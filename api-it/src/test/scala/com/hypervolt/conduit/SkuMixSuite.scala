package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.ForecastService
import com.hypervolt.conduit.forecast.SkuMixRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-G — an agent's unit count is split into a per-SKU forecast via the channel's SKU mix, but H6Q still records
// quantities PER SKU (doc 12 §1.2). Conserving: Σ per-SKU == the entered total.
object SkuMixSuite extends IOSuite {

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
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3 Pro') RETURNING id"
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

  test("an aggregate unit count is split into per-SKU H6Q records via the channel mix, conserving the total") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      black <- variant(xa, s"HV3PROAAUB050T2-${UUID.randomUUID().toString.take(6)}") // 5m black
      white <- variant(xa, s"HV3PROAAUW050T2-${UUID.randomUUID().toString.take(6)}") // 5m white
      grey  <- variant(xa, s"HV3PROAASG050T2-${UUID.randomUUID().toString.take(6)}") // 5m grey
      sc    <- scenarioP50(xa)
      // a channel-scoped mix: 50% black, 30% white, 20% grey
      _ <-
        SkuMixRepo
          .createMix(
            "UK retail 5m",
            Some(channel),
            Some(market),
            List(black -> BigDecimal("0.50"), white -> BigDecimal("0.30"), grey -> BigDecimal("0.20"))
          )
          .transact(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      // the agent enters a single number: 100 units for July
      r    <- svc.submitMix(agent, acct, cyc, month, sc, 100, Some("ipad"))
      rows <- sql"""SELECT product_variant_id, qty FROM forecast_entry
                    WHERE branch_company_id=$acct AND period_month=$month AND scenario_id=$sc AND superseded_by IS NULL
                    ORDER BY qty DESC""".query[(UUID, Int)].to[List].transact(xa)
    } yield expect(r == Right(3)) and
      expect(rows.map(_._2).sum == 100) and                      // conserved
      expect(rows == List((black, 50), (white, 30), (grey, 20))) // split per SKU
  }

  test("submit-mix without a configured mix is rejected (no_sku_mix)") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      sc    <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 8), cadence).map(_._1)
      r   <- svc.submitMix(agent, acct, cyc, month, sc, 100, None)
    } yield expect(r == Left("no_sku_mix"))
  }
}
