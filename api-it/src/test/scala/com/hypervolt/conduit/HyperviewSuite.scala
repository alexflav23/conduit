package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastService
import com.hypervolt.conduit.forecast.HyperviewService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-C — Hyperview source ingest + precedence (doc 12 §6). Model forecasts land append-only as
// source='hyperview'; manual estimates override the model by default; absent a manual estimate Hyperview is the line.
object HyperviewSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val cadence = "unit"
  private val month   = LocalDate.of(2026, 7, 1)

  private def user(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"u-${UUID.randomUUID()}"}, 'A') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def variant(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)

  private def account(xa: HikariTransactor[IO], market: UUID, channel: UUID, owner: UUID): IO[UUID] =
    sql"""INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment, account_manager_user_id, status)
          VALUES ('A','installer',true,'{forecastable}',$channel,$market,'retail',$owner,'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def scenarioP50(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)

  private def branchCoverage(xa: HikariTransactor[IO], market: UUID, sc: UUID): IO[(Int, String)] =
    sql"SELECT forecast_qty, forecast_source FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='branch' AND product_variant_id IS NULL"
      .query[(Int, String)]
      .unique
      .transact(xa)

  test("Hyperview is the line absent a manual estimate; a manual estimate then overrides it (append-only both)") { xa =>
    val svc    = new ForecastService[IO](xa)
    val hv     = new HyperviewService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      // 1) Hyperview publishes 80 → it is the forecast line (no manual yet)
      pub1  <- hv.publish(acct, v, month, sc, 80, "prophet-v1")
      hvRow <- branchCoverage(xa, market, sc)
      // 2) the agent submits a manual 120 → manual overrides the model
      _      <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 120)), None)
      manRow <- branchCoverage(xa, market, sc)
      // 3) Hyperview republishes 90 (append-only) → manual still wins
      pub2        <- hv.publish(acct, v, month, sc, 90, "prophet-v2")
      _           <- proj.recompute(market, month, sc)
      stillManual <- branchCoverage(xa, market, sc)
      hvVersions <-
        sql"SELECT count(*) FROM forecast_entry WHERE branch_company_id=$acct AND source='hyperview'"
          .query[Long]
          .unique
          .transact(xa)
      hvSuperseded <-
        sql"SELECT count(*) FROM forecast_entry WHERE branch_company_id=$acct AND source='hyperview' AND superseded_by IS NOT NULL"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(pub1 == Right(true)) and expect(hvRow == (80, "hyperview")) and
      expect(manRow == (120, "manual")) and
      expect(pub2 == Right(true)) and expect(stillManual == (120, "manual")) and
      expect(hvVersions == 2L) and expect(hvSuperseded == 1L)
  }

  test("republishing the same Hyperview number is a no-op (idempotent on key+qty+model_version)") { xa =>
    val hv     = new HyperviewService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc                                <- scenarioP50(xa)
      a     <- hv.publish(acct, v, month, sc, 50, "prophet-v1")
      b     <- hv.publish(acct, v, month, sc, 50, "prophet-v1") // identical → no-op
      rows <-
        sql"SELECT count(*) FROM forecast_entry WHERE branch_company_id=$acct AND source='hyperview'"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(a == Right(true)) and expect(b == Right(false)) and expect(rows == 1L)
  }
}
