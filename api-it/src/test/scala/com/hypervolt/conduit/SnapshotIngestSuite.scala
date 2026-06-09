package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.ingest.SnapshotLoader
import doobie.implicits._
import doobie.hikari.HikariTransactor
import java.nio.file.Paths
import weaver.IOSuite

// doc 26 §3a — the git-snapshot ingest: checkout → boot → seeded. Loads the REAL committed snapshot
// (ingest/exogenous/uk_car_sales.ndjson), proves censoring metadata (known_at) survives, and proves a re-load is
// an exact no-op — so every boot converges to the same state (the compose bring-up guarantee).
object SnapshotIngestSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("the committed NDJSON snapshot loads on boot, censorable, and a re-load is a no-op") { xa =>
    val loader = new SnapshotLoader[IO](xa)
    val root   = Paths.get(sys.props.getOrElse("user.dir", ".")).resolve("../ingest").normalize()
    for {
      n1 <- loader.loadAll(root)
      n2 <- loader.loadAll(root) // idempotent: the same files write nothing new
      rows <-
        sql"SELECT count(*) FROM exogenous_series WHERE series_key = 'uk_car_sales'"
          .query[Long]
          .unique
          .transact(xa)
      march <- sql"""SELECT value FROM exogenous_series WHERE series_key = 'uk_car_sales'
              AND period_month = '2025-03-01'""".query[BigDecimal].unique.transact(xa)
      // censoring: the March figure was not knowable before its publication date
      knowableInMarch <-
        sql"""SELECT count(*) FROM exogenous_series WHERE series_key = 'uk_car_sales'
              AND period_month = '2025-03-01' AND known_at < '2025-03-31T00:00:00Z'"""
          .query[Long]
          .unique
          .transact(xa)
      missing <- loader.loadAll(Paths.get("/nonexistent")) // a missing root is a clean no-op
    } yield expect(n1 == 6) and expect(n2 == 0) and
      expect(rows == 6L) and expect(march == BigDecimal("357103.0000")) and
      expect(knowableInMarch == 0L) and expect(missing == 0)
  }
}
