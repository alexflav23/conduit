package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastRepo
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11 — H6Q cycle engine + append-only capture + bottom-up rollup reconciliation (doc 12 §2–4).
object ForecastSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val month = LocalDate.of(2026, 7, 1)

  // Each test uses a distinct ISO week, and we first close any open weekly cycle — the schema enforces at most
  // one open cycle per cadence (doc 12 §2.8), and a closed cycle never reopens, so tests cannot share a week.
  // This suite uses its own cadence ('unit') so it never collides with H6QHttpSuite's 'weekly' open cycle on the
  // one-open-per-cadence index (suites may run concurrently against the shared Postgres).
  private val cadence = "unit"
  private def closeOpen(xa: HikariTransactor[IO]): IO[Unit] =
    sql"UPDATE forecast_cycle SET status='closed', closed_at=now() WHERE cadence=$cadence AND status='open'".update.run
      .transact(xa)
      .void

  private def freshOpen(svc: ForecastService[IO], xa: HikariTransactor[IO], asOf: LocalDate): IO[UUID] =
    closeOpen(xa) *> svc.openCycle(asOf, cadence).map(_._1)

  private def user(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"u-${UUID.randomUUID()}"}, 'Agent') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

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
      roles: List[String],
      market: UUID,
      channel: UUID,
      segment: String,
      owner: Option[UUID],
      parent: Option[UUID]
  ): IO[UUID] =
    sql"""INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment,
            account_manager_user_id, parent_party_id, status)
          VALUES ('Acct', $pType, true, $roles, $channel, $market, $segment, $owner, $parent, 'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def scenarioP50(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)

  test(
    "openCycle generates one outstanding submission per owned LEAF account (master excluded); re-run is idempotent"
  ) { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agentA <- user(xa); agentB <- user(xa)
      master <- party(xa, "wholesaler", List("forecastable"), market, channel, "wholesale", None, None)
      _      <- party(xa, "branch", List("forecastable"), market, channel, "wholesale", Some(agentA), Some(master))
      _      <- party(xa, "branch", List("forecastable"), market, channel, "wholesale", Some(agentB), Some(master))
      _ <-
        party(xa, "installer", List("forecastable"), market, channel, "retail", Some(agentA), None) // standalone leaf
      _     <- closeOpen(xa)
      first <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence) // W23
      (cycleId, created1) = first
      second <- svc.openCycle(LocalDate.of(2026, 6, 1), cadence) // same ISO week — must add nothing
      created2 = second._2
      // count submissions belonging to our agents only (the DB may hold others from parallel data)
      mine <-
        sql"SELECT count(*) FROM forecast_submission WHERE cycle_id=$cycleId AND forecaster_user_id IN ($agentA,$agentB)"
          .query[Long]
          .unique
          .transact(xa)
      masterSub <-
        sql"SELECT count(*) FROM forecast_submission WHERE cycle_id=$cycleId AND company_id=$master"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(created1 >= 3) and expect(created2 == 0) and expect(mine == 3L) and expect(masterSub == 0L)
  }

  test("submit versions append-only; a revision supersedes the prior; an unchanged value is not re-versioned") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      acct  <- party(xa, "installer", List("forecastable"), market, channel, "retail", Some(agent), None)
      v     <- variant(xa)
      sc    <- scenarioP50(xa)
      cyc   <- freshOpen(svc, xa, LocalDate.of(2026, 6, 8))                                      // W24
      r1    <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 120)), Some("ipad"))
      r2    <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 140)), Some("ipad")) // revision
      r3    <- svc.submit(agent, acct, cyc, List(ForecastLine(v, month, sc, 140)), Some("ipad")) // no-op
      total <-
        sql"SELECT count(*) FROM forecast_entry WHERE branch_company_id=$acct AND product_variant_id=$v"
          .query[Long]
          .unique
          .transact(xa)
      current <-
        sql"SELECT qty FROM forecast_entry WHERE branch_company_id=$acct AND product_variant_id=$v AND superseded_by IS NULL"
          .query[Int]
          .unique
          .transact(xa)
      superseded <-
        sql"SELECT count(*) FROM forecast_entry WHERE branch_company_id=$acct AND superseded_by IS NOT NULL"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(r1 == Right(1)) and expect(r2 == Right(1)) and expect(r3 == Right(0)) and
      expect(total == 2L) and expect(current == 140) and expect(superseded == 1L)
  }

  test("submit is own-scope: a non-owner gets not_owner; a closed cycle gets cycle_closed") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      owner    <- user(xa); other <- user(xa)
      acct     <- party(xa, "installer", List("forecastable"), market, channel, "retail", Some(owner), None)
      v        <- variant(xa); sc <- scenarioP50(xa)
      cyc      <- freshOpen(svc, xa, LocalDate.of(2026, 6, 15)) // W25
      notOwner <- svc.submit(other, acct, cyc, List(ForecastLine(v, month, sc, 10)), None)
      _        <- svc.closeCycle(cyc)
      closed   <- svc.submit(owner, acct, cyc, List(ForecastLine(v, month, sc, 10)), None)
    } yield expect(notOwner == Left("not_owner")) and expect(closed == Left("cycle_closed"))
  }

  test("bottom-up rollup reconciles in the DB: Σ branch-axis == Σ agent-axis == the market row") { xa =>
    val svc    = new ForecastService[IO](xa)
    val proj   = new CoverageProjector[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agentA <- user(xa); agentB                <- user(xa)
      master <- party(xa, "wholesaler", List("forecastable"), market, channel, "wholesale", None, None)
      leeds  <- party(xa, "branch", List("forecastable"), market, channel, "wholesale", Some(agentA), Some(master))
      york   <- party(xa, "branch", List("forecastable"), market, channel, "wholesale", Some(agentB), Some(master))
      solo   <- party(xa, "installer", List("forecastable"), market, channel, "retail", Some(agentA), None)
      v      <- variant(xa); sc <- scenarioP50(xa)
      cyc    <- freshOpen(svc, xa, LocalDate.of(2026, 6, 22)) // W26
      _      <- svc.submit(agentA, leeds, cyc, List(ForecastLine(v, month, sc, 120)), None)
      _      <- svc.submit(agentB, york, cyc, List(ForecastLine(v, month, sc, 80)), None)
      _      <- svc.submit(agentA, solo, cyc, List(ForecastLine(v, month, sc, 50)), None)
      n1     <- proj.recompute(market, month, sc)
      n2     <- proj.recompute(market, month, sc) // redeliver — must be a no-op (same end state)
      branchSum <-
        sql"SELECT COALESCE(SUM(forecast_qty),0) FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='branch' AND product_variant_id IS NULL"
          .query[Long]
          .unique
          .transact(xa)
      agentSum <-
        sql"SELECT COALESCE(SUM(forecast_qty),0) FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='agent' AND product_variant_id IS NULL"
          .query[Long]
          .unique
          .transact(xa)
      marketRow <-
        sql"SELECT forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='market' AND product_variant_id IS NULL"
          .query[Int]
          .unique
          .transact(xa)
      agentArow <-
        sql"SELECT forecast_qty FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc AND level='agent' AND agent_user_id=$agentA AND product_variant_id IS NULL"
          .query[Int]
          .unique
          .transact(xa)
      rowCount <-
        sql"SELECT count(*) FROM pipeline_coverage WHERE market_id=$market AND period_month=$month AND scenario_id=$sc"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(branchSum == 250L) and expect(agentSum == 250L) and expect(marketRow == 250) and
      expect(agentArow == 170) and expect(n1 == n2) and expect(rowCount.toInt == n1)
  }

  test("who-owes: outstanding/submitted/skipped per owner for the open cycle") { xa =>
    val svc    = new ForecastService[IO](xa)
    val market = UUID.randomUUID(); val channel = UUID.randomUUID()
    for {
      agent <- user(xa)
      a1    <- party(xa, "installer", List("forecastable"), market, channel, "retail", Some(agent), None)
      _     <- party(xa, "installer", List("forecastable"), market, channel, "retail", Some(agent), None)
      v     <- variant(xa); sc <- scenarioP50(xa)
      cyc   <- freshOpen(svc, xa, LocalDate.of(2026, 6, 29)) // W27
      _     <- svc.submit(agent, a1, cyc, List(ForecastLine(v, month, sc, 30)), None)
      owes  <- ForecastRepo.outstanding(cyc).transact(xa).map(_.find(_._1 == agent))
    } yield expect(owes.exists { case (_, outstanding, submitted, _) => outstanding == 1L && submitted == 1L })
  }
}
