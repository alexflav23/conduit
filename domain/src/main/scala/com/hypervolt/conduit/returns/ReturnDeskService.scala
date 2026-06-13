package com.hypervolt.conduit.returns

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
import scala.math.BigDecimal.RoundingMode

// The TB-free half of the RMA lifecycle (doc 09): raise / assess / approve / receive — pure Postgres + outbox,
// no TigerBeetle. The API drives these synchronously (it has no TB client — the no-TB-in-API rule), so the
// desk gets real 403 (SoD) / 422 (memo) responses; the money-posting transitions (disposition / refund) defer
// to the consumer. ReturnService composes this service and delegates the four, so the consumer keeps one
// surface and ReturnsSuite is unchanged.
final class ReturnDeskService[F[_]: Async](xa: Transactor[F]) {

  private[returns] def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // ----- raise -----

  def raise(
      orderId: UUID,
      rType: String,
      scope: String,
      reasonCode: String,
      requestedBy: UUID,
      lines: List[RaiseLine]
  ): F[UUID] =
    (for {
      ord <-
        sql"""SELECT entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id, txn_currency
                   FROM "order" WHERE id = $orderId"""
          .query[(Option[UUID], UUID, UUID, Option[UUID], Option[UUID], String)]
          .unique
      (entity, soldTo, billTo, channel, market, currency) = ord
      rmaId <-
        sql"""INSERT INTO rma (rma_no, order_id, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id,
                       type, scope, reason_code, refund_currency, status, requested_by)
                     VALUES ('RMA-' || nextval('rma_no_seq'), $orderId, $entity, $soldTo, $billTo, $channel, $market,
                       $rType, $scope, $reasonCode, $currency, 'raised', $requestedBy) RETURNING id"""
          .query[UUID]
          .unique
      _ <- lines.traverse_(l => insertRmaLine(rmaId, l))
      _ <- OutboxRepo.append(
        event(
          rmaId,
          "return.raised",
          Json.obj("order_id" -> orderId.toString.asJson, "type" -> rType.asJson, "reason_code" -> reasonCode.asJson)
        )
      )
    } yield rmaId).transact(xa)

  private def insertRmaLine(rmaId: UUID, l: RaiseLine): ConnectionIO[Unit] =
    for {
      meta <- l.serialNo.flatTraverse(serialMeta)
      (serialId, batchId, landed) = meta.getOrElse((Option.empty[UUID], Option.empty[UUID], Option.empty[BigDecimal]))
      variant <- sql"SELECT product_variant_id FROM order_line WHERE id = ${l.orderLineId}".query[UUID].unique
      commEntry <-
        sql"SELECT id FROM commission_entry WHERE order_id = (SELECT order_id FROM order_line WHERE id = ${l.orderLineId}) AND status='posted' LIMIT 1"
          .query[UUID]
          .option
      _ <- sql"""INSERT INTO rma_line (rma_id, order_line_id, product_variant_id, serial_unit_id, component_ref, qty,
                   lot_batch_id, unit_landed_cost, commission_entry_id, status)
                 VALUES ($rmaId, ${l.orderLineId}, $variant, $serialId, ${l.componentRef}, ${l.qty},
                   $batchId, $landed, $commEntry, 'expected')""".update.run
    } yield ()

  private def serialMeta(serialNo: String): ConnectionIO[Option[(Option[UUID], Option[UUID], Option[BigDecimal])]] =
    sql"""SELECT s.id, s.lot_batch_id, b.landed_unit_cost FROM serial_unit s
          LEFT JOIN lot_batch b ON b.id = s.lot_batch_id WHERE s.serial_no = $serialNo"""
      .query[(UUID, Option[UUID], Option[BigDecimal])]
      .option
      .map(_.map { case (sid, b, c) => (Some(sid), b, c) })

  // ----- assess -----

  def assess(rmaId: UUID, grades: List[(UUID, String)], assessedBy: UUID): F[Unit] =
    (grades.traverse_ {
      case (lineId, grade) =>
        sql"UPDATE rma_line SET condition_grade = $grade, status='assessed' WHERE id = $lineId".update.run.void
    } *>
      sql"UPDATE rma SET status='assessed', assessed_by=$assessedBy, updated_at=now() WHERE id=$rmaId".update.run *>
      OutboxRepo.append(event(rmaId, "return.assessed", Json.obj("assessed_by" -> assessedBy.toString.asJson))))
      .transact(xa)
      .void

  // ----- approve (maker-checker) -----

  def approve(rmaId: UUID, approver: UUID, memo: Option[String]): F[Either[String, Unit]] =
    loadHeader(rmaId).transact(xa).flatMap {
      case None => "no such rma".asLeft[Unit].pure[F]
      case Some(h) =>
        if (h.status != "raised" && h.status != "assessed") "rma not in an approvable state".asLeft[Unit].pure[F]
        else if (approver == h.requestedBy) "maker cannot be checker (self-approval rejected)".asLeft[Unit].pure[F]
        else
          ruleFor(h.rType).transact(xa).flatMap { rule =>
            if (rule.requiresMemo && memo.isEmpty) "approval memo required for this return type".asLeft[Unit].pure[F]
            else
              (for {
                refund <- computeRefund(rmaId, rule)
                _ <-
                  sql"UPDATE rma SET status='approved', approved_by=$approver, refund_amount=$refund, approval_memo_ref=$memo, updated_at=now() WHERE id=$rmaId".update.run
                replacementId <-
                  if (rule.issuesReplacement) issueReplacement(rmaId, h).map(Some(_))
                  else Option.empty[UUID].pure[ConnectionIO]
                _ <- replacementId.fold(().pure[ConnectionIO])(rid =>
                  sql"UPDATE rma SET replacement_order_id=$rid WHERE id=$rmaId".update.run.void *>
                    OutboxRepo
                      .append(
                        event(
                          rmaId,
                          "return.replaced",
                          Json.obj(
                            "replacement_order_id" -> rid.toString.asJson,
                            "priced"               -> rule.replacementPriced.asJson
                          )
                        )
                      )
                      .void
                )
                _ <- if (h.rType == "warranty_replacement") drawDownWarranty(rmaId) else ().pure[ConnectionIO]
                _ <- OutboxRepo.append(
                  event(
                    rmaId,
                    "return.approved",
                    Json.obj(
                      "approved_by"           -> approver.toString.asJson,
                      "refund_amount"         -> refund.toString.asJson,
                      "commission_claw_armed" -> (rule.commissionTreatment == "claw").asJson
                    )
                  )
                )
              } yield ().asRight[String]).transact(xa)
          }
    }

  private def computeRefund(rmaId: UUID, rule: ReturnRule): ConnectionIO[BigDecimal] =
    if (rule.refundBasis == "none" || rule.refundBasis == "per_approval") BigDecimal(0).pure[ConnectionIO]
    else
      sql"""SELECT COALESCE(SUM(ol.unit_price_ex_vat * rl.qty), 0) FROM rma_line rl
            JOIN order_line ol ON ol.id = rl.order_line_id WHERE rl.rma_id = $rmaId""".query[BigDecimal].unique

  private def issueReplacement(rmaId: UUID, h: RmaHeader): ConnectionIO[UUID] =
    sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id,
            status, txn_currency, payment_method, origin_rma_id)
          VALUES ('ORD-' || nextval('order_no_seq'), 'reseller', ${h.entityId}, ${h.soldTo}, ${h.billTo}, ${h.channel}, ${h.market},
            'placed', ${h.currency}, 'warranty', $rmaId) RETURNING id""".query[UUID].unique

  private def drawDownWarranty(rmaId: UUID): ConnectionIO[Unit] =
    sql"""UPDATE warranty_provision SET consumed_by_claims = consumed_by_claims + estimated_provision,
            outstanding = 0, status='claimed_out'
          WHERE serial_unit_id IN (SELECT serial_unit_id FROM rma_line WHERE rma_id = $rmaId AND serial_unit_id IS NOT NULL)""".update.run.void

  // ----- receive -----

  def receive(rmaId: UUID): F[Either[String, Unit]] =
    statusOf(rmaId).transact(xa).flatMap {
      case Some("approved") =>
        (sql"UPDATE rma SET status='received', received_at=now(), updated_at=now() WHERE id=$rmaId".update.run *>
          sql"UPDATE rma_line SET status='received' WHERE rma_id=$rmaId".update.run *>
          sql"UPDATE serial_unit SET status='returned' WHERE id IN (SELECT serial_unit_id FROM rma_line WHERE rma_id=$rmaId AND serial_unit_id IS NOT NULL)".update.run *>
          OutboxRepo.append(event(rmaId, "return.received", Json.obj()))).transact(xa).as(().asRight[String])
      case Some(other) => s"cannot receive in status $other".asLeft[Unit].pure[F]
      case None        => "no such rma".asLeft[Unit].pure[F]
    }

  // ----- synchronous validation for the TB-deferred transitions (doc 09 §L 422s) -----
  // The API runs these before recording the disposition/refund command, so the desk gets a real 422; the
  // consumer (ReturnService) re-checks defensively at posting time. Pure SQL, no TigerBeetle.

  def validateDisposition(rmaId: UUID, lineId: UUID, choice: String): F[Either[String, Unit]] =
    (statusOf(rmaId), dispositionGuard(lineId, choice)).tupled.transact(xa).map {
      case (None, _)                 => "no such rma".asLeft[Unit]
      case (Some("received"), guard) => guard
      case (Some(other), _)          => s"cannot disposition in status $other (goods not received)".asLeft[Unit]
    }

  private def dispositionGuard(lineId: UUID, choice: String): ConnectionIO[Either[String, Unit]] =
    if (choice != "restock") ().asRight[String].pure[ConnectionIO]
    else
      sql"""SELECT rl.condition_grade, (s.activated_at IS NOT NULL)
            FROM rma_line rl LEFT JOIN serial_unit s ON s.id = rl.serial_unit_id WHERE rl.id = $lineId"""
        .query[(Option[String], Boolean)]
        .option
        .map {
          case None                                     => "no such rma line".asLeft[Unit]
          case Some((grade, _)) if !grade.contains("a") => "restock requires A-grade condition".asLeft[Unit]
          case Some((_, true))                          => "an activated unit cannot be restocked (refurbish or scrap)".asLeft[Unit]
          case Some(_)                                  => ().asRight[String]
        }

  def validateRefund(rmaId: UUID): F[Either[String, Unit]] =
    statusOf(rmaId).transact(xa).map {
      case None                              => "no such rma".asLeft[Unit]
      case Some("raised") | Some("assessed") => "cannot refund before approval".asLeft[Unit]
      case Some("refunded") | Some("closed") => "already refunded".asLeft[Unit]
      case Some(_)                           => Right(())
    }

  // Record the TB-deferred transitions as command events (one tx, after a synchronous guard). The consumer
  // (ReturnService) effects the TigerBeetle posting — the API never touches TB (doc 19 no-TB-in-API).
  def requestDisposition(
      rmaId: UUID,
      lineId: UUID,
      choice: String,
      locationId: Option[UUID],
      actor: UUID
  ): F[Either[String, Unit]] =
    validateDisposition(rmaId, lineId, choice).flatMap {
      case Left(e) => e.asLeft[Unit].pure[F]
      case Right(_) =>
        OutboxRepo
          .append(
            event(
              rmaId,
              "return.disposition_requested",
              Json.obj(
                "rma_line_id" -> lineId.toString.asJson,
                "disposition" -> choice.asJson,
                "location_id" -> locationId.map(_.toString).asJson,
                "actor"       -> actor.toString.asJson
              )
            )
          )
          .transact(xa)
          .as(().asRight[String])
    }

  def requestRefund(rmaId: UUID, method: String, actor: UUID): F[Either[String, Unit]] =
    validateRefund(rmaId).flatMap {
      case Left(e) => e.asLeft[Unit].pure[F]
      case Right(_) =>
        OutboxRepo
          .append(
            event(
              rmaId,
              "return.refund_requested",
              Json.obj("refund_method" -> method.asJson, "actor" -> actor.toString.asJson)
            )
          )
          .transact(xa)
          .as(().asRight[String])
    }

  // requested_by — the SoD check at the API boundary needs it before the command is even recorded.
  def requestedBy(rmaId: UUID): F[Option[UUID]] =
    sql"SELECT requested_by FROM rma WHERE id = $rmaId".query[UUID].option.transact(xa)

  // ----- shared reads (used by ReturnService's TB half too) -----

  private[returns] def loadHeader(rmaId: UUID): ConnectionIO[Option[RmaHeader]] =
    sql"""SELECT order_id, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id, type, status,
            refund_currency, refund_amount, requested_by
          FROM rma WHERE id = $rmaId"""
      .query[
        (UUID, Option[UUID], UUID, UUID, Option[UUID], Option[UUID], String, String, String, Option[BigDecimal], UUID)
      ]
      .option
      .map(_.map { case (o, e, s, b, c, m, t, st, cur, ra, rb) => RmaHeader(o, e, s, b, c, m, t, st, cur, ra, rb) })

  private[returns] def ruleFor(rType: String): ConnectionIO[ReturnRule] =
    sql"""SELECT refund_basis, issues_replacement, replacement_priced, default_disposition, commission_treatment,
            warranty_effect, requires_memo, approval_threshold
          FROM return_type_rule WHERE type = $rType AND effective_to IS NULL ORDER BY version DESC LIMIT 1"""
      .query[(String, Boolean, Boolean, String, String, String, Boolean, Option[BigDecimal])]
      .unique
      .map { case (rb, ir, rp, dd, ct, we, rm, at) => ReturnRule(rb, ir, rp, dd, ct, we, rm, at) }

  private[returns] def statusOf(rmaId: UUID): ConnectionIO[Option[String]] =
    sql"SELECT status FROM rma WHERE id = $rmaId".query[String].option

  private[returns] def event(rmaId: UUID, eventType: String, payload: Json): OutboxEvent =
    OutboxEvent(UUID.randomUUID(), eventType, 1, "rma", rmaId, rmaId.toString, None, None, None, payload, Instant.now())
}

private[returns] final case class ReturnRule(
    refundBasis: String,
    issuesReplacement: Boolean,
    replacementPriced: Boolean,
    defaultDisposition: String,
    commissionTreatment: String,
    warrantyEffect: String,
    requiresMemo: Boolean,
    approvalThreshold: Option[BigDecimal]
)
private[returns] final case class RmaHeader(
    orderId: UUID,
    entityId: Option[UUID],
    soldTo: UUID,
    billTo: UUID,
    channel: Option[UUID],
    market: Option[UUID],
    rType: String,
    status: String,
    currency: String,
    refundAmount: Option[BigDecimal],
    requestedBy: UUID
)
