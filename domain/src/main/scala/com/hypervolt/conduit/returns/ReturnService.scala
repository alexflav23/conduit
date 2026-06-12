package com.hypervolt.conduit.returns

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class RaiseLine(orderLineId: UUID, serialNo: Option[String], componentRef: Option[String], qty: Int)

// Returns / RMA (doc 09). Every transition emits a return.* event through the outbox so downstream
// consumers (tracking, label print/dispatch, comms, Xero) attach with no core change. Money reverses by
// reversing transfers at the unit's specific batch cost; serials never silently re-enter sellable stock.
final class ReturnService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  def arAccount(party: UUID): BigInt                         = TbIds.accountId(s"AR:$party")
  def revenueAccount(entity: UUID): BigInt                   = TbIds.accountId(s"REVENUE:$entity")
  def vatAccount(entity: UUID, jurisdiction: String): BigInt = TbIds.accountId(s"VAT:$entity:$jurisdiction")
  def invAccount(entity: UUID): BigInt                       = TbIds.accountId(s"INV:$entity")
  def cosClearing(entity: UUID): BigInt                      = TbIds.accountId(s"COS_CLEARING:$entity")
  def commPayable(agent: UUID): BigInt                       = TbIds.accountId(s"COMM_PAYABLE:$agent:GBP")
  def commExpense(agent: UUID): BigInt                       = TbIds.accountId(s"COMM_EXPENSE:$agent:GBP")

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

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

  // ----- disposition -----

  def disposition(
      rmaId: UUID,
      lineId: UUID,
      choice: String,
      locationId: Option[UUID],
      actor: UUID
  ): F[Either[String, Unit]] =
    statusOf(rmaId).transact(xa).flatMap {
      case Some("received") => dispositionLine(rmaId, lineId, choice, locationId, actor)
      case Some(other)      => s"cannot disposition in status $other (goods not received)".asLeft[Unit].pure[F]
      case None             => "no such rma".asLeft[Unit].pure[F]
    }

  private def dispositionLine(
      rmaId: UUID,
      lineId: UUID,
      choice: String,
      locationId: Option[UUID],
      actor: UUID
  ): F[Either[String, Unit]] =
    loadLine(lineId).transact(xa).flatMap {
      case None => "no such rma line".asLeft[Unit].pure[F]
      case Some(line) =>
        val guard: Either[String, Unit] =
          if (choice == "restock") {
            if (!line.grade.contains("a")) Left("restock requires A-grade condition")
            else if (line.activated) Left("an activated unit cannot be restocked (refurbish or scrap)")
            else Right(())
          } else Right(())
        guard match {
          case Left(e) => e.asLeft[Unit].pure[F]
          case Right(_) =>
            val cost = line.unitLandedCost.getOrElse(BigDecimal(0))
            val invLeg = choice match {
              case "restock" | "refurbish" if line.entityId.isDefined && cost > 0 =>
                journal.postOne(
                  Instant.now(),
                  Posting(
                    lineId,
                    1,
                    JournalAccount(s"INV:${line.entityId.get}", LedgerAccountCode.Inv, line.entityId),
                    JournalAccount(s"COS_CLEARING:${line.entityId.get}", LedgerAccountCode.CosClearing, line.entityId),
                    Currency.GBP,
                    minor(cost)
                  )
                )
              case _ => Async[F].unit
            }
            // doc 28 §2.5: a returned unit under flash title unwinds its pro-rata share of the IC uplift —
            // the operating entity's COGS comes back down to landed for that unit, and the principal gives
            // back exactly its share of the markup. The genealogy accumulates on the match (returned_uplift).
            val flashUnwind = (choice, line.serialId) match {
              case ("restock" | "refurbish", Some(sid)) =>
                com.hypervolt.conduit.intercompany.FlashTitle.upliftShareForSerial(sid).transact(xa).flatMap {
                  case None => Async[F].unit
                  case Some((dispatchId, op, pr, share)) if share != 0 =>
                    val e2     = Some(op)
                    val pe     = Some(pr)
                    val icAp   = JournalAccount(s"IC_AP:$op:$pr", LedgerAccountCode.Intercompany, e2)
                    val icAr   = JournalAccount(s"IC_AR:$pr:$op", LedgerAccountCode.Intercompany, pe)
                    val margin = JournalAccount(s"IC_MARGIN:$pr", LedgerAccountCode.IcMargin, pe)
                    val cogsA  = JournalAccount(s"COGS:$op", LedgerAccountCode.CosClearing, e2)
                    val amt    = minor(share.abs)
                    val opPair = if (share >= 0) (icAp, cogsA) else (cogsA, icAp)
                    val prPair = if (share >= 0) (margin, icAr) else (icAr, margin)
                    journal.post(
                      Instant.now(),
                      List(
                        Posting(lineId, 2, opPair._1, opPair._2, Currency.GBP, amt),
                        Posting(lineId, 3, prPair._1, prPair._2, Currency.GBP, amt)
                      )
                    ) *> com.hypervolt.conduit.intercompany.FlashTitle
                      .accumulateReturnedUplift(dispatchId, share)
                      .transact(xa)
                      .void
                  case Some(_) => Async[F].unit
                }
              case _ => Async[F].unit
            }
            invLeg *> flashUnwind *> postDisposition(rmaId, line, choice, locationId, actor)
              .transact(xa)
              .as(().asRight[String])
        }
    }

  private def postDisposition(
      rmaId: UUID,
      line: RmaLineRow,
      choice: String,
      locationId: Option[UUID],
      actor: UUID
  ): ConnectionIO[Unit] = {
    val newStatus = choice match {
      case "restock"   => "in_stock"
      case "refurbish" => "refurbished"
      case _           => "scrapped" // scrap / return_to_supplier
    }
    val movementType = choice match {
      case "restock" | "refurbish" => "return"
      case "return_to_supplier"    => "transfer_out"
      case _                       => "write_off"
    }
    val qty = if (choice == "restock" || choice == "refurbish") 1 else 0
    for {
      mv <-
        sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type, ref_id, reason_code, actor_user_id)
                  VALUES ($movementType, ${line.variant}, ${locationId.orElse(
          line.locationId
        )}, ${line.entityId}, $qty, 'rma', $rmaId, 'return', $actor) RETURNING id""".query[UUID].unique
      _ <- line.serialId.fold(().pure[ConnectionIO])(sid =>
        sql"UPDATE serial_unit SET status=$newStatus, location_id=COALESCE($locationId, location_id), order_line_id = CASE WHEN $choice='restock' THEN NULL ELSE order_line_id END WHERE id=$sid".update.run.void
      )
      _ <-
        sql"""INSERT INTO return_disposition (rma_line_id, serial_unit_id, disposition, from_status, to_status, location_id, stock_movement_id, actor_user_id)
                 VALUES (${line.id}, ${line.serialId}, $choice, 'returned', $newStatus, $locationId, $mv, $actor)""".update.run
      _ <- line.serialId.fold(().pure[ConnectionIO])(sid =>
        sql"INSERT INTO unit_lifecycle_event (serial_unit_id, event_type, ref_type, ref_id, actor_user_id) VALUES ($sid, $choice, 'rma', $rmaId, $actor)".update.run.void
      )
      _ <-
        sql"UPDATE rma_line SET status='dispositioned', disposition=$choice, restock_location_id=$locationId WHERE id=${line.id}".update.run
      _ <- OutboxRepo.append(
        event(
          rmaId,
          "return.restocked",
          Json.obj(
            "rma_line_id" -> line.id.toString.asJson,
            "disposition" -> choice.asJson,
            "to_status"   -> newStatus.asJson
          )
        )
      )
    } yield ()
  }

  // ----- refund (credit note + ledger reversal + commission claw) -----

  def refund(rmaId: UUID, method: String): F[Either[String, Unit]] =
    loadHeader(rmaId).transact(xa).flatMap {
      case None => "no such rma".asLeft[Unit].pure[F]
      case Some(h) =>
        if (h.status == "raised" || h.status == "assessed") "cannot refund before approval".asLeft[Unit].pure[F]
        else if (h.status == "refunded" || h.status == "closed") "already refunded".asLeft[Unit].pure[F]
        else
          ruleFor(h.rType).transact(xa).flatMap { rule =>
            val refund = h.refundAmount.getOrElse(BigDecimal(0))
            for {
              vatRate <- vatRateFor(rmaId).transact(xa)
              vatJur  <- vatJurisdictionFor(h.orderId).transact(xa)
              vat    = (refund * vatRate / 100).setScale(2, RoundingMode.HALF_UP)
              entity = h.entityId.getOrElse(new UUID(0L, 0L))
              // AR/VAT/revenue reversal (only if a refund is due)
              ar = JournalAccount(s"AR:${h.billTo}", LedgerAccountCode.Ar, Some(entity))
              _ <-
                if (refund > 0)
                  journal.post(
                    Instant.now(),
                    List(
                      Posting(
                        rmaId,
                        10,
                        JournalAccount(s"REVENUE:$entity", LedgerAccountCode.Revenue, Some(entity)),
                        ar,
                        Currency.GBP,
                        minor(refund)
                      ),
                      Posting(
                        rmaId,
                        11,
                        JournalAccount(s"VAT:$entity:$vatJur", LedgerAccountCode.Vat, Some(entity)),
                        ar,
                        Currency.GBP,
                        minor(vat)
                      )
                    )
                  )
                else Async[F].unit
              _ <- if (refund > 0) issueCreditNote(rmaId, h, refund, vat, method).transact(xa) else ().pure[F]
              _ <-
                if (rule.commissionTreatment == "claw") clawCommission(rmaId).transact(xa).flatMap(effectClaw)
                else ().pure[F]
              _ <-
                sql"UPDATE rma SET status='refunded', tb_reversal_group=${TbIds.transferId(rmaId, 10).toString}, updated_at=now() WHERE id=$rmaId".update.run
                  .transact(xa)
              _ <-
                OutboxRepo
                  .append(
                    event(
                      rmaId,
                      "return.refunded",
                      Json.obj("refund_method" -> method.asJson, "total_inc_vat" -> (refund + vat).toString.asJson)
                    )
                  )
                  .transact(xa)
            } yield ().asRight[String]
          }
    }

  private def issueCreditNote(
      rmaId: UUID,
      h: RmaHeader,
      ex: BigDecimal,
      vat: BigDecimal,
      method: String
  ): ConnectionIO[Unit] =
    for {
      cnId <-
        sql"""INSERT INTO credit_note (rma_id, order_id, credit_note_no, bill_to_party_id, total_ex_vat, vat_total, total_inc_vat, refund_method)
                    VALUES ($rmaId, ${h.orderId}, 'CN-' || nextval('credit_note_no_seq'), ${h.billTo}, $ex, $vat, ${ex + vat}, $method) RETURNING id"""
          .query[UUID]
          .unique
      _ <- sql"UPDATE rma SET credit_note_id=$cnId WHERE id=$rmaId".update.run
    } yield ()

  // Returns (agentId, amount) of the forward posted commission to claw, if any.
  private def clawCommission(rmaId: UUID): ConnectionIO[Option[(UUID, UUID, BigDecimal)]] =
    sql"""SELECT ce.id, ce.agent_id, ce.amount FROM rma_line rl
          JOIN commission_entry ce ON ce.id = rl.commission_entry_id
          WHERE rl.rma_id = $rmaId AND ce.status='posted' LIMIT 1""".query[(UUID, UUID, BigDecimal)].option

  private def effectClaw(claw: Option[(UUID, UUID, BigDecimal)]): F[Unit] =
    claw.fold(Async[F].unit) {
      case (entryId, agentId, amount) =>
        journal.postOne(
          Instant.now(),
          Posting(
            entryId,
            9,
            JournalAccount(s"COMM_PAYABLE:$agentId:GBP", LedgerAccountCode.CommPayable, None),
            JournalAccount(s"COMM_EXPENSE:$agentId:GBP", LedgerAccountCode.CommissionExpense, None),
            Currency.GBP,
            minor(amount),
            transferCode = LedgerTransferCode.Commission
          )
        ) *>
          sql"""INSERT INTO commission_entry (agent_id, scheme_id, order_id, basis_amount, rate_applied, amount, currency, kind, status, tb_transfer_id)
              SELECT agent_id, scheme_id, order_id, -basis_amount, rate_applied, -amount, currency, 'claw', 'clawed', ${TbIds
            .transferId(entryId, 9)
            .toString}
              FROM commission_entry WHERE id=$entryId""".update.run.transact(xa).void
    }

  // ----- helpers -----

  private def vatRateFor(rmaId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(MAX(tr.rate_percent), 0) FROM rma_line rl
          JOIN order_line ol ON ol.id = rl.order_line_id
          LEFT JOIN tax_regime tr ON tr.code = ol.tax_regime WHERE rl.rma_id = $rmaId""".query[BigDecimal].unique

  // The VAT jurisdiction the refund must reverse — the original recognition's place of supply; falls back to the
  // selling entity's home jurisdiction, then GB (so the return nets the same VAT:<entity>:<jur> the sale accrued).
  private def vatJurisdictionFor(orderId: UUID): ConnectionIO[String] =
    sql"""SELECT COALESCE(
            (SELECT vat_jurisdiction FROM revenue_recognition WHERE order_id = $orderId AND vat_jurisdiction IS NOT NULL
               ORDER BY recognized_at DESC LIMIT 1),
            (SELECT e.jurisdiction FROM "order" o JOIN entity e ON e.id = o.entity_id WHERE o.id = $orderId),
            'GB')"""
      .query[String]
      .unique

  private def statusOf(rmaId: UUID): ConnectionIO[Option[String]] =
    sql"SELECT status FROM rma WHERE id = $rmaId".query[String].option

  private def ruleFor(rType: String): ConnectionIO[ReturnRule] =
    sql"""SELECT refund_basis, issues_replacement, replacement_priced, default_disposition, commission_treatment,
            warranty_effect, requires_memo, approval_threshold
          FROM return_type_rule WHERE type = $rType AND effective_to IS NULL ORDER BY version DESC LIMIT 1"""
      .query[(String, Boolean, Boolean, String, String, String, Boolean, Option[BigDecimal])]
      .unique
      .map { case (rb, ir, rp, dd, ct, we, rm, at) => ReturnRule(rb, ir, rp, dd, ct, we, rm, at) }

  private def loadHeader(rmaId: UUID): ConnectionIO[Option[RmaHeader]] =
    sql"""SELECT order_id, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id, type, status,
            refund_currency, refund_amount, requested_by
          FROM rma WHERE id = $rmaId"""
      .query[
        (UUID, Option[UUID], UUID, UUID, Option[UUID], Option[UUID], String, String, String, Option[BigDecimal], UUID)
      ]
      .option
      .map(_.map { case (o, e, s, b, c, m, t, st, cur, ra, rb) => RmaHeader(o, e, s, b, c, m, t, st, cur, ra, rb) })

  private def loadLine(lineId: UUID): ConnectionIO[Option[RmaLineRow]] =
    sql"""SELECT rl.id, rl.product_variant_id, rl.serial_unit_id, rl.condition_grade, rl.unit_landed_cost,
            r.entity_id, s.location_id, (s.activated_at IS NOT NULL)
          FROM rma_line rl JOIN rma r ON r.id = rl.rma_id
          LEFT JOIN serial_unit s ON s.id = rl.serial_unit_id WHERE rl.id = $lineId"""
      .query[(UUID, UUID, Option[UUID], Option[String], Option[BigDecimal], Option[UUID], Option[UUID], Boolean)]
      .option
      .map(_.map { case (id, v, sid, g, c, ent, loc, act) => RmaLineRow(id, v, sid, g, c, ent, loc, act) })

  private def event(rmaId: UUID, eventType: String, payload: Json): OutboxEvent =
    OutboxEvent(UUID.randomUUID(), eventType, 1, "rma", rmaId, rmaId.toString, None, None, None, payload, Instant.now())
}

private final case class ReturnRule(
    refundBasis: String,
    issuesReplacement: Boolean,
    replacementPriced: Boolean,
    defaultDisposition: String,
    commissionTreatment: String,
    warrantyEffect: String,
    requiresMemo: Boolean,
    approvalThreshold: Option[BigDecimal]
)
private final case class RmaHeader(
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
private final case class RmaLineRow(
    id: UUID,
    variant: UUID,
    serialId: Option[UUID],
    grade: Option[String],
    unitLandedCost: Option[BigDecimal],
    entityId: Option[UUID],
    locationId: Option[UUID],
    activated: Boolean
)
