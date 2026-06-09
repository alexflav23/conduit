package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.forecast.CoverageProjector
import com.hypervolt.conduit.forecast.CoverageViewsService
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// The H6Q spreadsheet's presentation views recreated on the live projection (doc 26; the product owner's brief):
// SECTOR attribution (energy / installers / …) reconciling with the market row; per-MARKET + derived GLOBAL
// (markets keep their own seasonality — global is read-time aggregation, never a stored blend); and the MONEY
// overlay — net-of-rebate, contract-consistent revenue per sector/market/global, drillable to the account.
object H6QViewsSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()
  private val period  = LocalDate.of(2026, 8, 1)

  private def seedVariant(xa: HikariTransactor[IO]): IO[UUID] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation, product_class) VALUES ($fam,$sku,'g3','charger') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
              VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 600.00, 1, 'active')""".update.run
    } yield vid).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String, sector: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ($n,'wholesaler',true,$sector) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def scenario(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE is_default = true LIMIT 1".query[UUID].unique.transact(xa)

  private def entry(
      xa: HikariTransactor[IO],
      market: UUID,
      company: UUID,
      vid: UUID,
      scen: UUID,
      qty: Int
  ): IO[Unit] =
    sql"""INSERT INTO forecast_entry
            (market_id, channel_id, segment, company_id, branch_company_id, forecaster_user_id,
             product_variant_id, period_month, scenario_id, qty, source)
          VALUES ($market, $channel, 'trade', $company, $company, ${UUID.randomUUID()},
             $vid, $period, $scen, $qty, 'manual')""".update.run.void.transact(xa)

  test("sector attribution reconciles; global derives from per-market rows; the money view is net of rebates") { xa =>
    val projector = new CoverageProjector[IO](xa)
    val views     = new CoverageViewsService[IO](xa)
    val agr       = new AgreementService[IO](xa)
    val marketUk  = UUID.randomUUID()
    val marketIe  = UUID.randomUUID()
    for {
      vid      <- seedVariant(xa)
      scen     <- scenario(xa)
      octopus  <- party(xa, "Octopus Energy", "energy")        // UK, energy — with a retro rebate agreement
      sparky   <- party(xa, "Sparky Installers", "installers") // UK, installers — open list
      ieEnergy <- party(xa, "IE Energy Co", "energy")          // IE, energy — open list
      // a retrospective agreement for Octopus: entry 600, commitment 500 → tier 520 → expected rebate 80/unit
      agreement <- agr.request(
        TierRequest(
          "Octopus H6Q",
          "GBP",
          List(octopus),
          List(
            TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
            TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
            TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
          ),
          Instant.now().minusSeconds(3600),
          None,
          "cumulative_retrospective",
          Json.obj("min_commitment_units" -> Json.fromInt(500)),
          None,
          UUID.randomUUID()
        )
      )
      _ <- agr.activate(agreement, UUID.randomUUID())
      // forecasts: UK energy 100, UK installers 50; IE energy 40
      _ <- entry(xa, marketUk, octopus, vid, scen, 100)
      _ <- entry(xa, marketUk, sparky, vid, scen, 50)
      _ <- entry(xa, marketIe, ieEnergy, vid, scen, 40)
      _ <- projector.recompute(marketUk, period, scen)
      _ <- projector.recompute(marketIe, period, scen)
      // sector rows reconcile with the market row, per market
      ukSectorSum <- sql"""SELECT COALESCE(SUM(forecast_qty),0) FROM pipeline_coverage
                WHERE level='sector' AND market_id=$marketUk AND period_month=$period AND scenario_id=$scen
                  AND product_variant_id IS NULL""".query[Int].unique.transact(xa)
      ukMarket    <- sql"""SELECT forecast_qty FROM pipeline_coverage
                WHERE level='market' AND market_id=$marketUk AND period_month=$period AND scenario_id=$scen
                  AND product_variant_id IS NULL""".query[Int].unique.transact(xa)
      // per-market + derived global
      global <- views.perMarketAndGlobal(period, scen)
      // sector view, global across markets
      sectors <- views.sectors(period, scen)
      // the money overlay: Octopus 100 × (600−80) = 52,000 ; Sparky 50 × 600 = 30,000 ; IE 40 × 600 = 24,000
      money <- views.netRevenueBySector(period, scen, channel, "GBP", Instant.now())
      gQty         = global.hcursor.downField("global").get[Int]("forecast_qty").toOption
      sectorGlobal = sectors.hcursor.downField("global").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      energyQty =
        sectorGlobal
          .find(_.hcursor.get[String]("sector").contains("energy"))
          .flatMap(_.hcursor.get[Int]("forecast_qty").toOption)
      bySector = money.hcursor.downField("by_sector").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      energyRev =
        bySector
          .find(_.hcursor.get[String]("sector").contains("energy"))
          .flatMap(_.hcursor.get[String]("forecast_revenue").toOption)
      installersRev =
        bySector
          .find(_.hcursor.get[String]("sector").contains("installers"))
          .flatMap(_.hcursor.get[String]("forecast_revenue").toOption)
      totalRev = money.hcursor.get[String]("global").toOption
    } yield expect(ukSectorSum == ukMarket) and expect(ukMarket == 150) and // Σ sector ≡ market (reconciliation)
      expect(gQty.contains(190)) and                                        // global = UK 150 + IE 40, derived
      expect(energyQty.contains(140)) and                                   // energy across markets: 100 + 40
      expect(energyRev.exists(BigDecimal(_) == BigDecimal(76000))) and      // 100×520 + 40×600 — net of rebate
      expect(installersRev.exists(BigDecimal(_) == BigDecimal(30000))) and  // 50×600
      expect(totalRev.exists(BigDecimal(_) == BigDecimal(106000)))          // the global money row
  }
}
