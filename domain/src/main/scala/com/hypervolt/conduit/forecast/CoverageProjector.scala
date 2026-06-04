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
      rows = Coverage.rollup(leaves)
      _ <- deleteSlice(market, period, scenario)
      _ <- rows.traverse_(insertRow(_, period, scenario))
      marketRow   = rows.find(_.level == "market")
      newForecast = marketRow.map(_.forecastQty).getOrElse(0)
      coverage    = marketRow.flatMap(_.coveragePct)
      _ <- OutboxRepo.append(coverageUpdatedEvent(market, period, scenario, prior, newForecast, coverage))
      _ <- NotificationRepo.fanoutCoverageUpdated(market, period, scenario, prior, newForecast, coverage)
    } yield rows.size).transact(xa)

  private def marketForecast(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"""SELECT COALESCE(forecast_qty, 0) FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario AND level = 'market'"""
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

  // Current manual leaves for the slice, summed across variants to a per-branch leaf (the coverage grain), with
  // shipped/activated actuals merged in per branch.
  private def readLeaves(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[Leaf]] =
    (forecastLeaves(market, period, scenario), shippedByBranch(market, period), activatedByAccount(period)).tupled.map {
      case (leaves, shipped, activated) =>
        leaves.map { l =>
          val br = l.branchId.getOrElse(l.companyId)
          l.copy(shippedQty = shipped.getOrElse(br, 0), activatedQty = activated.getOrElse(br, 0))
        }
    }

  private def forecastLeaves(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[Leaf]] =
    sql"""SELECT market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id,
                 forecaster_user_id, SUM(qty)::int AS forecast_qty
          FROM forecast_entry
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario
            AND superseded_by IS NULL AND source = 'manual'
            AND branch_company_id IS NOT NULL AND company_id IS NOT NULL AND forecaster_user_id IS NOT NULL
          GROUP BY market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id, forecaster_user_id"""
      .query[(UUID, Option[UUID], Option[UUID], Option[String], UUID, UUID, UUID, Int)]
      .to[List]
      .map(_.map {
        case (mkt, ch, sub, seg, co, br, ag, fq) =>
          Leaf(mkt, ch, sub, seg, co, Some(br), ag, period, scenario, fq, BigDecimal(0), 0, 0, fq, "manual")
      })

  // Sell-in: units dispatched to the account in the period (doc 12 §4.3).
  private def shippedByBranch(market: UUID, period: LocalDate): ConnectionIO[Map[UUID, Int]] =
    sql"""SELECT o.sold_to_party_id, COALESCE(SUM(dl.qty),0)::int
          FROM dispatch d
            JOIN "order" o ON o.id = d.order_id
            JOIN dispatch_line dl ON dl.dispatch_id = d.id
          WHERE o.market_id = $market AND date_trunc('month', d.date)::date = $period
          GROUP BY o.sold_to_party_id"""
      .query[(UUID, Int)]
      .to[List]
      .map(_.toMap)

  // Sell-through: v3 activations bound to the account in the period (V2/V3 rule — v2 excluded, doc 12 §7.2).
  private def activatedByAccount(period: LocalDate): ConnectionIO[Map[UUID, Int]] =
    sql"""SELECT s.company_id, COUNT(*)::int
          FROM activation a JOIN serial_unit s ON s.serial_no = a.serial
          WHERE s.generation = 'v3' AND s.company_id IS NOT NULL
            AND date_trunc('month', a.activated_at)::date = $period
          GROUP BY s.company_id"""
      .query[(UUID, Int)]
      .to[List]
      .map(_.toMap)

  private def deleteSlice(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"DELETE FROM pipeline_coverage WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario".update.run

  private def insertRow(r: CoverageRow, period: LocalDate, scenario: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO pipeline_coverage
            (level, channel_id, sub_channel_id, segment, company_id, branch_company_id, agent_user_id, market_id,
             period_month, scenario_id, forecast_qty, weighted_pipeline_qty, shipped_qty, activated_qty,
             coverage_pct, forecast_qty_ex, coverage_ex_account_pct, forecast_source)
          VALUES (${r.level}, ${r.channelId}, ${r.subChannelId}, ${r.segment}, ${r.companyId}, ${r.branchId},
             ${r.agentUserId}, ${r.marketId}, $period, $scenario, ${r.forecastQty}, ${r.weightedPipelineQty},
             ${r.shippedQty}, ${r.activatedQty}, ${r.coveragePct}, ${r.forecastQtyEx}, ${r.coverageExAccountPct},
             ${r.forecastSource})""".update.run
}
