package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.close.DualRunReconciler
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M-Ingest slice 6 (spec doc 33 §5): the dual-run reconciler proves Conduit's books match the source systems.
// AR comparison at tolerance 0 (Conduit invoiced total vs the ingested Xero total → matched/exception on the
// reconciliation dashboard) + drift detection (a source row edited after we ingested it).
object DualRunReconcileSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def period(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('DR','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      p <-
        sql"INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status) VALUES ($e,'month','2026-06','Europe/London','open') RETURNING id"
          .query[UUID]
          .unique
    } yield p).transact(xa)

  // a Conduit invoice contributing `incVat` to the AR projection.
  private def conduitInvoice(xa: HikariTransactor[IO], incVat: BigDecimal): IO[Unit] =
    (for {
      p <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('DR Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      o <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                   VALUES (${s"O-${UUID.randomUUID()}"},'trade',$p,$p,'invoiced','GBP','invoice') RETURNING id"""
          .query[UUID]
          .unique
      _ <- sql"""INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status)
                 VALUES ($o, ${s"INV-${UUID.randomUUID()}"}, 0, 0, $incVat, 'open')""".update.run
    } yield ()).transact(xa)

  // an ingested Xero invoice (the SoR side) with `total` inc-VAT, recorded in the universal source ledger.
  private def xeroInvoice(xa: HikariTransactor[IO], total: BigDecimal): IO[Unit] =
    sql"""INSERT INTO migration_record (source, entity_type, source_id, conduit_id, batch_id, source_payload, source_hash, phase)
          VALUES ('xero','invoice',${s"x-${UUID.randomUUID()}"}, gen_random_uuid(), gen_random_uuid(),
                  ${s"""{"Total":$total}"""}::jsonb, 'h', 1)""".update.run.transact(xa).void

  test("AR dual-run: matched when Conduit's invoiced total equals the ingested Xero total, exception on divergence") {
    xa =>
      val rec = new DualRunReconciler[IO](xa)
      for {
        pid     <- period(xa)
        _       <- conduitInvoice(xa, BigDecimal("1200.00"))
        _       <- xeroInvoice(xa, BigDecimal("1200.00"))
        matched <- rec.reconcileXeroAr(pid, "GBP")
        _       <- conduitInvoice(xa, BigDecimal("600.00")) // Conduit now ahead of Xero by 600 — a real divergence
        broke   <- rec.reconcileXeroAr(pid, "GBP")
        rows <-
          sql"SELECT count(*) FROM reconciliation WHERE type='dualrun_ar_xero' AND period_id=$pid"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(matched.status == "matched") and expect(matched.variance.signum == 0) and
        expect(broke.status == "exception") and expect(broke.variance == BigDecimal("600.00")) and
        expect(rows == 2L) // both comparisons land on the dashboard
  }

  test("drift: a changed source hash flags the row; driftCount reflects it; an unchanged hash does not") { xa =>
    val rec = new DualRunReconciler[IO](xa)
    val sid = s"d-${UUID.randomUUID()}"
    for {
      _ <-
        sql"""INSERT INTO migration_record (source, entity_type, source_id, conduit_id, batch_id, source_payload, source_hash, phase)
              VALUES ('driftsrc','order',$sid, gen_random_uuid(), gen_random_uuid(), '{}'::jsonb, 'orig-hash', 1)""".update.run
          .transact(xa)
      drifted   <- rec.flagDrift("driftsrc", sid, "new-hash") // edited at source → hash changed
      countA    <- rec.driftCount("driftsrc")
      unchanged <- rec.flagDrift("driftsrc", sid, "new-hash") // same hash again → no new drift (already flagged)
      countB    <- rec.driftCount("driftsrc")
    } yield expect(drifted) and expect(countA == 1L) and expect(!unchanged) and expect(countB == 1L)
  }
}
