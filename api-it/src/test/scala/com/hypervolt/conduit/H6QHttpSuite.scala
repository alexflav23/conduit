package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.H6QRoutes
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import org.http4s.implicits._
import weaver.IOSuite

// M11 — H6Q HTTP surface (doc 12 §11): capture (own-scope), the coverage board (layer-projected) and the
// reconcile + who-owes reads. Uses cadence 'weekly' on September weeks so it never collides with ForecastSuite.
object H6QHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]
  private val month                                         = LocalDate.of(2026, 9, 1)

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] = {
    val auth = new AuthService[IO](xa, devMode = true)
    (new AccessRoutes[IO](xa, auth).routes <+> new H6QRoutes[IO](xa, auth).routes).orNotFound
  }

  private def post(xa: HikariTransactor[IO], path: String, token: String, body: Json): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .withEntity(body)
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

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
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, Nil, None)
    } yield (kc, uid)).transact(xa)
  }

  private def variant(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)

  private def party(
      xa: HikariTransactor[IO],
      pType: String,
      market: UUID,
      channel: UUID,
      owner: UUID,
      parent: Option[UUID]
  ): IO[UUID] =
    sql"""INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment,
            account_manager_user_id, parent_party_id, status)
          VALUES ('Acct', $pType, true, '{forecastable}', $channel, $market, 'wholesale', $owner, $parent, 'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def freshCycle(svc: ForecastService[IO], xa: HikariTransactor[IO], asOf: LocalDate): IO[UUID] =
    sql"UPDATE forecast_cycle SET status='closed', closed_at=now() WHERE cadence='weekly' AND status='open'".update.run
      .transact(xa) *>
      svc.openCycle(asOf).map(_._1)

  private def scenarioP50(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)

  private def submitBody(cycle: UUID, variant: UUID, scenario: UUID, qty: Int): Json =
    Json.obj(
      "cycle" -> cycle.toString.asJson,
      "lines" -> Json.arr(
        Json.obj(
          "variant"  -> variant.toString.asJson,
          "period"   -> "2026-09".asJson,
          "scenario" -> scenario.toString.asJson,
          "qty"      -> qty.asJson
        )
      )
    )

  test(
    "capture: an agent sees their accounts, submits their portion (200), a non-owner is 403, a closed cycle is 409"
  ) { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      ar       <- userWithRole(xa, "retail_sales_agent"); (agent, agentId) = ar
      or       <- userWithRole(xa, "retail_sales_agent"); other            = or._1
      acct     <- party(xa, "installer", market, channel, agentId, None)
      v        <- variant(xa); sc <- scenarioP50(xa)
      cyc      <- freshCycle(svc, xa, LocalDate.of(2026, 9, 7)) // W37
      mine     <- get(xa, "/api/v1/h6q/my-forecasts", s"dev:$agent").flatMap(_.as[Json])
      ok       <- post(xa, s"/api/v1/h6q/my-forecasts/$acct/submit", s"dev:$agent", submitBody(cyc, v, sc, 120))
      notOwner <- post(xa, s"/api/v1/h6q/my-forecasts/$acct/submit", s"dev:$other", submitBody(cyc, v, sc, 10))
      _        <- svc.closeCycle(cyc)
      closed   <- post(xa, s"/api/v1/h6q/my-forecasts/$acct/submit", s"dev:$agent", submitBody(cyc, v, sc, 130))
    } yield {
      val accounts = mine.hcursor.downField("accounts").as[List[Json]].getOrElse(Nil)
      expect(accounts.exists(_.hcursor.get[String]("company_id").contains(acct.toString))) and
        expect(ok.status == Status.Ok) and
        expect(notOwner.status == Status.Forbidden) and
        expect(closed.status == Status.Conflict)
    }
  }

  test(
    "the board rolls up and reconciles over HTTP: Σ branch == Σ agent (ties); finance reads the layer-projected board"
  ) { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      ar     <- userWithRole(xa, "retail_sales_agent"); (agentA, agentAId) = ar
      br     <- userWithRole(xa, "retail_sales_agent"); (agentB, agentBId) = br
      fin    <- userWithRole(xa, "finance"); finance                       = fin._1
      master <- party(xa, "wholesaler", market, channel, agentAId, None)
      leeds  <- party(xa, "branch", market, channel, agentAId, Some(master))
      york   <- party(xa, "branch", market, channel, agentBId, Some(master))
      solo   <- party(xa, "installer", market, channel, agentAId, None)
      v      <- variant(xa); sc <- scenarioP50(xa)
      cyc    <- freshCycle(svc, xa, LocalDate.of(2026, 9, 14)) // W38
      _      <- post(xa, s"/api/v1/h6q/my-forecasts/$leeds/submit", s"dev:$agentA", submitBody(cyc, v, sc, 120))
      _      <- post(xa, s"/api/v1/h6q/my-forecasts/$york/submit", s"dev:$agentB", submitBody(cyc, v, sc, 80))
      _      <- post(xa, s"/api/v1/h6q/my-forecasts/$solo/submit", s"dev:$agentA", submitBody(cyc, v, sc, 50))
      _      <- proj.recompute(market, month, sc)                           // the forecast.submitted consumer's effect
      branch <-
        get(xa, s"/api/v1/h6q/coverage?market=$market&period=2026-09&scenario=$sc&group_by=branch", s"dev:$finance")
          .flatMap(_.as[Json])
      agents <-
        get(xa, s"/api/v1/h6q/coverage?market=$market&period=2026-09&scenario=$sc&group_by=agent", s"dev:$finance")
          .flatMap(_.as[Json])
      recon <- get(xa, s"/api/v1/h6q/coverage/reconcile?market=$market&period=2026-09&scenario=$sc", s"dev:$finance")
        .flatMap(_.as[Json])
      agentVol <-
        get(xa, s"/api/v1/h6q/coverage?market=$market&period=2026-09&scenario=$sc&group_by=market", s"dev:$agentA")
          .flatMap(_.as[Json])
    } yield {
      val branchRows = branch.asArray.getOrElse(Vector.empty)
      val agentRows  = agents.asArray.getOrElse(Vector.empty)
      val branchSum  = branchRows.flatMap(_.hcursor.get[Int]("forecast_qty").toOption).sum
      val agentSum   = agentRows.flatMap(_.hcursor.get[Int]("forecast_qty").toOption).sum
      val volRow     = agentVol.asArray.getOrElse(Vector.empty).headOption
      expect(branchSum == 250) and expect(agentSum == 250) and
        expect(recon.hcursor.get[Boolean]("ties").contains(true)) and
        expect(branchRows.size == 3) and expect(agentRows.size == 2) and
        // volume-only viewer: unit fields present, no money fields materialised
        expect(volRow.exists(_.hcursor.get[Int]("forecast_qty").isRight)) and
        expect(volRow.forall(r => r.hcursor.get[Json]("forecast_revenue").isLeft))
    }
  }

  test("who-owes is visible over HTTP for the open cycle") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      ar   <- userWithRole(xa, "retail_sales_agent"); (agent, agentId) = ar
      fin  <- userWithRole(xa, "finance"); finance                     = fin._1
      a1   <- party(xa, "installer", market, channel, agentId, None)
      _    <- party(xa, "installer", market, channel, agentId, None)
      v    <- variant(xa); sc <- scenarioP50(xa)
      cyc  <- freshCycle(svc, xa, LocalDate.of(2026, 9, 21)) // W39
      _    <- post(xa, s"/api/v1/h6q/my-forecasts/$a1/submit", s"dev:$agent", submitBody(cyc, v, sc, 30))
      owes <- get(xa, s"/api/v1/h6q/outstanding?cycle=$cyc", s"dev:$finance").flatMap(_.as[Json])
    } yield {
      val mine =
        owes.asArray.getOrElse(Vector.empty).find(_.hcursor.get[String]("forecaster").contains(agentId.toString))
      expect(
        mine.exists(r =>
          r.hcursor.get[Int]("accounts_outstanding").contains(1) && r.hcursor.get[Int]("submitted").contains(1)
        )
      )
    }
  }
}
