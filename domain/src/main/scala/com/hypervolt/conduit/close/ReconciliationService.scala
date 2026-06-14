package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.gl.GlEntryRepo
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

final case class ReconResult(id: UUID, expected: BigDecimal, actual: BigDecimal, variance: BigDecimal, status: String)

// Automated reconciliations (doc 14 §5–6) — Option B: the AR and trial-balance ties read the gl_entry MIRROR (pure
// SQL, synchronous, no TigerBeetle on the request path); only the gl_vs_tb MIRROR check itself reads TigerBeetle and
// so runs where TB is available (the consumer). Each records expected/actual/variance/status; an unsigned exception
// blocks the period lock (PeriodCloseService).
final class ReconciliationService[F[_]: Async](xa: Transactor[F]) {

  private def money(minor: BigDecimal): BigDecimal = ReconMath.money(minor)

  // AR ↔ open invoices: the AR posted balance (debits − credits) must equal the sum of open invoices for the entity.
  def arVsInvoices(periodId: UUID, entity: UUID, currency: String): F[ReconResult] =
    (openInvoiceTotal(entity, currency), GlEntryRepo.roleNet(entity, LedgerAccountCode.Ar)).tupled
      .transact(xa)
      .flatMap { case (expected, arNet) => record("ar_vs_invoices", periodId, expected, money(arNet), currency) }

  // Trial balance: the whole ledger ties — Σ posted debits == Σ posted credits (double-entry, by construction).
  def tbVsGl(periodId: UUID, currency: String): F[ReconResult] =
    GlEntryRepo.globalTotals.transact(xa).flatMap {
      case (dr, cr) => record("tb_vs_gl", periodId, money(dr), money(cr), currency)
    }

  // gl_entry ↔ TigerBeetle: every mirrored account's posted balance must match TB to the integer minor unit. The
  // `actual` is the count of accounts that DON'T match (0 = the mirror is faithful). Reads TB, so it runs in the
  // consumer; CTRL-GL-MIRROR then counts any unsigned gl_vs_tb exception.
  def glVsTb(periodId: UUID, currency: String, ledger: TigerBeetleLedger[F]): F[ReconResult] =
    GlEntryRepo.allAccounts.transact(xa).flatMap { keys =>
      keys
        .traverse(k =>
          GlEntryRepo.postedBalance(k).transact(xa).flatMap {
            case (gd, gc) =>
              ledger
                .balance(TbIds.accountId(k))
                .map(b => if (gd == BigDecimal(b.debitsPosted) && gc == BigDecimal(b.creditsPosted)) 0 else 1)
          }
        )
        .map(_.sum)
        .flatMap(mismatches => record("gl_vs_tb", periodId, BigDecimal(0), BigDecimal(mismatches), currency))
    }

  // Inventory ↔ counts: the INV ledger value must equal the on-hand stock valued at specific batch landed cost
  // (doc 14 §5.2). expected = ledger INV; actual = physical valuation.
  def inventoryVsCounts(periodId: UUID, entity: UUID, currency: String): F[ReconResult] =
    (GlEntryRepo.roleNet(entity, LedgerAccountCode.Inv), physicalInventory(entity)).tupled
      .transact(xa)
      .flatMap { case (invNet, physical) => record("inventory_vs_count", periodId, money(invNet), physical, currency) }

  // GL ↔ Xero: every non-void invoice in the GL must have reached Xero (a `xero_invoice_id`); the unsynced value is
  // the variance, so a failed/dropped accounting dispatch surfaces as an exception rather than silent drift.
  def glVsXero(periodId: UUID, entity: UUID, currency: String): F[ReconResult] =
    invoicedVsXero(entity).transact(xa).flatMap {
      case (invoiced, synced) => record("gl_vs_xero", periodId, invoiced, synced, currency)
    }

  def signOff(reconId: UUID, actor: UUID): F[Int] =
    sql"UPDATE reconciliation SET signed_off_by=$actor, signed_off_at=now(), updated_at=now() WHERE id=$reconId".update.run
      .transact(xa)

  private def openInvoiceTotal(entity: UUID, currency: String): doobie.ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(i.total_inc_vat),0) FROM order_invoice i JOIN "order" o ON o.id=i.order_id
          WHERE o.entity_id=$entity AND o.txn_currency=$currency AND i.status='open'""".query[BigDecimal].unique

  // On-hand units valued at the variant's latest lot landed cost (specific-identification basis, doc 02 §G).
  private def physicalInventory(entity: UUID): doobie.ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(si.qty_on_hand * COALESCE(
            (SELECT lb.landed_unit_cost FROM lot_batch lb WHERE lb.product_variant_id = si.product_variant_id
             ORDER BY lb.received_date DESC NULLS LAST LIMIT 1), 0)), 0)
          FROM stock_item si WHERE si.entity_id = $entity""".query[BigDecimal].unique

  // (Σ non-void invoiced, Σ of those that reached Xero) — the gap is the unsynced value.
  private def invoicedVsXero(entity: UUID): doobie.ConnectionIO[(BigDecimal, BigDecimal)] =
    sql"""SELECT COALESCE(SUM(i.total_inc_vat) FILTER (WHERE i.status <> 'void'), 0),
                 COALESCE(SUM(i.total_inc_vat) FILTER (WHERE i.status <> 'void' AND i.xero_invoice_id IS NOT NULL), 0)
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id WHERE o.entity_id = $entity"""
      .query[(BigDecimal, BigDecimal)]
      .unique

  private def record(
      reconType: String,
      periodId: UUID,
      expected: BigDecimal,
      actual: BigDecimal,
      currency: String
  ): F[ReconResult] = {
    val ReconMath.Eval(variance, status) = ReconMath.evaluate(expected, actual)
    sql"""INSERT INTO reconciliation (type, period_id, expected, actual, currency, variance, status)
          VALUES ($reconType, $periodId, $expected, $actual, $currency, $variance, $status) RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)
      .map(id => ReconResult(id, expected, actual, variance, status))
  }
}
