package com.hypervolt.conduit.revenue

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Journal
import com.hypervolt.conduit.ledger.JournalAccount
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.Posting
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.tax.RateTableProvider
import com.hypervolt.conduit.tax.TaxDeterminationService
import com.hypervolt.conduit.tax.TaxQuoteLineReq
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxShipPoint
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

private final case class DispatchHead(
    orderId: UUID,
    entityId: Option[UUID],
    billTo: UUID,
    currency: String,
    jurisdiction: String,
    procurementParent: Option[UUID], // entity.procurement_parent_id — present = the flash-title hop applies
    marketId: Option[UUID]
)
private final case class RecogCtx(
    head: DispatchHead,
    rev: BigDecimal,
    cogs: BigDecimal,
    shipping: BigDecimal,
    qty: Int,
    invoiceId: Option[UUID],
    asOf: LocalDate,
    vatRate: BigDecimal,                                                  // the order's implied VAT rate — the fallback for an entity-less order (no engine)
    flash: Option[com.hypervolt.conduit.intercompany.FlashTitle.FlashCtx] // doc 28 §2.2 — the priced internal hop
)

// ASC 606 revenue recognition on dispatch (doc 04 §Ledger, doc 13). On delivery, control transfers and revenue is
// recognised against the TigerBeetle immutable ledger — the recognised number is provable, not asserted. VAT is now
// determined by the tax engine at the recognition point (the authoritative `context=invoice` quote), tied to the
// invoice, and attributed to the place-of-supply jurisdiction (doc 16 §1.3). COGS relieves at the dispatched units'
// SPECIFIC batch landed cost. Deterministic transfer ids + UNIQUE(dispatch_id) make redelivery a no-op.
final class RevenueRecognitionService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val zero    = new UUID(0L, 0L)
  private val tax     = new TaxDeterminationService[F](xa, Map(RateTableProvider.name -> RateTableProvider))
  private val journal = new Journal[F](xa, ledger)

  def ar(party: UUID): BigInt       = TbIds.accountId(s"AR:$party")
  def revenue(entity: UUID): BigInt = TbIds.accountId(s"REVENUE:$entity")
  // VAT control is per (entity, place-of-supply jurisdiction) — the exposure accrues where the VAT is due, on the
  // immutable ledger. Year-1 (one jurisdiction per entity) this is simply VAT:<entity>:GB.
  def vatAcc(entity: UUID, jurisdiction: String): BigInt = TbIds.accountId(s"VAT:$entity:$jurisdiction")
  def cogsAcc(entity: UUID): BigInt                      = TbIds.accountId(s"COGS:$entity")
  def inv(entity: UUID): BigInt                          = TbIds.accountId(s"INV:$entity")
  def carriageExp(entity: UUID): BigInt                  = TbIds.accountId(s"CARRIAGE_EXPENSE:$entity")
  def carriageAccr(entity: UUID): BigInt                 = TbIds.accountId(s"CARRIAGE_ACCRUAL:$entity")

  private def minor(amount: BigDecimal): BigInt = (amount.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // Recognise revenue + COGS for a delivered dispatch. Idempotent: the existing-recognition guard short-circuits a
  // redelivery (so the tax engine is not re-quoted), and the deterministic TB ids make any re-post a no-op.
  def recognize(dispatchId: UUID): F[Either[String, Unit]] =
    preflight(dispatchId).transact(xa).flatMap {
      case Left(msg)   => msg.asLeft[Unit].pure[F]
      case Right(None) => ().asRight[String].pure[F] // already recognised — idempotent no-op
      case Right(Some(ctx)) =>
        Currency.fromCode(ctx.head.currency) match {
          case None => s"unknown currency ${ctx.head.currency}".asLeft[Unit].pure[F]
          // The tax engine (and its immutable tax_quote) needs a real selling entity. An entity-less order
          // (simulation/migration fixtures) recognises with no engine VAT rather than FK-crashing on the zero UUID.
          case Some(_) if ctx.head.entityId.isEmpty =>
            post(dispatchId, ctx, (ctx.rev * ctx.vatRate).setScale(2, RoundingMode.HALF_UP)).as(().asRight[String])
          case Some(_) =>
            tax.determine(taxRequest(ctx)).flatMap {
              case Left(e)     => s"tax determination failed: $e".asLeft[Unit].pure[F]
              case Right(resp) => post(dispatchId, ctx, resp.taxTotal).as(().asRight[String])
            }
        }
    }

  private def post(dispatchId: UUID, ctx: RecogCtx, vatAmt: BigDecimal): F[Unit] = {
    val entity   = ctx.head.entityId.getOrElse(zero)
    val ledgerId = Ledgers.forCurrency(Currency.fromCode(ctx.head.currency).get)
    val flashAccounts = ctx.flash.toList.flatMap { f =>
      val p = f.procurementEntity
      List(
        LedgerAccount(TbIds.accountId(s"IC_AP:$entity:$p"), ledgerId, LedgerAccountCode.Intercompany),
        LedgerAccount(TbIds.accountId(s"IC_AR:$p:$entity"), ledgerId, LedgerAccountCode.Intercompany),
        LedgerAccount(TbIds.accountId(s"IC_MARGIN:$p"), ledgerId, LedgerAccountCode.IcMargin)
      )
    }
    val accounts = List(
      LedgerAccount(ar(ctx.head.billTo), ledgerId, LedgerAccountCode.Ar),
      LedgerAccount(revenue(entity), ledgerId, LedgerAccountCode.Revenue),
      LedgerAccount(vatAcc(entity, ctx.head.jurisdiction), ledgerId, LedgerAccountCode.Vat),
      LedgerAccount(cogsAcc(entity), ledgerId, LedgerAccountCode.CosClearing),
      LedgerAccount(inv(entity), ledgerId, LedgerAccountCode.Inv),
      LedgerAccount(carriageExp(entity), ledgerId, LedgerAccountCode.CarriageExpense),
      LedgerAccount(carriageAccr(entity), ledgerId, LedgerAccountCode.CarriageAccrual)
    ) ++ flashAccounts
    val ccy        = Currency.fromCode(ctx.head.currency).get
    val occurredAt = ctx.asOf.atStartOfDay(ZoneOffset.UTC).toInstant
    val e          = Some(entity)
    // leg 3 = outbound carriage (DR expense / CR accrual). Recorded like the others so the void path reverses the
    // FULL set — adding this cost category needed no change to the reversal logic (per-event reversal, doc 04 §Ledger).
    val postings = List(
      Posting(
        dispatchId,
        0,
        JournalAccount(s"AR:${ctx.head.billTo}", LedgerAccountCode.Ar, e),
        JournalAccount(s"REVENUE:$entity", LedgerAccountCode.Revenue, e),
        ccy,
        minor(ctx.rev)
      ),
      Posting(
        dispatchId,
        1,
        JournalAccount(s"AR:${ctx.head.billTo}", LedgerAccountCode.Ar, e),
        JournalAccount(s"VAT:$entity:${ctx.head.jurisdiction}", LedgerAccountCode.Vat, e),
        ccy,
        minor(vatAmt)
      ),
      Posting(
        dispatchId,
        2,
        JournalAccount(s"COGS:$entity", LedgerAccountCode.CosClearing, e),
        JournalAccount(s"INV:$entity", LedgerAccountCode.Inv, e),
        ccy,
        minor(ctx.cogs)
      ),
      Posting(
        dispatchId,
        3,
        JournalAccount(s"CARRIAGE_EXPENSE:$entity", LedgerAccountCode.CarriageExpense, e),
        JournalAccount(s"CARRIAGE_ACCRUAL:$entity", LedgerAccountCode.CarriageAccrual, e),
        ccy,
        minor(ctx.shipping)
      )
    )
    // legs 4+5 — the flash-title uplift pair (doc 28 §2.2): operating COGS tops up to the TRANSFER price
    // against an IC payable; the principal books exactly the markup. A below-cost catalogue (negative
    // uplift) posts the same pair flipped. Eliminates at group; leg ids deterministic from (dispatch, leg).
    val flashPostings = ctx.flash.toList.flatMap { f =>
      val p      = f.procurementEntity
      val pe     = Some(p)
      val uplift = f.transferTotal - ctx.cogs
      val amt    = minor(uplift.abs)
      val opPair =
        if (uplift >= 0)
          (
            JournalAccount(s"COGS:$entity", LedgerAccountCode.CosClearing, e),
            JournalAccount(s"IC_AP:$entity:$p", LedgerAccountCode.Intercompany, e)
          )
        else
          (
            JournalAccount(s"IC_AP:$entity:$p", LedgerAccountCode.Intercompany, e),
            JournalAccount(s"COGS:$entity", LedgerAccountCode.CosClearing, e)
          )
      val prPair =
        if (uplift >= 0)
          (
            JournalAccount(s"IC_AR:$p:$entity", LedgerAccountCode.Intercompany, pe),
            JournalAccount(s"IC_MARGIN:$p", LedgerAccountCode.IcMargin, pe)
          )
        else
          (
            JournalAccount(s"IC_MARGIN:$p", LedgerAccountCode.IcMargin, pe),
            JournalAccount(s"IC_AR:$p:$entity", LedgerAccountCode.Intercompany, pe)
          )
      List(
        Posting(dispatchId, 4, opPair._1, opPair._2, ccy, amt),
        Posting(dispatchId, 5, prPair._1, prPair._2, ccy, amt)
      )
    }
    ledger.createAccounts(accounts) *> journal.post(occurredAt, postings ++ flashPostings) *>
      record(dispatchId, ctx, vatAmt).transact(xa).void
  }

  // The authoritative invoice quote (doc 16 §6): the place of supply is the selling entity's jurisdiction for a
  // domestic sale (year-1 UK); ship-to drives cross-border once markets open. One ex-tax line = the dispatched net.
  private def taxRequest(ctx: RecogCtx): TaxQuoteRequest =
    TaxQuoteRequest(
      context = "invoice",
      entityId = ctx.head.entityId.getOrElse(zero),
      shipFrom = TaxShipPoint(ctx.head.jurisdiction, None, None),
      shipTo = TaxShipPoint(ctx.head.jurisdiction, None, None),
      partyTaxStatus = "business",
      buyerTaxId = None,
      incoterm = None,
      currency = ctx.head.currency,
      asOf = ctx.asOf,
      lines = List(TaxQuoteLineReq("rec", None, Some("goods_standard"), None, ctx.qty, ctx.rev)),
      orderId = Some(ctx.head.orderId),
      orderInvoiceId = ctx.invoiceId
    )

  // ----- preflight (one read): idempotency guard + the dispatch facts -----

  private def preflight(dispatchId: UUID): ConnectionIO[Either[String, Option[RecogCtx]]] =
    alreadyRecognised(dispatchId).flatMap {
      case true => Option.empty[RecogCtx].asRight[String].pure[ConnectionIO]
      case false =>
        (
          head(dispatchId),
          revenueExVat(dispatchId),
          cogs(dispatchId),
          shippingCost(dispatchId),
          dispatchedQty(dispatchId),
          invoiceId(dispatchId),
          asOf(dispatchId),
          orderVatRate(dispatchId)
        ).tupled
          .flatMap {
            case (None, _, _, _, _, _, _, _) =>
              "unknown dispatch".asLeft[Option[RecogCtx]].pure[ConnectionIO]
            case (Some(h), rev, c, ship, qty, inv, d, vr) =>
              flashHop(dispatchId, h, c, rev, qty, d).map(
                _.map(f => Some(RecogCtx(h, rev, c, ship, qty, inv, d, vr, f)))
              )
          }
    }

  // The flash-title hop (doc 28 §2.2): present iff the selling entity has a procurement parent. Pricing fails
  // CLOSED — an unpriced internal sale blocks recognition (a governance error, not a landed-cost default).
  private def flashHop(
      dispatchId: UUID,
      h: DispatchHead,
      landed: BigDecimal,
      rev: BigDecimal,
      qty: Int,
      asOf: LocalDate
  ): ConnectionIO[Either[String, Option[com.hypervolt.conduit.intercompany.FlashTitle.FlashCtx]]] =
    (h.entityId, h.procurementParent, h.marketId) match {
      case (Some(op), Some(parent), Some(market)) =>
        com.hypervolt.conduit.intercompany.FlashTitle
          .resolve(dispatchId, op, parent, market, landed, rev, qty, asOf)
          .flatMap(_.flatTraverse(com.hypervolt.conduit.intercompany.FlashTitle.stampRate(_, h.currency, asOf)))
          .map(_.map(Some(_)))
      case _ => Option.empty[com.hypervolt.conduit.intercompany.FlashTitle.FlashCtx].asRight[String].pure[ConnectionIO]
    }

  private def shippingCost(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"SELECT COALESCE(shipping_cost, 0) FROM dispatch WHERE id = $dispatchId".query[BigDecimal].unique

  // The order's implied VAT rate (vat_total / subtotal_ex_vat) — used ONLY for an entity-less order, where the
  // tax engine cannot run; real orders carry an entity and get engine-determined VAT (doc 16 §1.3, VAT.8).
  private def orderVatRate(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT CASE WHEN o.subtotal_ex_vat > 0 THEN o.vat_total / o.subtotal_ex_vat ELSE 0 END
          FROM dispatch d JOIN "order" o ON o.id = d.order_id WHERE d.id = $dispatchId"""
      .query[BigDecimal]
      .option
      .map(_.getOrElse(BigDecimal(0)))

  private def alreadyRecognised(dispatchId: UUID): ConnectionIO[Boolean] =
    sql"SELECT count(*) FROM revenue_recognition WHERE dispatch_id = $dispatchId".query[Int].unique.map(_ > 0)

  private def head(dispatchId: UUID): ConnectionIO[Option[DispatchHead]] =
    sql"""SELECT o.id, o.entity_id, o.bill_to_party_id, o.txn_currency, COALESCE(e.jurisdiction, 'GB'),
                 e.procurement_parent_id, o.market_id
          FROM dispatch d JOIN "order" o ON o.id = d.order_id LEFT JOIN entity e ON e.id = o.entity_id
          WHERE d.id = $dispatchId"""
      .query[DispatchHead]
      .option

  private def dispatchedQty(dispatchId: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(SUM(qty), 0) FROM dispatch_line WHERE dispatch_id = $dispatchId".query[Int].unique

  private def invoiceId(dispatchId: UUID): ConnectionIO[Option[UUID]] =
    sql"""SELECT i.id FROM order_invoice i JOIN dispatch d ON d.order_id = i.order_id
          WHERE d.id = $dispatchId ORDER BY i.issued_at DESC NULLS LAST LIMIT 1"""
      .query[UUID]
      .option

  // The recognition (control transfer) date — delivery if known, else the dispatch date. Fixes the rates as_of so
  // a redelivery would re-quote identically (it doesn't, thanks to the idempotency guard, but the date is stable).
  private def asOf(dispatchId: UUID): ConnectionIO[LocalDate] =
    sql"SELECT COALESCE(delivered_at, date)::date FROM dispatch WHERE id = $dispatchId"
      .query[LocalDate]
      .option
      .map(_.getOrElse(LocalDate.of(2026, 1, 1)))

  private def revenueExVat(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(dl.qty * ol.unit_price_ex_vat * (1 - ol.discount_pct/100)), 0)
          FROM dispatch_line dl JOIN order_line ol ON ol.id = dl.order_line_id WHERE dl.dispatch_id = $dispatchId"""
      .query[BigDecimal]
      .unique

  // COGS at specific batch landed cost (doc 02 §G — never weighted-average).
  private def cogs(dispatchId: UUID): ConnectionIO[BigDecimal] =
    (cogsSerial(dispatchId), cogsNonSerial(dispatchId)).tupled.map { case (a, b) => a + b }

  private def cogsSerial(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(b.landed_unit_cost), 0)
          FROM serial_unit s JOIN lot_batch b ON b.id = s.lot_batch_id WHERE s.dispatch_id = $dispatchId"""
      .query[BigDecimal]
      .unique

  private def cogsNonSerial(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(dl.qty * COALESCE(
            (SELECT lb.landed_unit_cost FROM lot_batch lb WHERE lb.product_variant_id = ol.product_variant_id
             ORDER BY lb.received_date DESC NULLS LAST LIMIT 1), 0)), 0)
          FROM dispatch_line dl
            JOIN order_line ol ON ol.id = dl.order_line_id
            JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE dl.dispatch_id = $dispatchId AND pv.is_serialised = false"""
      .query[BigDecimal]
      .unique

  // ----- write: recognition row (with VAT jurisdiction + the tax_quote it was determined from) + event -----

  private def record(dispatchId: UUID, ctx: RecogCtx, vatAmt: BigDecimal): ConnectionIO[Int] = {
    val h = ctx.head
    // Under flash title (doc 28) the operating entity's COGS — the figure its P&L reports — is the TRANSFER
    // price; the landed/uplift decomposition lives ONLY in ic_match, behind the inter_entity wall.
    val opCogs = ctx.flash.fold(ctx.cogs)(_.transferTotal)
    // Claims iff posted (doc 29 A2): the Journal drops zero-amount legs, so a stamped id with no gl mirror
    // would be a false claim CTRL-LINEAGE-CLOSURE rightly rejects.
    def claim(leg: Int, amount: BigDecimal) =
      Option.when(minor(amount) > 0)(BigDecimal(TbIds.transferId(dispatchId, leg)))
    val arTid       = claim(0, ctx.rev)
    val vatTid      = claim(1, vatAmt)
    val cogsTid     = claim(2, ctx.cogs)
    val carriageTid = claim(3, ctx.shipping)
    sql"""INSERT INTO revenue_recognition
            (dispatch_id, order_id, invoice_no, entity_id, currency, revenue_ex_vat, vat, cogs, gross_margin,
             vat_jurisdiction, tax_quote_id, shipping_cost, ar_transfer_id, vat_transfer_id, cogs_transfer_id,
             carriage_transfer_id)
          VALUES ($dispatchId, ${h.orderId},
             (SELECT invoice_no FROM order_invoice WHERE order_id = ${h.orderId} ORDER BY issued_at DESC LIMIT 1),
             ${h.entityId}, ${h.currency}, ${ctx.rev}, $vatAmt, $opCogs, ${ctx.rev - opCogs}, ${h.jurisdiction},
             (SELECT id FROM tax_quote WHERE order_invoice_id = ${ctx.invoiceId} AND context = 'invoice'
                ORDER BY determined_at DESC LIMIT 1),
             ${ctx.shipping}, $arTid, $vatTid, $cogsTid, $carriageTid)
          ON CONFLICT (dispatch_id) DO NOTHING""".update.run
      .flatTap { _ =>
        ctx.flash.traverse_ { f =>
          val upliftLeg =
            (l: Int) => Option.when(minor((f.transferTotal - ctx.cogs).abs) > 0)(TbIds.transferId(dispatchId, l))
          h.entityId.traverse_(op =>
            com.hypervolt.conduit.intercompany.FlashTitle.recordMatch(
              dispatchId,
              h.orderId,
              op,
              f,
              h.currency,
              ctx.cogs,
              upliftLeg(4),
              upliftLeg(5)
            )
          )
        }
      }
      .flatMap { n =>
        OutboxRepo
          .append(
            OutboxEvent(
              UUID.randomUUID(),
              "revenue.recognized",
              1,
              "order",
              h.orderId,
              h.orderId.toString,
              None,
              None,
              None,
              Json.obj(
                "dispatch_id"      -> dispatchId.toString.asJson,
                "revenue_ex_vat"   -> ctx.rev.toString.asJson,
                "vat"              -> vatAmt.toString.asJson,
                "vat_jurisdiction" -> h.jurisdiction.asJson,
                "cogs"             -> ctx.cogs.toString.asJson,
                "gross_margin"     -> (ctx.rev - ctx.cogs).toString.asJson
              ),
              Instant.now(),
              "service:revenue-recognition"
            )
          )
          .as(n)
      }
  }
}
