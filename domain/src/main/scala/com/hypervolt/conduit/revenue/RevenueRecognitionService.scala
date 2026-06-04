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
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

private final case class DispatchHead(orderId: UUID, entityId: Option[UUID], billTo: UUID, currency: String)

// ASC 606 revenue recognition on dispatch (doc 04 §Ledger, doc 13). When goods are delivered, control transfers
// and revenue is recognised against the TigerBeetle immutable ledger — so the recognised number is provable, not
// asserted. Cost of sales is relieved at the dispatched units' SPECIFIC batch landed cost (no weighted-average,
// doc 02 §G), so gross margin is exact. Deterministic transfer ids from the dispatch make a re-run a no-op.
final class RevenueRecognitionService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val zero = new UUID(0L, 0L)

  def ar(party: UUID): BigInt       = TbIds.accountId(s"AR:$party")
  def revenue(entity: UUID): BigInt = TbIds.accountId(s"REVENUE:$entity")
  def vatAcc(entity: UUID): BigInt  = TbIds.accountId(s"VAT:$entity")
  def cogsAcc(entity: UUID): BigInt = TbIds.accountId(s"COGS:$entity")
  def inv(entity: UUID): BigInt     = TbIds.accountId(s"INV:$entity")

  private def minor(amount: BigDecimal): BigInt = (amount.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // Recognise revenue + COGS for a delivered dispatch. Idempotent: deterministic transfer ids (TB returns
  // `exists`) and a UNIQUE(dispatch_id) recognition row both make a re-run a no-op.
  def recognize(dispatchId: UUID): F[Either[String, Unit]] =
    (head(dispatchId), revenueExVat(dispatchId), vat(dispatchId), cogs(dispatchId)).tupled.transact(xa).flatMap {
      case (None, _, _, _) => "unknown dispatch".asLeft[Unit].pure[F]
      case (Some(h), rev, vatAmt, cogsAmt) =>
        Currency.fromCode(h.currency) match {
          case None => s"unknown currency ${h.currency}".asLeft[Unit].pure[F]
          case Some(ccy) =>
            val entity   = h.entityId.getOrElse(zero)
            val ledgerId = Ledgers.forCurrency(ccy)
            val accounts = List(
              LedgerAccount(ar(h.billTo), ledgerId, LedgerAccountCode.Ar),
              LedgerAccount(revenue(entity), ledgerId, LedgerAccountCode.Revenue),
              LedgerAccount(vatAcc(entity), ledgerId, LedgerAccountCode.Vat),
              LedgerAccount(cogsAcc(entity), ledgerId, LedgerAccountCode.CosClearing),
              LedgerAccount(inv(entity), ledgerId, LedgerAccountCode.Inv)
            )
            val transfers = List(
              LedgerTransfer(
                TbIds.transferId(dispatchId, 0),
                ar(h.billTo),
                revenue(entity),
                minor(rev),
                ledgerId,
                LedgerTransferCode.Generic
              ),
              LedgerTransfer(
                TbIds.transferId(dispatchId, 1),
                ar(h.billTo),
                vatAcc(entity),
                minor(vatAmt),
                ledgerId,
                LedgerTransferCode.Generic
              ),
              LedgerTransfer(
                TbIds.transferId(dispatchId, 2),
                cogsAcc(entity),
                inv(entity),
                minor(cogsAmt),
                ledgerId,
                LedgerTransferCode.Generic
              )
            ).filter(_.amount > 0)
            ledger.createAccounts(accounts) *>
              ledger.postTransfers(transfers) *>
              record(dispatchId, h, rev, vatAmt, cogsAmt).transact(xa).as(().asRight[String])
        }
    }

  // ----- amounts (from the dispatch's actual lines — partial tranches recognise only what shipped) -----

  private def head(dispatchId: UUID): ConnectionIO[Option[DispatchHead]] =
    sql"""SELECT o.id, o.entity_id, o.bill_to_party_id, o.txn_currency
          FROM dispatch d JOIN "order" o ON o.id = d.order_id WHERE d.id = $dispatchId"""
      .query[DispatchHead]
      .option

  private def revenueExVat(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(dl.qty * ol.unit_price_ex_vat * (1 - ol.discount_pct/100)), 0)
          FROM dispatch_line dl JOIN order_line ol ON ol.id = dl.order_line_id WHERE dl.dispatch_id = $dispatchId"""
      .query[BigDecimal]
      .unique

  private def vat(dispatchId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(dl.qty * (ol.vat_amount / NULLIF(ol.qty, 0))), 0)
          FROM dispatch_line dl JOIN order_line ol ON ol.id = dl.order_line_id WHERE dl.dispatch_id = $dispatchId"""
      .query[BigDecimal]
      .unique

  // COGS at specific batch landed cost: serialised units by their serial's lot; non-serialised by the variant's
  // latest lot cost × qty (doc 02 §G — never weighted-average).
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

  private def record(
      dispatchId: UUID,
      h: DispatchHead,
      rev: BigDecimal,
      vatAmt: BigDecimal,
      cogsAmt: BigDecimal
  ): ConnectionIO[Int] =
    sql"""INSERT INTO revenue_recognition
            (dispatch_id, order_id, invoice_no, entity_id, currency, revenue_ex_vat, vat, cogs, gross_margin,
             ar_transfer_id, vat_transfer_id, cogs_transfer_id)
          VALUES ($dispatchId, ${h.orderId},
             (SELECT invoice_no FROM order_invoice WHERE order_id = ${h.orderId} ORDER BY issued_at DESC LIMIT 1),
             ${h.entityId}, ${h.currency}, $rev, $vatAmt, $cogsAmt, ${rev - cogsAmt},
             ${TbIds.transferId(dispatchId, 0).bigInteger.toString}::numeric,
             ${TbIds.transferId(dispatchId, 1).bigInteger.toString}::numeric,
             ${TbIds.transferId(dispatchId, 2).bigInteger.toString}::numeric)
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
              "dispatch_id"    -> dispatchId.toString.asJson,
              "revenue_ex_vat" -> rev.toString.asJson,
              "vat"            -> vatAmt.toString.asJson,
              "cogs"           -> cogsAmt.toString.asJson,
              "gross_margin"   -> (rev - cogsAmt).toString.asJson
            ),
            Instant.now()
          )
        )
        .as(n)
    }
}
