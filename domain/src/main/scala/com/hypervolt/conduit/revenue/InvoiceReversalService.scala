package com.hypervolt.conduit.revenue

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Journal
import com.hypervolt.conduit.ledger.JournalAccount
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.Posting
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
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

final case class InvoiceReversalResult(
    reversalId: UUID,
    invoiceNo: String,
    kind: String,
    reversedTotalIncVat: BigDecimal,
    invoiceStatus: String
)

private final case class ReversalHead(
    orderId: UUID,
    entity: Option[UUID],
    billTo: UUID,
    currency: String,
    status: String,
    invoiceNo: String,
    revenueExVat: BigDecimal,
    vat: BigDecimal,
    vatJurisdiction: String,
    cogs: BigDecimal,
    shipping: BigDecimal,
    dispatchId: Option[UUID],
    flashLanded: Option[BigDecimal], // ic_match decomposition: the physical leg posted LANDED
    flashUplift: Option[BigDecimal], // and the uplift pair carried the markup (doc 28 §2.5)
    flashProcurement: Option[UUID]
)

// Invoice invalidation (doc 13 §void, ASC 606). The immutable-log rule: never edit or delete an invoice — append
// a reversal that NEGATES the recognition on the TigerBeetle ledger (DR Revenue/CR AR, DR VAT/CR AR, DR INV/CR
// COGS), flips the invoice to 'void' with a reason marker, and records an immutable `invoice_reversal` fact. The
// reversal is a CURRENT-period event (UTC instant; the period is a re-projection), so it never back-posts into a
// locked month. Idempotent: a deterministic reversal id + UNIQUE(order_invoice_id) make a re-run a no-op. The
// emitted `invoice.voided` event fans out to the document (credit note) and accounting (Xero) consumers.
final class InvoiceReversalService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val zero    = new UUID(0L, 0L)
  private val kinds   = Set("mistake", "cancellation", "refund", "correction")
  private val journal = new Journal[F](xa, ledger)

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt
  // The reversal id IS the cycle correlation id (and the invoice.voided event id) — one deterministic thread.
  private def reversalId(orderInvoiceId: UUID): UUID = CollectionCycle.correlationId(orderInvoiceId)

  def reverse(
      orderInvoiceId: UUID,
      kind: String,
      reason: String,
      actor: String,
      causedBy: Option[UUID] = None
  ): F[Either[String, InvoiceReversalResult]] = {
    val rid = reversalId(orderInvoiceId)
    if (!kinds.contains(kind)) s"invalid void kind '$kind'".asLeft[InvoiceReversalResult].pure[F]
    else if (reason.trim.isEmpty) "a void reason is required".asLeft[InvoiceReversalResult].pure[F]
    else
      existing(rid).transact(xa).flatMap {
        case Some(r) => r.asRight[String].pure[F] // idempotent: already reversed
        case None =>
          head(orderInvoiceId).transact(xa).flatMap {
            case None                          => "unknown invoice".asLeft[InvoiceReversalResult].pure[F]
            case Some(h) if h.status == "void" => "invoice already void".asLeft[InvoiceReversalResult].pure[F]
            case Some(h) =>
              Currency.fromCode(h.currency) match {
                case None => s"unknown currency ${h.currency}".asLeft[InvoiceReversalResult].pure[F]
                case Some(ccy) =>
                  val entity     = h.entity.getOrElse(zero)
                  val ledgerId   = Ledgers.forCurrency(ccy)
                  val arAcc      = TbIds.accountId(s"AR:${h.billTo}")
                  val revAcc     = TbIds.accountId(s"REVENUE:$entity")
                  val vatAcc     = TbIds.accountId(s"VAT:$entity:${h.vatJurisdiction}")
                  val cogsAcc    = TbIds.accountId(s"COGS:$entity")
                  val invAcc     = TbIds.accountId(s"INV:$entity")
                  val carExpAcc  = TbIds.accountId(s"CARRIAGE_EXPENSE:$entity")
                  val carAccrAcc = TbIds.accountId(s"CARRIAGE_ACCRUAL:$entity")
                  val accounts = List(
                    LedgerAccount(arAcc, ledgerId, LedgerAccountCode.Ar),
                    LedgerAccount(revAcc, ledgerId, LedgerAccountCode.Revenue),
                    LedgerAccount(vatAcc, ledgerId, LedgerAccountCode.Vat),
                    LedgerAccount(cogsAcc, ledgerId, LedgerAccountCode.CosClearing),
                    LedgerAccount(invAcc, ledgerId, LedgerAccountCode.Inv),
                    LedgerAccount(carExpAcc, ledgerId, LedgerAccountCode.CarriageExpense),
                    LedgerAccount(carAccrAcc, ledgerId, LedgerAccountCode.CarriageAccrual)
                  )
                  val e = Some(entity)
                  // Negate recognition: swap debit/credit of each original leg (incl. recalled carriage — the full set).
                  val rev  = JournalAccount(s"REVENUE:$entity", LedgerAccountCode.Revenue, e)
                  val ar   = JournalAccount(s"AR:${h.billTo}", LedgerAccountCode.Ar, e)
                  val vat  = JournalAccount(s"VAT:$entity:${h.vatJurisdiction}", LedgerAccountCode.Vat, e)
                  val inv  = JournalAccount(s"INV:$entity", LedgerAccountCode.Inv, e)
                  val cogs = JournalAccount(s"COGS:$entity", LedgerAccountCode.CosClearing, e)
                  val cAcc = JournalAccount(s"CARRIAGE_ACCRUAL:$entity", LedgerAccountCode.CarriageAccrual, e)
                  val cExp = JournalAccount(s"CARRIAGE_EXPENSE:$entity", LedgerAccountCode.CarriageExpense, e)
                  // Under flash title (doc 28 §2.5) the ORIGINAL leg 2 posted at LANDED (the physical
                  // relief) and the markup rode the uplift pair — the reversal must mirror that split, or
                  // inventory mis-states by the markup and the principal keeps margin on a cancelled sale.
                  val physicalCogs = h.flashLanded.getOrElse(h.cogs)
                  val flashLegs = (h.flashUplift, h.flashProcurement).tupled.toList.flatMap {
                    case (uplift, p) =>
                      val pe     = Some(p)
                      val icAp   = JournalAccount(s"IC_AP:$entity:$p", LedgerAccountCode.Intercompany, e)
                      val icAr   = JournalAccount(s"IC_AR:$p:$entity", LedgerAccountCode.Intercompany, pe)
                      val margin = JournalAccount(s"IC_MARGIN:$p", LedgerAccountCode.IcMargin, pe)
                      val amt    = minor(uplift.abs)
                      val opPair = if (uplift >= 0) (icAp, cogs) else (cogs, icAp)
                      val prPair = if (uplift >= 0) (margin, icAr) else (icAr, margin)
                      List(
                        Posting(rid, 4, opPair._1, opPair._2, ccy, amt, transferCode = LedgerTransferCode.Reversal),
                        Posting(rid, 5, prPair._1, prPair._2, ccy, amt, transferCode = LedgerTransferCode.Reversal)
                      )
                  }
                  val flashAccounts = h.flashProcurement.toList.flatMap { p =>
                    List(
                      LedgerAccount(TbIds.accountId(s"IC_AP:$entity:$p"), ledgerId, LedgerAccountCode.Intercompany),
                      LedgerAccount(TbIds.accountId(s"IC_AR:$p:$entity"), ledgerId, LedgerAccountCode.Intercompany),
                      LedgerAccount(TbIds.accountId(s"IC_MARGIN:$p"), ledgerId, LedgerAccountCode.IcMargin)
                    )
                  }
                  val postings = List(
                    Posting(rid, 0, rev, ar, ccy, minor(h.revenueExVat), transferCode = LedgerTransferCode.Reversal),
                    Posting(rid, 1, vat, ar, ccy, minor(h.vat), transferCode = LedgerTransferCode.Reversal),
                    Posting(rid, 2, inv, cogs, ccy, minor(physicalCogs), transferCode = LedgerTransferCode.Reversal),
                    Posting(rid, 3, cAcc, cExp, ccy, minor(h.shipping), transferCode = LedgerTransferCode.Reversal)
                  ) ++ flashLegs
                  ledger.createAccounts(accounts ++ flashAccounts) *>
                    journal.post(Instant.now(), postings) *>
                    record(rid, orderInvoiceId, h, kind, reason, actor, causedBy).transact(xa)
              }
          }
      }
  }

  // ----- reads -----

  private def existing(rid: UUID): ConnectionIO[Option[InvoiceReversalResult]] =
    sql"""SELECT r.id, r.invoice_no, r.kind,
                 (r.reversed_revenue_ex_vat + r.reversed_vat), COALESCE(i.status, 'void')
          FROM invoice_reversal r LEFT JOIN order_invoice i ON i.id = r.order_invoice_id WHERE r.id = $rid"""
      .query[(UUID, String, String, BigDecimal, String)]
      .option
      .map(_.map { case (id, no, k, tot, st) => InvoiceReversalResult(id, no, k, tot, st) })

  // The recognition amounts (joined by invoice_no) are what we reverse; fall back to the invoice totals if the
  // invoice was never recognised (no dispatch), so a same-day mistake still reverses cleanly.
  private def head(orderInvoiceId: UUID): ConnectionIO[Option[ReversalHead]] =
    sql"""SELECT i.order_id, o.entity_id, o.bill_to_party_id, o.txn_currency, i.status, i.invoice_no,
                 COALESCE(rr.revenue_ex_vat, i.total_ex_vat), COALESCE(rr.vat, i.vat_total),
                 COALESCE(rr.vat_jurisdiction, 'GB'), COALESCE(rr.cogs, 0),
                 COALESCE(rr.shipping_cost, 0), rr.dispatch_id,
                 m.landed_total, m.uplift_total, m.procurement_entity_id
          FROM order_invoice i
            JOIN "order" o ON o.id = i.order_id
            LEFT JOIN revenue_recognition rr ON rr.invoice_no = i.invoice_no
            LEFT JOIN ic_match m ON m.dispatch_id = rr.dispatch_id AND m.reversed_at IS NULL
          WHERE i.id = $orderInvoiceId"""
      .query[ReversalHead]
      .option

  // ----- write: the immutable reversal fact + invoice marker + the void event -----

  private def record(
      rid: UUID,
      orderInvoiceId: UUID,
      h: ReversalHead,
      kind: String,
      reason: String,
      actor: String,
      causedBy: Option[UUID]
  ): ConnectionIO[Either[String, InvoiceReversalResult]] =
    for {
      // cancellations carry the genealogy (doc 28 §2.5): the match row is stamped with the reversal thread
      _ <-
        h.dispatchId
          .filter(_ => h.flashProcurement.isDefined)
          .traverse_(d =>
            com.hypervolt.conduit.intercompany.FlashTitle
              .stampReversal(d, rid, TbIds.transferId(rid, 4), TbIds.transferId(rid, 5))
          )
      _ <- sql"""INSERT INTO invoice_reversal
                (id, order_invoice_id, order_id, dispatch_id, invoice_no, kind, reason, currency,
                 reversed_revenue_ex_vat, reversed_vat, reversed_cogs,
                 rev_ar_transfer_id, rev_vat_transfer_id, rev_cogs_transfer_id, created_by)
              VALUES ($rid, $orderInvoiceId, ${h.orderId}, ${h.dispatchId}, ${h.invoiceNo}, $kind, $reason, ${h.currency},
                 ${h.revenueExVat}, ${h.vat}, ${h.cogs},
                 ${TbIds.transferId(rid, 0).toString}::numeric, ${TbIds.transferId(rid, 1).toString}::numeric,
                 ${TbIds.transferId(rid, 2).toString}::numeric, $actor)""".update.run
      _ <- sql"""UPDATE order_invoice SET status = 'void', voided_at = now(), void_reason = $reason, void_kind = $kind
              WHERE id = $orderInvoiceId""".update.run
      _ <- OutboxRepo.append(event(rid, orderInvoiceId, h, kind, reason, causedBy, actor))
    } yield InvoiceReversalResult(rid, h.invoiceNo, kind, h.revenueExVat + h.vat, "void").asRight[String]

  // correlation = rid (the cycle thread, which is also this event's id); causation = the void request that triggered it.
  private def event(
      rid: UUID,
      orderInvoiceId: UUID,
      h: ReversalHead,
      kind: String,
      reason: String,
      causedBy: Option[UUID],
      actor: String
  ): OutboxEvent =
    OutboxEvent(
      rid,
      "invoice.voided",
      1,
      "order",
      h.orderId,
      h.orderId.toString,
      None,
      Some(rid),
      causedBy,
      Json.obj(
        "reversal_id"      -> rid.toString.asJson,
        "order_invoice_id" -> orderInvoiceId.toString.asJson,
        "invoice_no"       -> h.invoiceNo.asJson,
        "kind"             -> kind.asJson,
        "reason"           -> reason.asJson,
        "currency"         -> h.currency.asJson,
        "reversed_revenue" -> h.revenueExVat.asJson,
        "reversed_vat"     -> h.vat.asJson,
        "reversed_cogs"    -> h.cogs.asJson
      ),
      Instant.now(),
      s"user:$actor"
    )
}
