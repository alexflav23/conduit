package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.AccuracyScorer
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastQueryRepo
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-D — accuracy scoring (doc 12 §9) + WoW reconstruction from append-only history (doc 12 §4.5).
object AccuracySuite extends IOSuite {

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

  private def shipUnits(xa: HikariTransactor[IO], acct: UUID, market: UUID, v: UUID, qty: Int): IO[Unit] =
    (for {
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $acct, $acct, $market, 'closed', 'GBP', 'stripe') RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat) VALUES ($ord, $v, $qty, 100.00) RETURNING id"
          .query[UUID]
          .unique
      d <-
        sql"INSERT INTO dispatch (dispatch_no, order_id, date, status) VALUES (${s"D-${UUID.randomUUID()}"}, $ord, '2026-07-10', 'delivered') RETURNING id"
          .query[UUID]
          .unique
      _ <- sql"INSERT INTO dispatch_line (dispatch_id, order_line_id, qty) VALUES ($d, $ol, $qty)".update.run
    } yield ()).transact(xa)

  test("accuracy scores error/bias/MAPE vs sell-in; MAPE flags the 20% margin discipline") { xa =>
    val svc    = new ForecastService[IO](xa)
    val scorer = new AccuracyScorer[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc  <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      _    <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 120)), None) // forecast 120
      _    <- shipUnits(xa, acct, market, v, 100)                                       // actual sell-in 100
      n    <- scorer.score(acct, month, sc, "sell_in")
      rows <- ForecastQueryRepo.accuracy(acct, month, "sell_in").transact(xa)
    } yield {
      val r = rows.head.hcursor
      // error = 100 - 120 = -20 ; mape = 20/100 = 0.20 ; within the 20% margin
      expect(n == 1) and
        expect(r.get[Int]("forecast_qty").contains(120)) and
        expect(r.get[Int]("actual_qty").contains(100)) and
        expect(r.get[Int]("error").contains(-20)) and
        expect(r.get[String]("mape").contains("0.2000")) and
        expect(r.get[Boolean]("within_margin").contains(true))
    }
  }

  test("WoW: the forecast current as-of a past instant is reconstructable from append-only history") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc     <- svc.openCycle(LocalDate.of(2026, 6, 8), cadence).map(_._1)
      _       <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 100)), None) // first estimate 100
      tMid    <- IO.realTimeInstant
      _       <- IO.sleep(scala.concurrent.duration.DurationInt(10).millis)
      _       <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 140)), None) // revised to 140
      asOfMid <- ForecastQueryRepo.forecastAsOf(market, month, sc, tMid).transact(xa)      // should still be 100
      asOfNow <- IO.realTimeInstant.flatMap(now => ForecastQueryRepo.forecastAsOf(market, month, sc, now).transact(xa))
    } yield expect(asOfMid == 100) and expect(asOfNow == 140)
  }

  test("the market coverage row carries a WoW delta (movement since the last recompute)") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 15), cadence).map(_._1)
      _   <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 100)), None) // prior 0 -> 100, wow +100
      _   <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 130)), None) // 100 -> 130, wow +30
      wow <-
        sql"SELECT wow_delta FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='market'"
          .query[Option[BigDecimal]]
          .unique
          .transact(xa)
    } yield expect(wow.contains(BigDecimal(30)))
  }
}
