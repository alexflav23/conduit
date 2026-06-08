package com.hypervolt.conduit.revenue

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
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
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

private final case class DispatchHead(
    orderId: UUID,
    entityId: Option[UUID],
    billTo: UUID,
    currency: String,
    jurisdiction: String
)
private final case class RecogCtx(
    head: DispatchHead,
    rev: BigDecimal,
    cogs: BigDecimal,
    shipping: BigDecimal,
    qty: Int,
    invoiceId: Option[UUID],
    asOf: LocalDate
)

// ASC 606 revenue recognition on dispatch (doc 04 §Ledger, doc 13). On delivery, control transfers and revenue is
// recognised against the TigerBeetle immutable ledger — the recognised number is provable, not asserted. VAT is now
// determined by the tax engine at the recognition point (the authoritative `context=invoice` quote), tied to the
// invoice, and attributed to the place-of-supply jurisdiction (doc 16 §1.3). COGS relieves at the dispatched units'
// SPECIFIC batch landed cost. Deterministic transfer ids + UNIQUE(dispatch_id) make redelivery a no-op.
final class RevenueRecognitionService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val zero = new UUID(0L, 0L)
  private val tax  = new TaxDeterminationService[F](xa, Map(RateTableProvider.name -> RateTableProvider))

  def ar(party: UUID): BigInt            = TbIds.accountId(s"AR:$party")
  def revenue(entity: UUID): BigInt      = TbIds.accountId(s"REVENUE:$entity")
  def vatAcc(entity: UUID): BigInt       = TbIds.accountId(s"VAT:$entity")
  def cogsAcc(entity: UUID): BigInt      = TbIds.accountId(s"COGS:$entity")
  def inv(entity: UUID): BigInt          = TbIds.accountId(s"INV:$entity")
  def carriageExp(entity: UUID): BigInt  = TbIds.accountId(s"CARRIAGE_EXPENSE:$entity")
  def carriageAccr(entity: UUID): BigInt = TbIds.accountId(s"CARRIAGE_ACCRUAL:$entity")

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
    val accounts = List(
      LedgerAccount(ar(ctx.head.billTo), ledgerId, LedgerAccountCode.Ar),
      LedgerAccount(revenue(entity), ledgerId, LedgerAccountCode.Revenue),
      LedgerAccount(vatAcc(entity), ledgerId, LedgerAccountCode.Vat),
      LedgerAccount(cogsAcc(entity), ledgerId, LedgerAccountCode.CosClearing),
      LedgerAccount(inv(entity), ledgerId, LedgerAccountCode.Inv),
      LedgerAccount(carriageExp(entity), ledgerId, LedgerAccountCode.CarriageExpense),
      LedgerAccount(carriageAccr(entity), ledgerId, LedgerAccountCode.CarriageAccrual)
    )
    // leg 3 = outbound carriage (DR expense / CR accrual). Recorded like the others so the void path reverses the
    // FULL set — adding this cost category needed no change to the reversal logic (per-event reversal, doc 04 §Ledger).
    val transfers = List(
      LedgerTransfer(
        TbIds.transferId(dispatchId, 0),
        ar(ctx.head.billTo),
        revenue(entity),
        minor(ctx.rev),
        ledgerId,
        LedgerTransferCode.Generic
      ),
      LedgerTransfer(
        TbIds.transferId(dispatchId, 1),
        ar(ctx.head.billTo),
        vatAcc(entity),
        minor(vatAmt),
        ledgerId,
        LedgerTransferCode.Generic
      ),
      LedgerTransfer(
        TbIds.transferId(dispatchId, 2),
        cogsAcc(entity),
        inv(entity),
        minor(ctx.cogs),
        ledgerId,
        LedgerTransferCode.Generic
      ),
      LedgerTransfer(
        TbIds.transferId(dispatchId, 3),
        carriageExp(entity),
        carriageAccr(entity),
        minor(ctx.shipping),
        ledgerId,
        LedgerTransferCode.Generic
      )
    ).filter(_.amount > 0)
    ledger.createAccounts(accounts) *> ledger
      .postTransfers(transfers) *> record(dispatchId, ctx, vatAmt).transact(xa).void
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
          asOf(dispatchId)
        ).tupled
          .map {
            case (None, _, _, _, _, _, _)             => "unknown dispatch".asLeft[Option[RecogCtx]]
            case (Some(h), rev, c, ship, qty, inv, d) => Some(RecogCtx(h, rev, c, ship, qty, inv, d)).asRight[String]
          }
    }

  private def shippingCost(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"SELECT COALESCE(shipping_cost, 0) FROM dispatch WHERE id = $dispatchId".query[BigDecimal].unique

  private def alreadyRecognised(dispatchId: UUID): ConnectionIO[Boolean] =
    sql"SELECT count(*) FROM revenue_recognition WHERE dispatch_id = $dispatchId".query[Int].unique.map(_ > 0)

  private def head(dispatchId: UUID): ConnectionIO[Option[DispatchHead]] =
    sql"""SELECT o.id, o.entity_id, o.bill_to_party_id, o.txn_currency, COALESCE(e.jurisdiction, 'GB')
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
    sql"""INSERT INTO revenue_recognition
            (dispatch_id, order_id, invoice_no, entity_id, currency, revenue_ex_vat, vat, cogs, gross_margin,
             vat_jurisdiction, tax_quote_id, shipping_cost, ar_transfer_id, vat_transfer_id, cogs_transfer_id,
             carriage_transfer_id)
          VALUES ($dispatchId, ${h.orderId},
             (SELECT invoice_no FROM order_invoice WHERE order_id = ${h.orderId} ORDER BY issued_at DESC LIMIT 1),
             ${h.entityId}, ${h.currency}, ${ctx.rev}, $vatAmt, ${ctx.cogs}, ${ctx.rev - ctx.cogs}, ${h.jurisdiction},
             (SELECT id FROM tax_quote WHERE order_invoice_id = ${ctx.invoiceId} AND context = 'invoice'
                ORDER BY determined_at DESC LIMIT 1),
             ${ctx.shipping},
             ${TbIds.transferId(dispatchId, 0).bigInteger.toString}::numeric,
             ${TbIds.transferId(dispatchId, 1).bigInteger.toString}::numeric,
             ${TbIds.transferId(dispatchId, 2).bigInteger.toString}::numeric,
             ${TbIds.transferId(dispatchId, 3).bigInteger.toString}::numeric)
          ON CONFLICT (dispatch_id) DO NOTHING""".update.run.flatMap { n =>
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
