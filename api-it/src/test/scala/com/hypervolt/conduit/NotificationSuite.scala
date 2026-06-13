package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastService
import com.hypervolt.conduit.notification.NotificationRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-B — every H6Q update propagates: a coverage recompute emits forecast.coverage.updated AND fans out to
// subscribers (incl. the contract manufacturer) past their materiality threshold. Forward visibility shifting
// reaches whoever needs to know (doc 12 §2.6, doc 10 §B).
object NotificationSuite extends IOSuite {

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

  test("a forecast submission propagates forecast.coverage.updated and notifies the contract manufacturer") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      _   <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 120)), None) // first forecast: prior 0 -> 120
      // the propagated event landed in the outbox for this market slice
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='forecast.coverage.updated' AND aggregate_id=$market"
          .query[Long]
          .unique
          .transact(xa)
      // the contract manufacturer (webhook, >=10% threshold) was notified — pending an outbound send
      cmNotes <- sql"""SELECT count(*) FROM notification n JOIN notification_subscription s ON s.id=n.subscription_id
                       WHERE s.name LIKE 'Contract manufacturer%' AND n.event_type='forecast.coverage.updated'
                         AND (n.payload->>'market_id') = ${market.toString}""".query[Long].unique.transact(xa)
      cmStatus <-
        sql"""SELECT n.status FROM notification n JOIN notification_subscription s ON s.id=n.subscription_id
                        WHERE s.name LIKE 'Contract manufacturer%' AND (n.payload->>'market_id') = ${market.toString} LIMIT 1"""
          .query[String]
          .unique
          .transact(xa)
    } yield expect(events >= 1L) and expect(cmNotes >= 1L) and expect(cmStatus == "pending")
  }

  test("a sub-threshold change does not spam the contract manufacturer, but the always-on exec still hears it") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 8), cadence).map(_._1)
      _ <- svc.submit(
        agent,
        acct,
        cyc,
        List(ForecastLine(v, month, sc, 100)),
        None
      ) // prior 0 -> 100 (material, both notified)
      _ <- svc.submit(
        agent,
        acct,
        cyc,
        List(ForecastLine(v, month, sc, 103)),
        None
      ) // +3% (< 10%): CM skipped, exec still hears
      cmCount <-
        sql"""SELECT count(*) FROM notification n JOIN notification_subscription s ON s.id=n.subscription_id
                       WHERE s.name LIKE 'Contract manufacturer%' AND (n.payload->>'market_id') = ${market.toString}"""
          .query[Long]
          .unique
          .transact(xa)
      execCount <-
        sql"""SELECT count(*) FROM notification n JOIN notification_subscription s ON s.id=n.subscription_id
                         WHERE s.name LIKE 'Exec%' AND (n.payload->>'market_id') = ${market.toString}"""
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(cmCount == 1L) and expect(execCount == 2L) // CM once (the material first move); exec on both
  }

  test("markSent moves a pending external notification to sent (the delivery relay)") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- account(xa, market, channel, agent)
      v     <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 15), cadence).map(_._1)
      _   <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 200)), None)
      ids <-
        sql"""SELECT n.id FROM notification n JOIN notification_subscription s ON s.id=n.subscription_id
                     WHERE s.channel='webhook' AND n.status='pending' AND (n.payload->>'market_id') = ${market.toString}"""
          .query[UUID]
          .to[List]
          .transact(xa)
      sent <- NotificationRepo.markSent(ids).transact(xa)
    } yield expect(ids.nonEmpty) and expect(sent == ids.size)
  }
}
