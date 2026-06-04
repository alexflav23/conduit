package com.hypervolt.conduit.stockops

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Maker-checker stock operations (doc 04 §Stock ops): the requester cannot approve their own action;
// on approval each posts an immutable stock_movement + a ledger write-down at the units' specific batch
// cost, fully reconstructable. Cycle count, transfer (in-transit), and write-off/adjustment.
final class StockOpsService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val gbpLedger = Ledgers.forCurrency(Currency.GBP)

  def invAccount(entity: UUID): BigInt        = TbIds.accountId(s"INV:$entity")
  def writeOffAccount(entity: UUID): BigInt   = TbIds.accountId(s"INV_WRITEOFF:$entity")

  private def minor(amount: BigDecimal): BigInt = (amount.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // ----- write-off / damage / adjustment -----

  def requestAdjustment(entity: UUID, location: UUID, variant: UUID, serials: List[String], qty: Int, kind: String, reason: String, requestedBy: UUID): F[UUID] =
    sql"""INSERT INTO stock_adjustment (entity_id, location_id, product_variant_id, serials, qty, kind, reason_code, status, requested_by)
          VALUES ($entity, $location, $variant, $serials, $qty, $kind, $reason, 'pending_approval', $requestedBy) RETURNING id"""
      .query[UUID].unique.transact(xa)

  def approveAdjustment(adjustmentId: UUID, approver: UUID): F[Either[String, Unit]] =
    loadAdjustment(adjustmentId).transact(xa).flatMap {
      case None => "no such adjustment".asLeft[Unit].pure[F]
      case Some((entity, location, variant, serials, qty, requestedBy, status)) =>
        if (status != "pending_approval") "adjustment not pending".asLeft[Unit].pure[F]
        else if (approver == requestedBy) "maker cannot be checker (self-approval rejected)".asLeft[Unit].pure[F]
        else
          costOf(serials, variant, qty).flatMap { cost =>
            ledger.postTransfers(List(LedgerTransfer(
              id = TbIds.transferId(adjustmentId, 0),
              debitAccountId = writeOffAccount(entity),
              creditAccountId = invAccount(entity),
              amount = minor(cost),
              ledger = gbpLedger,
              code = LedgerTransferCode.Generic
            ))) *> postAdjustment(adjustmentId, entity, location, variant, serials, qty, approver).transact(xa).as(().asRight[String])
          }
    }

  private def postAdjustment(adjustmentId: UUID, entity: UUID, location: UUID, variant: UUID, serials: List[String], qty: Int, approver: UUID): ConnectionIO[Unit] =
    for {
      _ <- sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id, reason_code, actor_user_id)
                 VALUES ('write_off', $variant, $location, $entity, ${-qty}, 'stock_adjustment', $adjustmentId, 'write_off', $approver)""".update.run
      _ <- sql"UPDATE stock_item SET qty_on_hand = qty_on_hand - $qty, updated_at = now() WHERE entity_id = $entity AND product_variant_id = $variant AND location_id = $location".update.run
      _ <- serials.traverse_(s => sql"UPDATE serial_unit SET status = 'scrapped' WHERE serial_no = $s".update.run.void)
      _ <- sql"UPDATE stock_adjustment SET status = 'posted', approved_by = $approver, tb_transfer_id = ${TbIds.transferId(adjustmentId, 0).toString} WHERE id = $adjustmentId".update.run
    } yield ()

  // ----- cycle count -----

  def submitCount(entity: UUID, location: UUID, lines: List[(UUID, Int, Int)], countedBy: UUID): F[UUID] =
    (for {
      countId <- sql"INSERT INTO stock_count (entity_id, location_id, status, counted_by) VALUES ($entity, $location, 'pending_approval', $countedBy) RETURNING id".query[UUID].unique
      _ <- lines.traverse_ { case (variant, counted, system) =>
             sql"INSERT INTO stock_count_line (count_id, product_variant_id, system_qty, counted_qty, variance) VALUES ($countId, $variant, $system, $counted, ${counted - system})".update.run.void
           }
    } yield countId).transact(xa)

  def approveCount(countId: UUID, approver: UUID): F[Either[String, Unit]] =
    sql"SELECT entity_id, location_id, counted_by, status FROM stock_count WHERE id = $countId".query[(Option[UUID], UUID, UUID, String)].option.transact(xa).flatMap {
      case None => "no such count".asLeft[Unit].pure[F]
      case Some((entityOpt, location, countedBy, status)) =>
        if (status != "pending_approval") "count not pending".asLeft[Unit].pure[F]
        else if (approver == countedBy) "maker cannot be checker (self-approval rejected)".asLeft[Unit].pure[F]
        else {
          val entity = entityOpt.getOrElse(new UUID(0L, 0L))
          for {
            vs <- variances(countId).transact(xa)
            _  <- vs.traverse_ { case (variant, variance) => ledgerForVariance(entity, variant, variance) }
            _  <- postCount(countId, location, entity, approver).transact(xa)
          } yield ().asRight[String]
        }
    }

  private def ledgerForVariance(entity: UUID, variant: UUID, variance: Int): F[Unit] =
    if (variance == 0) Async[F].unit
    else
      variantCost(variant).flatMap { unit =>
        val amount = minor(BigDecimal(math.abs(variance)) * unit)
        // shrinkage (variance<0): CR INV; found (variance>0): DR INV
        val (debit, credit) = if (variance < 0) (writeOffAccount(entity), invAccount(entity)) else (invAccount(entity), writeOffAccount(entity))
        ledger.postTransfers(List(LedgerTransfer(TbIds.transferId(UUID.randomUUID(), 0), debit, credit, amount, gbpLedger, LedgerTransferCode.Generic)))
      }

  private def postCount(countId: UUID, location: UUID, entity: UUID, approver: UUID): ConnectionIO[Unit] =
    variances(countId).flatMap { vs =>
      vs.traverse_ { case (variant, variance) =>
        sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id, reason_code, actor_user_id)
              VALUES ('count_correction', $variant, $location, $entity, $variance, 'stock_count', $countId, 'cycle_count', $approver)""".update.run.void *>
          sql"UPDATE stock_item SET qty_on_hand = qty_on_hand + $variance, updated_at = now() WHERE entity_id = $entity AND product_variant_id = $variant AND location_id = $location".update.run.void
      } *> sql"UPDATE stock_count SET status = 'posted', approved_by = $approver WHERE id = $countId".update.run.void
    }

  // ----- transfer (in-transit) -----

  def requestTransfer(from: UUID, to: UUID, entity: UUID, variant: UUID, qty: Int, requestedBy: UUID): F[UUID] =
    sql"""INSERT INTO stock_transfer (from_location_id, to_location_id, entity_id, product_variant_id, qty, status, requested_by)
          VALUES ($from, $to, $entity, $variant, $qty, 'requested', $requestedBy) RETURNING id""".query[UUID].unique.transact(xa)

  def approveTransfer(transferId: UUID, approver: UUID): F[Either[String, Unit]] =
    sql"SELECT from_location_id, entity_id, product_variant_id, qty, requested_by, status FROM stock_transfer WHERE id = $transferId"
      .query[(UUID, Option[UUID], UUID, Int, UUID, String)].option.transact(xa).flatMap {
        case None => "no such transfer".asLeft[Unit].pure[F]
        case Some((from, entity, variant, qty, requestedBy, status)) =>
          if (status != "requested") "transfer not in requested state".asLeft[Unit].pure[F]
          else if (approver == requestedBy) "maker cannot be checker (self-approval rejected)".asLeft[Unit].pure[F]
          else
            (sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id, actor_user_id)
                   VALUES ('transfer_out', $variant, $from, $entity, ${-qty}, 'stock_transfer', $transferId, $approver)""".update.run *>
              sql"UPDATE stock_item SET qty_on_hand = qty_on_hand - $qty WHERE entity_id = $entity AND product_variant_id = $variant AND location_id = $from".update.run *>
              sql"UPDATE stock_transfer SET status = 'in_transit', approved_by = $approver, dispatched_at = now() WHERE id = $transferId".update.run)
              .transact(xa).as(().asRight[String])
      }

  def receiveTransfer(transferId: UUID): F[Unit] =
    sql"SELECT to_location_id, entity_id, product_variant_id, qty FROM stock_transfer WHERE id = $transferId AND status = 'in_transit'"
      .query[(UUID, Option[UUID], UUID, Int)].option.flatMap {
        case None => ().pure[ConnectionIO]
        case Some((to, entity, variant, qty)) =>
          sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id)
                VALUES ('transfer_in', $variant, $to, $entity, $qty, 'stock_transfer', $transferId)""".update.run *>
            sql"""INSERT INTO stock_item (entity_id, product_variant_id, location_id, qty_on_hand) VALUES ($entity, $variant, $to, $qty)
                  ON CONFLICT (entity_id, product_variant_id, location_id) DO UPDATE SET qty_on_hand = stock_item.qty_on_hand + $qty""".update.run *>
            sql"UPDATE stock_transfer SET status = 'received', received_at = now() WHERE id = $transferId".update.run.void
      }.transact(xa)

  // ----- helpers -----

  private def loadAdjustment(id: UUID): ConnectionIO[Option[(UUID, UUID, UUID, List[String], Int, UUID, String)]] =
    sql"SELECT entity_id, location_id, product_variant_id, serials, qty, requested_by, status FROM stock_adjustment WHERE id = $id"
      .query[(UUID, UUID, UUID, List[String], Int, UUID, String)].option

  private def variances(countId: UUID): ConnectionIO[List[(UUID, Int)]] =
    sql"SELECT product_variant_id, variance FROM stock_count_line WHERE count_id = $countId".query[(UUID, Int)].to[List]

  private def costOf(serials: List[String], variant: UUID, qty: Int): F[BigDecimal] =
    if (serials.nonEmpty)
      serials.traverse(s => serialCost(s)).transact(xa).map(_.flatten.foldLeft(BigDecimal(0))(_ + _))
    else variantCost(variant).map(_ * BigDecimal(qty))

  private def serialCost(serialNo: String): ConnectionIO[Option[BigDecimal]] =
    sql"SELECT b.landed_unit_cost FROM serial_unit s JOIN lot_batch b ON b.id = s.lot_batch_id WHERE s.serial_no = $serialNo".query[BigDecimal].option

  private def variantCost(variant: UUID): F[BigDecimal] =
    sql"SELECT landed_unit_cost FROM lot_batch WHERE product_variant_id = $variant ORDER BY received_date DESC LIMIT 1".query[BigDecimal].option.transact(xa).map(_.getOrElse(BigDecimal(0)))
}
