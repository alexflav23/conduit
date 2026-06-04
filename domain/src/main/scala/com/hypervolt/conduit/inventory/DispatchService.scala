package com.hypervolt.conduit.inventory

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

final case class DispatchLineInput(orderLineId: UUID, qty: Int, serials: List[String])
private final case class ValidatedLine(
    lineId: UUID,
    variant: UUID,
    qty: Int,
    serials: List[String],
    serialised: Boolean
)

// Dispatch + delivery (doc 04 §Orders). A serialised line cannot dispatch without serials; dispatch
// decrements stock + flips serials; delivery auto-issues a per-drop invoice and emits dispatch.delivered.
final class DispatchService[F[_]: Async](xa: Transactor[F]) {

  def dispatch(
      orderId: UUID,
      trancheId: Option[UUID],
      carrierId: Option[UUID],
      tracking: Option[String],
      lines: List[DispatchLineInput]
  ): F[Either[String, UUID]] =
    lines
      .traverse(validateLine)
      .map(_.sequence)
      .flatMap {
        case Left(err) => err.asLeft[UUID].pure[ConnectionIO]
        case Right(validated) =>
          for {
            dispatchNo <- sql"SELECT 'DSP-' || nextval('dispatch_no_seq')".query[String].unique
            dispatchId <-
              sql"""INSERT INTO dispatch (dispatch_no, order_id, tranche_id, carrier_id, tracking_no, status)
                                VALUES ($dispatchNo, $orderId, $trancheId, $carrierId, $tracking, 'created') RETURNING id"""
                .query[UUID]
                .unique
            _ <- validated.traverse_(v => shipLine(dispatchId, trancheId, v))
            // Conduit OWNS the serial→customer attribution: stamp every dispatched serial with the buying account
            // here (no MRPeasy lookup). This is what makes per-customer stock automatic and real-time.
            _ <- sql"""UPDATE serial_unit SET company_id = (SELECT sold_to_party_id FROM "order" WHERE id = $orderId)
                    WHERE dispatch_id = $dispatchId""".update.run
            _ <-
              sql"""UPDATE "order" SET dispatched_at = COALESCE(dispatched_at, now()), updated_at = now() WHERE id = $orderId""".update.run
            _ <- OutboxRepo.append(
              event(
                orderId,
                "dispatch.created",
                Json.obj("dispatch_no" -> dispatchNo.asJson, "tranche_id" -> trancheId.map(_.toString).asJson)
              )
            )
          } yield dispatchId.asRight[String]
      }
      .transact(xa)

  def deliver(dispatchId: UUID): F[Either[String, String]] =
    sql"SELECT order_id, tranche_id FROM dispatch WHERE id = $dispatchId"
      .query[(UUID, Option[UUID])]
      .option
      .flatMap {
        case None => "unknown dispatch".asLeft[String].pure[ConnectionIO]
        case Some((orderId, tranche)) =>
          for {
            _ <- sql"UPDATE dispatch SET status = 'delivered', delivered_at = now() WHERE id = $dispatchId".update.run
            _ <- tranche.fold(Sync0)(t =>
              sql"UPDATE delivery_tranche SET status = 'delivered' WHERE id = $t".update.run.void
            )
            invoiceNo <- sql"SELECT 'INV-' || nextval('invoice_no_seq')".query[String].unique
            _ <-
              sql"""INSERT INTO order_invoice (order_id, tranche_id, invoice_no, total_ex_vat, vat_total, total_inc_vat)
                     SELECT $orderId, $tranche, $invoiceNo, subtotal_ex_vat, vat_total, total_inc_vat FROM "order" WHERE id = $orderId""".update.run
            _ <- tranche.fold(Sync0)(t =>
              sql"UPDATE delivery_tranche SET status = 'invoiced' WHERE id = $t".update.run.void
            )
            _ <- OutboxRepo.append(
              event(
                orderId,
                "dispatch.delivered",
                Json.obj("dispatch_id" -> dispatchId.toString.asJson, "tranche_id" -> tranche.map(_.toString).asJson)
              )
            )
            _ <- OutboxRepo.append(
              event(
                orderId,
                "order.invoiced",
                Json.obj("invoice_no" -> invoiceNo.asJson, "tranche_id" -> tranche.map(_.toString).asJson)
              )
            )
          } yield invoiceNo.asRight[String]
      }
      .transact(xa)

  private val Sync0: ConnectionIO[Unit] = ().pure[ConnectionIO]

  private def validateLine(in: DispatchLineInput): ConnectionIO[Either[String, ValidatedLine]] =
    sql"""SELECT pv.is_serialised, ol.product_variant_id FROM order_line ol
          JOIN product_variant pv ON pv.id = ol.product_variant_id WHERE ol.id = ${in.orderLineId}"""
      .query[(Boolean, UUID)]
      .option
      .map {
        case None => Left(s"unknown order line ${in.orderLineId}")
        case Some((serialised, variant)) =>
          if (serialised && in.serials.size != in.qty)
            Left(s"serialised line requires ${in.qty} serials, got ${in.serials.size}")
          else Right(ValidatedLine(in.orderLineId, variant, in.qty, in.serials, serialised))
      }

  private def shipLine(dispatchId: UUID, tranche: Option[UUID], v: ValidatedLine): ConnectionIO[Unit] =
    for {
      _ <-
        sql"INSERT INTO dispatch_line (dispatch_id, order_line_id, tranche_id, qty) VALUES ($dispatchId, ${v.lineId}, $tranche, ${v.qty})".update.run
      _ <-
        if (v.serialised) v.serials.traverse_(s => shipSerial(dispatchId, s))
        else shipNonSerial(v.lineId, tranche, v.qty)
      _ <-
        sql"UPDATE order_line SET qty_dispatched = qty_dispatched + ${v.qty}, status = 'dispatched' WHERE id = ${v.lineId}".update.run
      _ <- tranche.fold(Sync0)(t =>
        sql"UPDATE delivery_tranche SET qty_dispatched = qty_dispatched + ${v.qty}, status = 'dispatched', dispatch_id = $dispatchId WHERE id = $t".update.run.void
      )
    } yield ()

  private def shipSerial(dispatchId: UUID, serialNo: String): ConnectionIO[Unit] =
    sql"SELECT id, product_variant_id, location_id, entity_id FROM serial_unit WHERE serial_no = $serialNo"
      .query[(UUID, UUID, Option[UUID], Option[UUID])]
      .unique
      .flatMap {
        case (sid, variant, locOpt, entOpt) =>
          sql"UPDATE serial_unit SET status = 'dispatched', dispatch_id = $dispatchId WHERE id = $sid".update.run.void *>
            locOpt.fold(Sync0) { loc =>
              sql"""UPDATE stock_item SET qty_on_hand = qty_on_hand - 1, qty_allocated = GREATEST(qty_allocated - 1, 0), updated_at = now()
                  WHERE product_variant_id = $variant AND location_id = $loc""".update.run.void *>
                sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id)
                    VALUES ('dispatch', $variant, $loc, $entOpt, -1, 'dispatch', $dispatchId)""".update.run.void
            }
      }

  private def shipNonSerial(line: UUID, tranche: Option[UUID], qty: Int): ConnectionIO[Unit] = {
    val where = fr"WHERE order_line_id = $line" ++ tranche.fold(Fragment.empty)(t => fr"AND tranche_id = $t")
    (fr"SELECT location_id FROM allocation" ++ where ++ fr"LIMIT 1").query[UUID].option.flatMap {
      case None => Sync0
      case Some(loc) =>
        sql"""UPDATE stock_item SET qty_on_hand = qty_on_hand - $qty, qty_allocated = GREATEST(qty_allocated - $qty, 0), updated_at = now()
              WHERE location_id = $loc AND product_variant_id = (SELECT product_variant_id FROM order_line WHERE id = $line)""".update.run.void *>
          sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, qty, ref_type, ref_id)
                SELECT 'dispatch', product_variant_id, $loc, -$qty, 'dispatch', $line FROM order_line WHERE id = $line""".update.run.void
    }
  }

  private def event(orderId: UUID, eventType: String, payload: Json): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      eventType,
      1,
      "order",
      orderId,
      orderId.toString,
      None,
      None,
      None,
      payload,
      Instant.now()
    )
}
