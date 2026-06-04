package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-F — coverage exists PER SKU (built on the catalogue), not just the all-SKU total. Different SKUs don't
// equate, so coverage must be per variant; the all-SKU total is the sum of the per-SKU rows (doc 12 §4).
object SkuCoverageSuite extends IOSuite {

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

  private def variant(xa: HikariTransactor[IO], sku: String): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, $sku, 'v3', true) RETURNING id"
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

  test("coverage is materialised per SKU and the all-SKU total equals the sum of the per-SKU rows") { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v1    <- variant(xa, s"HV-7M-BLK-${UUID.randomUUID().toString.take(6)}") // e.g. 7m black
      v2    <- variant(xa, s"HV-5M-WHT-${UUID.randomUUID().toString.take(6)}") // e.g. 5m white
      sc    <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      _   <- svc.submit(agent, acct, cyc, List(ForecastLine(v1, month, sc, 120), ForecastLine(v2, month, sc, 80)), None)
      _   <- proj.recompute(market, month, sc)
      allSku <-
        sql"SELECT forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='market' AND product_variant_id IS NULL"
          .query[Int]
          .unique
          .transact(xa)
      perSku <-
        sql"SELECT product_variant_id, forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='market' AND product_variant_id IS NOT NULL ORDER BY forecast_qty DESC"
          .query[(UUID, Int)]
          .to[List]
          .transact(xa)
      // per-SKU branch axis still reconciles to the per-SKU agent axis
      branchV1 <-
        sql"SELECT forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='branch' AND product_variant_id=$v1"
          .query[Int]
          .unique
          .transact(xa)
      agentV1 <-
        sql"SELECT forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='agent' AND product_variant_id=$v1"
          .query[Int]
          .unique
          .transact(xa)
    } yield expect(allSku == 200) and
      expect(perSku == List((v1, 120), (v2, 80))) and
      expect(perSku.map(_._2).sum == allSku) and
      expect(branchV1 == 120) and expect(agentV1 == 120) // per-SKU reconciliation holds
  }
}
