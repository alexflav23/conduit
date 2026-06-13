package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.assurance.FingerprintService
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.demo.DemoBook
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.hikari.HikariTransactor
import weaver.IOSuite

// M-Assurance D (spec doc 29): reproducibility, proven. The fingerprint over the demo book's money
// aggregates is STABLE — recomputing it at the same ingest SHA yields the SAME digest (despite the demo
// being seeded with fresh UUIDs each run, because the digest is id-independent). CTRL-REPRO surfaces any
// (scope, sha) that produced two different digests — non-determinism or drift — and the suite proves it
// detects a seeded drift, then clears on restore.
object ReproSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def control(xa: HikariTransactor[IO]): IO[Long] =
    new ControlRunner[IO](xa).run("CTRL-REPRO", None).map(_.toOption.get.violations)

  test("the demo book fingerprints identically twice at the same ingest SHA — id churn does not perturb it") {
    case (xa, client) =>
      val fp = new FingerprintService[IO](xa)
      for {
        _  <- DemoBook.seed(xa, TigerBeetleLedger.fromClient[IO](client))
        d1 <- fp.compute("sha-fixed")
        d2 <- fp.compute("sha-fixed") // recompute over the SAME data
        d3 <- fp.compute("sha-other") // a different code/data point
        // record two manifests for the same point — CTRL-REPRO must stay green
        _  <- fp.record("ledger", "sha-fixed")
        _  <- fp.record("ledger", "sha-fixed")
        ok <- control(xa)
      } yield expect(d1 == d2) and expect(d1 != d3) and expect.same(ok, 0L)
  }

  test("DETECTION: a drift under the same (scope, sha) fails CTRL-REPRO; clearing the bad manifest restores it") {
    case (xa, _) =>
      for {
        // a manifest whose digest disagrees with the prior 'sha-fixed' ones — the drift signature
        _      <- sql"""INSERT INTO reproduction_manifest (scope, git_sha, digest, line_count)
                        VALUES ('ledger', 'sha-fixed', 'DRIFTED-DIGEST', 0)""".update.run.transact(xa)
        broken <- control(xa)
        _      <- sql"DELETE FROM reproduction_manifest WHERE digest = 'DRIFTED-DIGEST'".update.run.transact(xa)
        fixed  <- control(xa)
      } yield expect(broken > 0L) and expect.same(fixed, 0L)
  }
}
