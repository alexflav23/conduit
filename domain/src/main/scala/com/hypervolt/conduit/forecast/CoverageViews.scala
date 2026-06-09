package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The H6Q presentation views the spreadsheet had (doc 26; the user's brief): per-MARKET and GLOBAL, with SECTOR
// attribution (energy / retail / wholesale / installers / …) — now backed by the live projection instead of cells.
// GLOBAL and cross-market sector views are READ-TIME aggregations over the per-market pipeline_coverage rows: a
// market recompute only knows its own slice, and keeping markets separate is precisely what lets each market carry
// its own seasonality. Quantities sum; coverage % is recomputed from summed components, never averaged.
final class CoverageViewsService[F[_]: Async](xa: Transactor[F]) {

  // One row per market + the derived GLOBAL row (market_id NULL), for a (period, scenario).
  def perMarketAndGlobal(period: LocalDate, scenario: UUID): F[Json] =
    marketRows(period, scenario)
      .map { rows =>
        val global = Json.obj(
          "market_id"     -> Json.Null,
          "level"         -> "global".asJson,
          "forecast_qty"  -> rows.map(_._2).sum.asJson,
          "shipped_qty"   -> rows.map(_._3).sum.asJson,
          "activated_qty" -> rows.map(_._4).sum.asJson,
          "coverage_pct"  -> Coverage.ratio(rows.map(_._3).sum, BigDecimal(0), rows.map(_._2).sum).map(_.toString).asJson
        )
        Json.obj(
          "period_month" -> period.toString.asJson,
          "markets" -> Json.fromValues(rows.map {
            case (mkt, f, s, a) =>
              Json.obj(
                "market_id"     -> mkt.toString.asJson,
                "forecast_qty"  -> f.asJson,
                "shipped_qty"   -> s.asJson,
                "activated_qty" -> a.asJson,
                "coverage_pct"  -> Coverage.ratio(s, BigDecimal(0), f).map(_.toString).asJson
              )
          }),
          "global" -> global
        )
      }
      .transact(xa)

  // Sector attribution (doc 24 §5.8): per market, and aggregated globally per sector — energy vs retail vs
  // wholesale vs installers, each drillable to the account level via the company rows underneath.
  def sectors(period: LocalDate, scenario: UUID): F[Json] =
    sectorRows(period, scenario)
      .map { rows =>
        val global = rows
          .groupBy(_._2)
          .toList
          .map {
            case (sector, grp) =>
              Json.obj(
                "sector"       -> sector.getOrElse("unclassified").asJson,
                "forecast_qty" -> grp.map(_._3).sum.asJson,
                "shipped_qty"  -> grp.map(_._4).sum.asJson,
                "coverage_pct" -> Coverage
                  .ratio(grp.map(_._4).sum, BigDecimal(0), grp.map(_._3).sum)
                  .map(_.toString)
                  .asJson
              )
          }
        Json.obj(
          "period_month" -> period.toString.asJson,
          "per_market" -> Json.fromValues(rows.map {
            case (mkt, sector, f, s) =>
              Json.obj(
                "market_id"    -> mkt.toString.asJson,
                "sector"       -> sector.getOrElse("unclassified").asJson,
                "forecast_qty" -> f.asJson,
                "shipped_qty"  -> s.asJson
              )
          }),
          "global" -> Json.fromValues(global)
        )
      }
      .transact(xa)

  // The MONEY overlay (doc 26 §2): the same coverage tree expressed as net revenue — each account×SKU's forecast
  // priced at the customer's authorized tier net of the expected retrospective rebate, summed per sector and
  // globally. Read-time, contract-consistent, never stored (doc 12 §8.3 — H6Q doesn't own money).
  def netRevenueBySector(
      period: LocalDate,
      scenario: UUID,
      channel: UUID,
      currency: String,
      asOf: Instant
  ): F[Json] =
    companySkuRows(period, scenario)
      .flatMap { rows =>
        rows
          .traverse {
            case (market, sector, company, variant, cls, qty) =>
              (
                RevenueProjection.netUnitPrice(company, variant, cls, channel, market, currency, qty, asOf)
              ).map(net => (market, sector, (net * qty).setScale(2, RoundingMode.HALF_UP)))
          }
          .map { priced =>
            val bySector = priced.groupBy(_._2).toList.map {
              case (sector, grp) =>
                Json.obj(
                  "sector"           -> sector.getOrElse("unclassified").asJson,
                  "forecast_revenue" -> grp.map(_._3).sum.toString.asJson
                )
            }
            val byMarket = priced.groupBy(_._1).toList.map {
              case (market, grp) =>
                Json.obj(
                  "market_id"        -> market.toString.asJson,
                  "forecast_revenue" -> grp.map(_._3).sum.toString.asJson
                )
            }
            Json.obj(
              "period_month" -> period.toString.asJson,
              "currency"     -> currency.asJson,
              "by_sector"    -> Json.fromValues(bySector),
              "by_market"    -> Json.fromValues(byMarket),
              "global"       -> priced.map(_._3).sum.toString.asJson
            )
          }
      }
      .transact(xa)

  private def marketRows(period: LocalDate, scenario: UUID): ConnectionIO[List[(UUID, Int, Int, Int)]] =
    sql"""SELECT market_id, forecast_qty, shipped_qty, activated_qty FROM pipeline_coverage
          WHERE level = 'market' AND product_variant_id IS NULL
            AND period_month = $period AND scenario_id = $scenario
          ORDER BY market_id"""
      .query[(UUID, Int, Int, Int)]
      .to[List]

  private def sectorRows(period: LocalDate, scenario: UUID): ConnectionIO[List[(UUID, Option[String], Int, Int)]] =
    sql"""SELECT market_id, sector, forecast_qty, shipped_qty FROM pipeline_coverage
          WHERE level = 'sector' AND product_variant_id IS NULL
            AND period_month = $period AND scenario_id = $scenario
          ORDER BY market_id, sector"""
      .query[(UUID, Option[String], Int, Int)]
      .to[List]

  private def companySkuRows(
      period: LocalDate,
      scenario: UUID
  ): ConnectionIO[List[(UUID, Option[String], UUID, UUID, String, Int)]] =
    sql"""SELECT pc.market_id, p.sector, pc.company_id, pc.product_variant_id, pv.product_class, pc.forecast_qty
          FROM pipeline_coverage pc
            JOIN party p ON p.id = pc.company_id
            JOIN product_variant pv ON pv.id = pc.product_variant_id
          WHERE pc.level = 'company' AND pc.product_variant_id IS NOT NULL
            AND pc.period_month = $period AND pc.scenario_id = $scenario AND pc.forecast_qty > 0"""
      .query[(UUID, Option[String], UUID, UUID, String, Int)]
      .to[List]
}

// The shared net-unit-price (extracted shape of RevenueProjectionService): the customer's authorized tier price
// minus the expected per-unit retrospective rebate. ConnectionIO so the overlay prices inside one transaction.
object RevenueProjection {

  import com.hypervolt.conduit.pricing.PricingService
  import com.hypervolt.conduit.pricing.RebateEngine
  import com.hypervolt.conduit.pricing.RebateRepo
  import com.hypervolt.conduit.pricing.TierResolver

  def netUnitPrice(
      company: UUID,
      variant: UUID,
      productClass: String,
      channel: UUID,
      market: UUID,
      currency: String,
      qty: Int,
      asOf: Instant
  ): ConnectionIO[BigDecimal] =
    priceAndRebate(company, variant, productClass, channel, market, currency, qty, asOf).map {
      case (price, rebate) => (price.getOrElse(BigDecimal(0)) - rebate).max(BigDecimal(0))
    }

  // (the authorized tier price, the expected per-unit retrospective rebate) — the decomposition the per-account
  // projection displays and the rollup overlay nets.
  def priceAndRebate(
      company: UUID,
      variant: UUID,
      productClass: String,
      channel: UUID,
      market: UUID,
      currency: String,
      qty: Int,
      asOf: Instant
  ): ConnectionIO[(Option[BigDecimal], BigDecimal)] =
    (
      TierResolver
        .candidates(variant, productClass, channel, market, None, currency, qty.max(1), Some(company), asOf)
        .map(PricingService.resolve(_, channel, market, None).map(_.exVat)),
      rebatePerUnit(company, variant)
    ).tupled

  private def rebatePerUnit(company: UUID, variant: UUID): ConnectionIO[BigDecimal] =
    retroAgreements(company, variant).flatMap {
      _.traverse { agreementId =>
        (RebateRepo.ladder(agreementId, variant), RebateRepo.commitment(agreementId)).mapN { (ladder, commitment) =>
          val tiers = ladder.map { case (q, p) => RebateEngine.Tier(q, p) }
          (RebateEngine.entryPrice(tiers), RebateEngine.achievedPrice(tiers, commitment.getOrElse(0)))
            .mapN(_ - _)
            .getOrElse(BigDecimal(0))
            .max(BigDecimal(0))
        }
      }.map(_.maxOption.getOrElse(BigDecimal(0)))
    }

  private def retroAgreements(company: UUID, variant: UUID): ConnectionIO[List[UUID]] =
    sql"""SELECT DISTINCT pa.id
          FROM price_agreement pa
          JOIN price_agreement_customer pac ON pac.agreement_id = pa.id
          JOIN price_rule pr ON pr.price_agreement_id = pa.id
          WHERE pac.party_id = $company AND pr.product_variant_id = $variant
            AND pa.base_volume_basis = 'cumulative_retrospective' AND pa.status = 'active'"""
      .query[UUID]
      .to[List]
}
