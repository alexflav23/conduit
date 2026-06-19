package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.shadow.ShadowValidationService
import doobie.hikari.HikariTransactor
import doobie.implicits._
import weaver.IOSuite

// S3.1 — the inbox_quarantine dual-run check: a record that LANDED but failed to map (quarantine) must surface as
// a shadow finding (so the never-lost guarantee's exceptions sit in the dual-run triage queue), and auto-resolve
// when the quarantine clears. Proves the validation loop on the inbox against a real Postgres.
object ShadowInboxCheckSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def reset(xa: HikariTransactor[IO]): IO[Unit] =
    (sql"TRUNCATE ingest_record RESTART IDENTITY".update.run *>
      sql"DELETE FROM shadow_finding WHERE check_code = 'inbox_quarantine'".update.run).transact(xa).void

  private def landFailed(xa: HikariTransactor[IO], src: String, ds: String, id: String): IO[Unit] =
    sql"""INSERT INTO ingest_record (source, dataset, source_id, payload, source_hash, status, last_error)
          VALUES ($src, $ds, $id, '{}'::jsonb, 'h', 'failed', 'no handler for source family')""".update.run.transact(xa).void

  private def openFindings(xa: HikariTransactor[IO]): IO[Int] =
    sql"SELECT count(*) FROM shadow_finding WHERE check_code = 'inbox_quarantine' AND status <> 'resolved'"
      .query[Int].unique.transact(xa)

  test("a quarantined inbound record raises an inbox_quarantine finding; clearing it auto-resolves") { xa =>
    val svc = new ShadowValidationService[IO](xa)
    for {
      _       <- reset(xa)
      _       <- landFailed(xa, "mrpeasy", "customer_orders", "x1")
      _       <- landFailed(xa, "mrpeasy", "customer_orders", "x2") // same (source,dataset) → one grouped finding
      _       <- landFailed(xa, "hubspot", "deals", "d1")           // a second (source,dataset) → a second finding
      _       <- svc.runAll(None, shadowMode = true)
      raised  <- openFindings(xa)
      actual  <- sql"SELECT actual::int FROM shadow_finding WHERE check_code='inbox_quarantine' AND scope_id='mrpeasy/customer_orders'".query[Int].unique.transact(xa)
      // clear the quarantine (the fix + requeue path marks them processed) and re-run → the finding resolves
      _       <- sql"UPDATE ingest_record SET status='processed' WHERE status='failed'".update.run.transact(xa)
      _       <- svc.runAll(None, shadowMode = true)
      cleared <- openFindings(xa)
    } yield expect(raised == 2) and expect(actual == 2) and expect(cleared == 0)
  }
}
