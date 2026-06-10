package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.ingest.SnapshotLoader
import doobie.implicits._
import doobie.hikari.HikariTransactor
import java.nio.file.Paths
import weaver.IOSuite

// doc 26 §3a — the git-snapshot ingest: checkout → boot → seeded. Loads the REAL committed snapshot (SMMT BEV
// registrations, the HubSpot deal lifecycle, the full MRPeasy order/shipment history), proves censoring
// metadata (known_at) survives, and proves a re-load CONVERGES — the guarantee is identical state, not zero
// writes (the hubspot handler is an upsert by design: a deal open in today's snapshot closes in next month's).
object SnapshotIngestSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def state(xa: HikariTransactor[IO]): IO[(Long, Long, Long, Long, BigDecimal)] =
    (for {
      bev     <- sql"SELECT count(*) FROM exogenous_series WHERE series_key = 'uk_bev_registrations'".query[Long].unique
      deals   <- sql"SELECT count(*) FROM deal_snapshot".query[Long].unique
      orders  <- sql"""SELECT count(*) FROM "order" WHERE order_no LIKE 'MRP-%'""".query[Long].unique
      serials <- sql"SELECT count(*) FROM serial_unit".query[Long].unique
      march   <- sql"""SELECT value FROM exogenous_series WHERE series_key = 'uk_bev_registrations'
              AND period_month = '2026-03-01'""".query[BigDecimal].unique
    } yield (bev, deals, orders, serials, march)).transact(xa)

  test("the committed real snapshot loads on boot, censorable, and a re-load converges to identical state") { xa =>
    val loader = new SnapshotLoader[IO](xa)
    val root   = Paths.get(sys.props.getOrElse("user.dir", ".")).resolve("../ingest").normalize()
    for {
      n1     <- loader.loadAll(root)
      first  <- state(xa)
      _      <- loader.loadAll(root) // re-load: upserts may write, the STATE must not move
      second <- state(xa)
      // censoring: March'26 BEV registrations were not knowable before SMMT published in April
      knowableInMarch <-
        sql"""SELECT count(*) FROM exogenous_series WHERE series_key = 'uk_bev_registrations'
              AND period_month = '2026-03-01' AND known_at < '2026-04-01T00:00:00Z'"""
          .query[Long]
          .unique
          .transact(xa)
      missing <- loader.loadAll(Paths.get("/nonexistent")) // a missing root is a clean no-op
    } yield expect(n1 > 100000L) and // the full real history seeds in one boot
      expect(first == second) and    // convergence: the bring-up guarantee
      expect(first._1 == 6L) and expect(first._2 > 50000L) and
      expect(first._3 > 49000L) and expect(first._4 > 100000L) and
      expect(first._5 == BigDecimal(86120)) and // SMMT March'26 record month
      expect(knowableInMarch == 0L) and expect(missing == 0)
  }
}
