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
import scala.math.BigDecimal.RoundingMode

final case class ReconResult(id: UUID, expected: BigDecimal, actual: BigDecimal, variance: BigDecimal, status: String)

// Automated reconciliations (doc 14 §5–6) — Option B: the AR and trial-balance ties read the gl_entry MIRROR (pure
// SQL, synchronous, no TigerBeetle on the request path); only the gl_vs_tb MIRROR check itself reads TigerBeetle and
// so runs where TB is available (the consumer). Each records expected/actual/variance/status; an unsigned exception
// blocks the period lock (PeriodCloseService).
final class ReconciliationService[F[_]: Async](xa: Transactor[F]) {

  private def money(minor: BigDecimal): BigDecimal = (minor / 100).setScale(2, RoundingMode.HALF_UP)

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

  def signOff(reconId: UUID, actor: UUID): F[Int] =
    sql"UPDATE reconciliation SET signed_off_by=$actor, signed_off_at=now(), updated_at=now() WHERE id=$reconId".update.run
      .transact(xa)

  private def openInvoiceTotal(entity: UUID, currency: String): doobie.ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(i.total_inc_vat),0) FROM order_invoice i JOIN "order" o ON o.id=i.order_id
          WHERE o.entity_id=$entity AND o.txn_currency=$currency AND i.status='open'""".query[BigDecimal].unique

  private def record(
      reconType: String,
      periodId: UUID,
      expected: BigDecimal,
      actual: BigDecimal,
      currency: String
  ): F[ReconResult] = {
    val variance = (actual - expected).setScale(2, RoundingMode.HALF_UP)
    val status   = if (variance.signum == 0) "matched" else "exception"
    sql"""INSERT INTO reconciliation (type, period_id, expected, actual, currency, variance, status)
          VALUES ($reconType, $periodId, $expected, $actual, $currency, $variance, $status) RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)
      .map(id => ReconResult(id, expected, actual, variance, status))
  }
}
