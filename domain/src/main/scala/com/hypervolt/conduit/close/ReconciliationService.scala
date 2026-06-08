package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class ReconResult(id: UUID, expected: BigDecimal, actual: BigDecimal, variance: BigDecimal, status: String)

// Automated reconciliations (doc 14 §5–6). Each ties a sub-ledger / projection back to the immutable ledger and
// records expected / actual / variance / status (matched|exception). An unsigned exception blocks the period
// lock (PeriodCloseService). These are the TB↔GL, AR↔invoices etc. ties the close board surfaces.
final class ReconciliationService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private def money(minor: BigInt): BigDecimal = (BigDecimal(minor) / 100).setScale(2, RoundingMode.HALF_UP)

  // AR ↔ open invoices: the AR ledger balance must equal the sum of open invoices for the entity.
  def arVsInvoices(periodId: UUID, entity: UUID, currency: String): F[ReconResult] =
    (openInvoiceTotal(entity, currency), arParties(entity)).tupled.transact(xa).flatMap {
      case (expected, parties) =>
        parties
          .traverse(p => ledger.balance(TbIds.accountId(s"AR:$p")).map(b => b.debitsPosted - b.creditsPosted))
          .map(bals => money(bals.sum))
          .flatMap(actual => record("ar_vs_invoices", periodId, expected, actual, currency))
    }

  // TB ↔ GL: the trial balance read off the ledger must tie out (Σ debits == Σ credits).
  def tbVsGl(periodId: UUID, entity: UUID, currency: String): F[ReconResult] =
    (arParties(entity), vatJurisdictions(entity)).tupled.transact(xa).flatMap {
      case (parties, vatJurs) =>
        val keys =
          List(s"REVENUE:$entity", s"COGS:$entity", s"INV:$entity") :::
            vatJurs.map(j => s"VAT:$entity:$j") ::: parties.map(p => s"AR:$p")
        keys
          .traverse(k => ledger.balance(TbIds.accountId(k)).map(b => (b.debitsPosted, b.creditsPosted)))
          .flatMap(bs => record("tb_vs_gl", periodId, money(bs.map(_._1).sum), money(bs.map(_._2).sum), currency))
    }

  def signOff(reconId: UUID, actor: UUID): F[Int] =
    sql"UPDATE reconciliation SET signed_off_by=$actor, signed_off_at=now(), updated_at=now() WHERE id=$reconId".update.run
      .transact(xa)

  // ----- helpers -----

  private def openInvoiceTotal(entity: UUID, currency: String): doobie.ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(i.total_inc_vat),0) FROM order_invoice i JOIN "order" o ON o.id=i.order_id
          WHERE o.entity_id=$entity AND o.txn_currency=$currency AND i.status='open'""".query[BigDecimal].unique

  private def arParties(entity: UUID): doobie.ConnectionIO[List[UUID]] =
    sql"""SELECT DISTINCT o.bill_to_party_id FROM order_invoice i JOIN "order" o ON o.id=i.order_id
          WHERE o.entity_id=$entity""".query[UUID].to[List]

  // The jurisdictions this entity holds VAT in (recognised + its home) — the per-jurisdiction VAT control accounts.
  private def vatJurisdictions(entity: UUID): doobie.ConnectionIO[List[String]] =
    sql"""SELECT DISTINCT jur FROM (
            SELECT vat_jurisdiction AS jur FROM revenue_recognition WHERE entity_id=$entity AND vat_jurisdiction IS NOT NULL
            UNION SELECT jurisdiction FROM entity WHERE id=$entity
          ) j WHERE jur IS NOT NULL""".query[String].to[List]

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
