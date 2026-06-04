package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.H6QRoutes
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import org.http4s._
import org.http4s.headers.Authorization
import org.http4s.implicits._
import weaver.IOSuite

// M11-E — ex-cut resolution (doc 12 §5.2) + layer-respecting, audited, output-only export (doc 12 §8.4).
object ExCutExportSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val cadence = "unit"
  private val month   = LocalDate.of(2026, 7, 1)

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] = {
    val auth = new AuthService[IO](xa, devMode = true)
    (new AccessRoutes[IO](xa, auth).routes <+> new H6QRoutes[IO](xa, auth).routes).orNotFound
  }

  private def get(xa: HikariTransactor[IO], path: String, token: String): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.GET, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

  private def userWithRole(xa: HikariTransactor[IO], role: String): IO[(String, UUID)] = {
    val kc = s"$role-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some(role))
      rid <- sql"SELECT id FROM role WHERE name = $role".query[UUID].unique
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, None)
    } yield (kc, uid)).transact(xa)
  }

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

  private def account(
      xa: HikariTransactor[IO],
      market: UUID,
      channel: UUID,
      owner: UUID,
      excludable: Boolean
  ): IO[UUID] = {
    val attrs = if (excludable) """{"h6q_excludable":"true"}""" else "{}"
    sql"""INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment, account_manager_user_id, attributes, status)
          VALUES ('A','installer',true,'{forecastable}',$channel,$market,'retail',$owner,$attrs::jsonb,'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)
  }

  private def scenarioP50(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)

  test("ex-cut: an excludable named account drops out of forecast_qty_ex while the all-in figure keeps it") { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      ar      <- userWithRole(xa, "retail_sales_agent"); (agent, agentId) = ar
      normal  <- account(xa, market, channel, agentId, excludable = false)
      octopus <- account(xa, market, channel, agentId, excludable = true) // the "Octopus" cuttable account
      v       <- variant(xa); sc <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence).map(_._1)
      _   <- svc.submit(agentId, normal, cyc, List(ForecastLine(v, month, sc, 100)), None)
      _   <- svc.submit(agentId, octopus, cyc, List(ForecastLine(v, month, sc, 60)), None)
      _   <- proj.recompute(market, month, sc)
      row <-
        sql"SELECT forecast_qty, forecast_qty_ex FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='market' AND product_variant_id IS NULL"
          .query[(Int, Int)]
          .unique
          .transact(xa)
    } yield expect(row._1 == 160) and expect(row._2 == 100) // all-in keeps Octopus (160); ex-cut removes it (100)
  }

  test(
    "export is output-only, layer-respecting and permission-gated: finance gets CSV, an agent without export is 403"
  ) { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      ar   <- userWithRole(xa, "retail_sales_agent"); (agent, agentId) = ar
      fin  <- userWithRole(xa, "finance"); finance                     = fin._1
      acct <- account(xa, market, channel, agentId, excludable = false)
      v    <- variant(xa); sc                                         <- scenarioP50(xa)
      _ <-
        sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run.transact(xa)
      cyc <- svc.openCycle(LocalDate.of(2026, 6, 8), cadence).map(_._1)
      _   <- svc.submit(agentId, acct, cyc, List(ForecastLine(v, month, sc, 90)), None)
      _   <- proj.recompute(market, month, sc)
      finResp <-
        get(xa, s"/api/v1/h6q/export?market=$market&period=2026-07&scenario=$sc&group_by=branch", s"dev:$finance")
      csv <- finResp.as[String]
      agentResp <-
        get(xa, s"/api/v1/h6q/export?market=$market&period=2026-07&scenario=$sc&group_by=branch", s"dev:$agent")
      audited <-
        sql"SELECT count(*) FROM audit_log WHERE entity_type='pipeline_coverage' AND action='export' AND entity_id=$market"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(finResp.status == Status.Ok) and expect(csv.contains("forecast_qty")) and expect(
      csv.contains("90")
    ) and
      expect(agentResp.status == Status.Forbidden) and expect(audited == 1L)
  }
}
