package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.notification.NotificationRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Rebuilds the materialised pipeline_coverage projection for one (market, period, scenario) slice from the
// current (non-superseded) forecast leaves (doc 12 §4.2). It is the forecast.submitted consumer's effect; it is
// deterministic and replayable — recomputing the whole slice means redelivery is a no-op (same end state). The
// org axis AND the agent axis are written from the same leaves, so they reconcile by construction (doc 12 §4.4).
//
// Actuals are joined per branch: shipped from dispatched orders (sell-in), activated from v3 activations bound
// to the account (sell-through, the V2/V3 rule, doc 12 §7). weighted_pipeline awaits the deal/pipeline tables
// and stays 0 for now — the rollup math already carries it.
final class CoverageProjector[F[_]: Async](xa: Transactor[F]) {

  // Recompute the slice AND propagate the shift: a recompute means forward visibility moved, so we emit
  // forecast.coverage.updated (for the Pulsar consumer / external systems) and fan it out to subscribers — the
  // exec, account owners, and external partners like our contract manufacturer — all in ONE transaction with
  // the projection write (doc 12 §2.6, doc 10 §B). "H6Q updated" therefore reaches whoever needs to know.
  def recompute(market: UUID, period: LocalDate, scenario: UUID): F[Int] =
    (for {
      prior  <- marketForecast(market, period, scenario)
      leaves <- readLeaves(market, period, scenario)
      // Both granularities: the all-SKU total (productVariantId None) AND the per-SKU breakdown.
      rows = Coverage.rollup(leaves) ::: Coverage.rollupBySku(leaves)
      _ <- deleteSlice(market, period, scenario)
      // WoW: the all-SKU market row carries the movement since the last recompute (doc 12 §4.5).
      withWow = rows.map(r =>
        if (r.level == "market" && r.productVariantId.isEmpty)
          r.copy(wowDelta = Some(BigDecimal(r.forecastQty - prior)))
        else r
      )
      _ <- withWow.traverse_(insertRow(_, period, scenario))
      marketRow   = rows.find(r => r.level == "market" && r.productVariantId.isEmpty)
      newForecast = marketRow.map(_.forecastQty).getOrElse(0)
      coverage    = marketRow.flatMap(_.coveragePct)
      _ <- OutboxRepo.append(coverageUpdatedEvent(market, period, scenario, prior, newForecast, coverage))
      _ <- NotificationRepo.fanoutCoverageUpdated(market, period, scenario, prior, newForecast, coverage)
    } yield rows.size).transact(xa)

  private def marketForecast(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"""SELECT COALESCE(forecast_qty, 0) FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario
            AND level = 'market' AND product_variant_id IS NULL"""
      .query[Int]
      .option
      .map(_.getOrElse(0))

  private def coverageUpdatedEvent(
      market: UUID,
      period: LocalDate,
      scenario: UUID,
      prior: Int,
      now: Int,
      coverage: Option[BigDecimal]
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "forecast.coverage.updated",
      1,
      "forecast",
      market,
      s"$market:$period:$scenario",
      Some(Json.obj("market_id" -> market.toString.asJson)),
      None,
      None,
      Json.obj(
        "market_id"      -> market.toString.asJson,
        "period_month"   -> period.toString.asJson,
        "scenario_id"    -> scenario.toString.asJson,
        "prior_forecast" -> prior.asJson,
        "new_forecast"   -> now.asJson,
        "delta"          -> (now - prior).asJson,
        "coverage_pct"   -> coverage.asJson
      ),
      Instant.now()
    )

  // Current leaves for the slice at the (branch, SKU) grain, with shipped/activated actuals merged per (branch, SKU).
  private def readLeaves(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[Leaf]] =
    (
      forecastLeaves(market, period, scenario),
      shippedByBranchVariant(market, period),
      activatedByAccountVariant(period)
    ).tupled.map {
      case (leaves, shipped, activated) =>
        leaves.map { l =>
          val br = l.branchId.getOrElse(l.companyId)
          l.copy(
            shippedQty = shipped.getOrElse((br, l.productVariantId), 0),
            activatedQty = activated.getOrElse((br, l.productVariantId), 0)
          )
        }
    }

  // Resolve manual-vs-hyperview precedence per estimate key (doc 12 §6.3, default manual_overrides_hyperview) at
  // the (branch, SKU) grain — coverage exists per SKU. Hyperview rows (no owner) are attributed to a sentinel
  // "model" agent so the agent axis still totals to the branch axis. A (branch, SKU) mixing sources is 'mixed'.
  private def forecastLeaves(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[Leaf]] =
    sql"""WITH cur AS (
            SELECT market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id,
                   forecaster_user_id, product_variant_id, qty, source
            FROM forecast_entry
            WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario
              AND superseded_by IS NULL AND branch_company_id IS NOT NULL AND company_id IS NOT NULL
              AND product_variant_id IS NOT NULL
          ),
          resolved AS (
            SELECT DISTINCT ON (branch_company_id, product_variant_id)
                   market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id,
                   COALESCE(forecaster_user_id, '00000000-0000-0000-0000-000000000000'::uuid) AS agent,
                   product_variant_id, qty, source
            FROM cur
            ORDER BY branch_company_id, product_variant_id, CASE source WHEN 'manual' THEN 0 ELSE 1 END
          )
          SELECT r.market_id, r.channel_id, r.sub_channel_id, r.segment, r.company_id, r.branch_company_id, r.agent,
                 r.product_variant_id, r.qty AS forecast_qty, r.source AS src,
                 (COALESCE((p.attributes->>'h6q_excludable') = 'true', false)
                  OR COALESCE((pp.attributes->>'h6q_excludable') = 'true', false)) AS excluded
          FROM resolved r
            JOIN party p ON p.id = r.branch_company_id
            LEFT JOIN party pp ON pp.id = p.parent_party_id"""
      .query[(UUID, Option[UUID], Option[UUID], Option[String], UUID, UUID, UUID, UUID, Int, String, Boolean)]
      .to[List]
      .map(_.map {
        case (mkt, ch, sub, seg, co, br, ag, variant, fq, src, excluded) =>
          Leaf(mkt, ch, sub, seg, co, Some(br), ag, variant, period, scenario, fq, BigDecimal(0), 0, 0, src, excluded)
      })

  // Sell-in per (account, SKU): units dispatched to the account in the period (doc 12 §4.3).
  private def shippedByBranchVariant(market: UUID, period: LocalDate): ConnectionIO[Map[(UUID, UUID), Int]] =
    sql"""SELECT o.sold_to_party_id, ol.product_variant_id, COALESCE(SUM(dl.qty),0)::int
          FROM dispatch d
            JOIN "order" o ON o.id = d.order_id
            JOIN dispatch_line dl ON dl.dispatch_id = d.id
            JOIN order_line ol ON ol.id = dl.order_line_id
          WHERE o.market_id = $market AND date_trunc('month', d.date)::date = $period
          GROUP BY o.sold_to_party_id, ol.product_variant_id"""
      .query[(UUID, UUID, Int)]
      .to[List]
      .map(_.map { case (b, v, q) => (b, v) -> q }.toMap)

  // Sell-through per (account, SKU): v3 activations bound to the account in the period (V2/V3 rule, doc 12 §7.2).
  private def activatedByAccountVariant(period: LocalDate): ConnectionIO[Map[(UUID, UUID), Int]] =
    sql"""SELECT s.company_id, s.product_variant_id, COUNT(*)::int
          FROM activation a JOIN serial_unit s ON s.serial_no = a.serial
          WHERE s.generation = 'v3' AND s.company_id IS NOT NULL
            AND date_trunc('month', a.activated_at)::date = $period
          GROUP BY s.company_id, s.product_variant_id"""
      .query[(UUID, UUID, Int)]
      .to[List]
      .map(_.map { case (c, v, q) => (c, v) -> q }.toMap)

  private def deleteSlice(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"DELETE FROM pipeline_coverage WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario".update.run

  private def insertRow(r: CoverageRow, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO pipeline_coverage
            (level, channel_id, sub_channel_id, segment, company_id, branch_company_id, agent_user_id, market_id,
             product_variant_id, period_month, scenario_id, forecast_qty, weighted_pipeline_qty, shipped_qty,
             activated_qty, coverage_pct, forecast_qty_ex, coverage_ex_account_pct, forecast_source, wow_delta)
          VALUES (${r.level}, ${r.channelId}, ${r.subChannelId}, ${r.segment}, ${r.companyId}, ${r.branchId},
             ${r.agentUserId}, ${r.marketId}, ${r.productVariantId}, $period, $scenario, ${r.forecastQty},
             ${r.weightedPipelineQty}, ${r.shippedQty}, ${r.activatedQty}, ${r.coveragePct}, ${r.forecastQtyEx},
             ${r.coverageExAccountPct}, ${r.forecastSource}, ${r.wowDelta})""".update.run
}
