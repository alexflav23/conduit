package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The dual-run reconciler (spec doc 33 §5, extends spec/18 §4.2). While Conduit runs in shadow it keeps a full
// parallel set of books; THIS is the proof those books match the source systems — per domain it compares
// Conduit's projection (actual) to the source-system aggregate (expected, read from the ingested
// migration_record payloads) at TOLERANCE ZERO on money + units, and writes a reconciliation row so the
// divergence lands on the same Auditability dashboard finance uses at close. A sustained all-matched window is
// the cutover green light. Plus drift detection (spec/18 §4.3): a source row edited after we ingested it.
final class DualRunReconciler[F[_]: Async](xa: Transactor[F]) {

  // Generic tolerance-0 comparator: source = expected (the SoR), conduit = actual (our derived projection).
  def reconcile(
      periodId: UUID,
      reconType: String,
      source: BigDecimal,
      conduit: BigDecimal,
      currency: String
  ): F[ReconResult] =
    record(periodId, reconType, source, conduit, currency).transact(xa)

  // AR dual-run: Conduit's invoiced total vs the ingested Xero invoice total. The canonical money comparison;
  // inventory/orders/activations plug into `reconcile` the same way (source aggregate vs Conduit projection).
  def reconcileXeroAr(periodId: UUID, currency: String): F[ReconResult] =
    (xeroInvoicedTotal, conduitInvoicedTotal).tupled
      .flatMap { case (src, con) => record(periodId, "dualrun_ar_xero", src, con, currency) }
      .transact(xa)

  // Source drift (spec/18 §4.3): on a re-pull the connector hands the fresh hash; if it differs from what we
  // ingested, the source row was edited after migration — flag it for a targeted idempotent re-apply.
  def flagDrift(source: String, sourceId: String, freshHash: String): F[Boolean] =
    sql"""UPDATE migration_record SET status = 'exception'
          WHERE source = $source AND source_id = $sourceId AND source_hash <> $freshHash AND status <> 'exception'""".update.run
      .transact(xa)
      .map(_ > 0)

  def driftCount(source: String): F[Long] =
    sql"SELECT count(*) FROM migration_record WHERE source = $source AND status = 'exception'"
      .query[Long]
      .unique
      .transact(xa)

  private def xeroInvoicedTotal: ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM((source_payload->>'Total')::numeric), 0)
          FROM migration_record WHERE source = 'xero' AND entity_type = 'invoice'""".query[BigDecimal].unique

  private def conduitInvoicedTotal: ConnectionIO[BigDecimal] =
    sql"SELECT COALESCE(SUM(total_inc_vat), 0) FROM order_invoice WHERE status <> 'void'".query[BigDecimal].unique

  // mirrors ReconciliationService.record (same table, same tolerance-0 status rule) — expected=source, actual=conduit.
  private def record(
      periodId: UUID,
      reconType: String,
      expected: BigDecimal,
      actual: BigDecimal,
      currency: String
  ): ConnectionIO[ReconResult] = {
    val variance = (actual - expected).setScale(2, RoundingMode.HALF_UP)
    val status   = if (variance.signum == 0) "matched" else "exception"
    sql"""INSERT INTO reconciliation (type, period_id, expected, actual, currency, variance, status)
          VALUES ($reconType, $periodId, ${expected.setScale(2, RoundingMode.HALF_UP)},
                  ${actual.setScale(2, RoundingMode.HALF_UP)}, $currency, $variance, $status) RETURNING id"""
      .query[UUID]
      .unique
      .map(id =>
        ReconResult(
          id,
          expected.setScale(2, RoundingMode.HALF_UP),
          actual.setScale(2, RoundingMode.HALF_UP),
          variance,
          status
        )
      )
  }
}
