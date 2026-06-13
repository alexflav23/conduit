package com.hypervolt.conduit.returns

import cats.effect.Async
import cats.syntax.all._
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
// The TB-free transitions (raise/assess/approve/receive) live in ReturnDeskService (so the API can drive them
// without a TB client); this service composes it and owns the money-posting transitions (disposition/refund).
final class ReturnService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)
  private val desk    = new ReturnDeskService[F](xa)

  def arAccount(party: UUID): BigInt                         = TbIds.accountId(s"AR:$party")
  def revenueAccount(entity: UUID): BigInt                   = TbIds.accountId(s"REVENUE:$entity")
  def vatAccount(entity: UUID, jurisdiction: String): BigInt = TbIds.accountId(s"VAT:$entity:$jurisdiction")
  def invAccount(entity: UUID): BigInt                       = TbIds.accountId(s"INV:$entity")
  def cosClearing(entity: UUID): BigInt                      = TbIds.accountId(s"COS_CLEARING:$entity")
  def commPayable(agent: UUID): BigInt                       = TbIds.accountId(s"COMM_PAYABLE:$agent:GBP")
  def commExpense(agent: UUID): BigInt                       = TbIds.accountId(s"COMM_EXPENSE:$agent:GBP")

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // ----- TB-free transitions: delegate to the desk service (one surface for the consumer + ReturnsSuite) -----

  def raise(
      orderId: UUID,
      rType: String,
      scope: String,
      reasonCode: String,
      requestedBy: UUID,
      lines: List[RaiseLine]
  ): F[UUID] = desk.raise(orderId, rType, scope, reasonCode, requestedBy, lines)

  def assess(rmaId: UUID, grades: List[(UUID, String)], assessedBy: UUID): F[Unit] =
    desk.assess(rmaId, grades, assessedBy)

  def approve(rmaId: UUID, approver: UUID, memo: Option[String]): F[Either[String, Unit]] =
    desk.approve(rmaId, approver, memo)

  def receive(rmaId: UUID): F[Either[String, Unit]] = desk.receive(rmaId)

  // ----- disposition (stock + serial + the inventory leg at batch cost) -----

  def disposition(
      rmaId: UUID,
      lineId: UUID,
      choice: String,
      locationId: Option[UUID],
      actor: UUID
  ): F[Either[String, Unit]] =
    desk.statusOf(rmaId).transact(xa).flatMap {
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
              case "restock" | "refurbish" if line.entityId.isDefined && minor(cost) > 0 =>
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
                ) *> claimRestock(lineId)
              case _ => Async[F].unit
            }
            // doc 28 §2.5: a returned unit under flash title unwinds its pro-rata share of the IC uplift —
            // the operating entity's COGS comes back down to landed for that unit, and the principal gives
            // back exactly its share of the markup. The genealogy accumulates on the match (returned_uplift).
            val flashUnwind = (choice, line.serialId) match {
              case ("restock" | "refurbish", Some(sid)) =>
                com.hypervolt.conduit.intercompany.FlashTitle.upliftShareForSerial(sid).transact(xa).flatMap {
                  case None => Async[F].unit
                  case Some((dispatchId, op, pr, share)) if minor(share.abs) > 0 =>
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
                    ) *> claimUnwind(lineId) *> com.hypervolt.conduit.intercompany.FlashTitle
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
        desk.event(
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
    desk.loadHeader(rmaId).transact(xa).flatMap {
      case None => "no such rma".asLeft[Unit].pure[F]
      case Some(h) =>
        if (h.status == "raised" || h.status == "assessed") "cannot refund before approval".asLeft[Unit].pure[F]
        else if (h.status == "refunded" || h.status == "closed") "already refunded".asLeft[Unit].pure[F]
        else
          desk.ruleFor(h.rType).transact(xa).flatMap { rule =>
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
              refundArTid  = Option.when(minor(refund) > 0)(BigDecimal(TbIds.transferId(rmaId, 10)))
              refundVatTid = Option.when(minor(vat) > 0)(BigDecimal(TbIds.transferId(rmaId, 11)))
              legacyGroup  = refundArTid.map(_.toBigInt.toString)
              _ <-
                sql"""UPDATE rma SET status='refunded', tb_reversal_group=$legacyGroup,
                        refund_ar_transfer_id=$refundArTid, refund_vat_transfer_id=$refundVatTid, updated_at=now()
                      WHERE id=$rmaId""".update.run
                  .transact(xa)
              _ <-
                OutboxRepo
                  .append(
                    desk.event(
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

  // Lineage claims (doc 29 A2): the posted legs' deterministic ids land on the line so
  // CTRL-LINEAGE-CLOSURE can walk gl_entry back to this disposition. Stamped iff posted.
  private def claimRestock(lineId: UUID): F[Unit] =
    sql"""UPDATE rma_line SET restock_tb_transfer_id = ${BigDecimal(TbIds.transferId(lineId, 1))}
          WHERE id = $lineId""".update.run.transact(xa).void

  private def claimUnwind(lineId: UUID): F[Unit] =
    sql"""UPDATE rma_line SET unwind_op_tb_transfer_id = ${BigDecimal(TbIds.transferId(lineId, 2))},
                              unwind_pr_tb_transfer_id = ${BigDecimal(TbIds.transferId(lineId, 3))}
          WHERE id = $lineId""".update.run.transact(xa).void

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

  private def loadLine(lineId: UUID): ConnectionIO[Option[RmaLineRow]] =
    sql"""SELECT rl.id, rl.product_variant_id, rl.serial_unit_id, rl.condition_grade, rl.unit_landed_cost,
            r.entity_id, s.location_id, (s.activated_at IS NOT NULL)
          FROM rma_line rl JOIN rma r ON r.id = rl.rma_id
          LEFT JOIN serial_unit s ON s.id = rl.serial_unit_id WHERE rl.id = $lineId"""
      .query[(UUID, UUID, Option[UUID], Option[String], Option[BigDecimal], Option[UUID], Option[UUID], Boolean)]
      .option
      .map(_.map { case (id, v, sid, g, c, ent, loc, act) => RmaLineRow(id, v, sid, g, c, ent, loc, act) })
}

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
