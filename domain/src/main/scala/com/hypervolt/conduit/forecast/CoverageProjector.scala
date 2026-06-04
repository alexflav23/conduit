package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

// Rebuilds the materialised pipeline_coverage projection for one (market, period, scenario) slice from the
// current (non-superseded) forecast leaves (doc 12 §4.2). It is the forecast.submitted consumer's effect; it is
// deterministic and replayable — recomputing the whole slice means redelivery is a no-op (same end state). The
// org axis AND the agent axis are written from the same leaves, so they reconcile by construction (doc 12 §4.4).
//
// shipped/weighted_pipeline/activated are sourced from dispatch/deal/activation consumers; until those are wired
// into this slice they are 0 here — the rollup math already carries them, so adding them is a leaf-component
// change, not a structural one.
final class CoverageProjector[F[_]: Async](xa: Transactor[F]) {

  def recompute(market: UUID, period: LocalDate, scenario: UUID): F[Int] =
    (readLeaves(market, period, scenario)
      .flatMap { leaves =>
        val rows = Coverage.rollup(leaves)
        deleteSlice(market, period, scenario) *> rows.traverse_(insertRow(_, period, scenario)).as(rows.size)
      })
      .transact(xa)

  // Current manual leaves for the slice, summed across variants to a per-branch leaf (the coverage grain).
  private def readLeaves(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[Leaf]] =
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
