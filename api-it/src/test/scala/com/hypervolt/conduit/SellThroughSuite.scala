package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastService
import com.hypervolt.conduit.forecast.SellThroughProjector
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-A — actuals into coverage (doc 12 §4.3, §7): shipped from dispatch (sell-in), activated from v3
// activations (sell-through), real coverage_pct, and sell_through/overhang with the V2/V3 rule.
object SellThroughSuite extends IOSuite {

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

  // Dispatch `shipped` units of `v` to `acct` in July; mark `activated` of them as v3-activated in July.
  private def actuals(
      xa: HikariTransactor[IO],
      acct: UUID,
      market: UUID,
      v: UUID,
      shipped: Int,
      activated: Int
  ): IO[Unit] =
    (for {
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $acct, $acct, $market, 'closed', 'GBP', 'stripe') RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat) VALUES ($ord, $v, $shipped, 100.00) RETURNING id"
          .query[UUID]
          .unique
      d <-
        sql"INSERT INTO dispatch (dispatch_no, order_id, date, status) VALUES (${s"D-${UUID.randomUUID()}"}, $ord, '2026-07-10', 'delivered') RETURNING id"
          .query[UUID]
          .unique
      _ <- sql"INSERT INTO dispatch_line (dispatch_id, order_line_id, qty) VALUES ($d, $ol, $shipped)".update.run
      _ <- (0 until activated).toList.traverse_ { _ =>
        val serial = s"SER-${UUID.randomUUID()}"
        sql"INSERT INTO serial_unit (serial_no, generation, product_variant_id, company_id, status) VALUES ($serial, 'v3', $v, $acct, 'activated')".update.run *>
          sql"INSERT INTO activation (serial, placement_id, placement_version, activated_at) VALUES ($serial, ${UUID
            .randomUUID()}, 1, '2026-07-15')".update.run.void
      }
    } yield ()).transact(xa)

  test("coverage carries real shipped + activated; coverage_pct = (shipped + weighted_pipeline) / forecast") { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc                                                         <- scenarioP50(xa)
      cyc   <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      _     <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 120)), None) // submit recomputes
      _     <- actuals(xa, acct, market, v, shipped = 60, activated = 25)
      _     <- proj.recompute(market, month, sc)                                         // recompute after actuals land
      row <-
        sql"SELECT shipped_qty, activated_qty, coverage_pct FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='branch'"
          .query[(Int, Int, BigDecimal)]
          .unique
          .transact(xa)
    } yield expect(row._1 == 60) and expect(row._2 == 25) and expect(row._3 == BigDecimal("0.5000"))
  }

  test("sell_through materialises sell-in vs v3 sell-through and cumulative overhang") { xa =>
    val st     = new SellThroughProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa)
      _     <- actuals(xa, acct, market, v, shipped = 100, activated = 30) // ship 100, only 30 activated (v3)
      n     <- st.recompute(acct)
      row <-
        sql"SELECT sell_in_qty, sell_through_qty, overhang_qty, generation_scope FROM sell_through WHERE company_id=$acct AND period_month=$month"
          .query[(Int, Int, Int, String)]
          .unique
          .transact(xa)
    } yield expect(n >= 1) and expect(row._1 == 100) and expect(row._2 == 30) and expect(row._3 == 70) and expect(
      row._4 == "v3"
    )
  }
}
