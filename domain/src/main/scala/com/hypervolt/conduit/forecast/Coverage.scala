package com.hypervolt.conduit.forecast

import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The atomic forecast leaf (doc 12 §4.2): one branch/account's resolved current estimate for a
// (market, channel, sub_channel, segment, company, branch, agent, period_month, scenario) with its coverage
// components. Both the org axis and the agent axis are rolled up from the SAME leaves — that shared origin is
// exactly why the two axes reconcile (doc 12 §4.4).
final case class Leaf(
    marketId: UUID,
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    companyId: UUID,
    branchId: Option[UUID],
    agentUserId: UUID,
    periodMonth: LocalDate,
    scenarioId: UUID,
    forecastQty: Int,
    weightedPipelineQty: BigDecimal,
    shippedQty: Int,
    activatedQty: Int,
    forecastQtyEx: Int, // forecast with the scenario's ex-cut account removed (doc 12 §5.2)
    source: String
)

// A rolled-up coverage row at one level of the hierarchy (or the agent axis). Quantities sum on rollup;
// coverage_pct is RECOMPUTED from the summed components, never averaged (doc 12 §4.4).
final case class CoverageRow(
    level: String,
    marketId: Option[UUID],
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    companyId: Option[UUID],
    branchId: Option[UUID],
    agentUserId: Option[UUID],
    periodMonth: LocalDate,
    scenarioId: UUID,
    forecastQty: Int,
    weightedPipelineQty: BigDecimal,
    shippedQty: Int,
    activatedQty: Int,
    forecastQtyEx: Int,
    forecastSource: String,
    wowDelta: Option[BigDecimal] = None
) {
  // (shipped + weighted_pipeline) / forecast — None when forecast is 0 (the 0-forecast guard, doc 12 §4.3).
  def coveragePct: Option[BigDecimal]          = Coverage.ratio(shippedQty, weightedPipelineQty, forecastQty)
  def coverageExAccountPct: Option[BigDecimal] = Coverage.ratio(shippedQty, weightedPipelineQty, forecastQtyEx)
}

object Coverage {

  def ratio(shipped: Int, weightedPipeline: BigDecimal, forecast: Int): Option[BigDecimal] =
    if (forecast == 0) None
    else Some(((BigDecimal(shipped) + weightedPipeline) / BigDecimal(forecast)).setScale(4, RoundingMode.HALF_UP))

  // The full bottom-up rollup. From the leaves it produces a row at every ORG level
  // (branch → company → segment → sub_channel → channel → market) AND a row per AGENT — all summing the same
  // leaves. The reconciliation guarantee (doc 12 §4.4): within any (market, period, scenario),
  // Σ branch-level == Σ agent-level == the market row, on every quantity.
  def rollup(leaves: List[Leaf]): List[CoverageRow] = {
    val branch = roll(
      leaves,
      "branch",
      l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, Some(l.companyId), l.branchId, None)
    )
    val company = roll(
      leaves,
      "company",
      l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, Some(l.companyId), None, None)
    )
    val segment =
      roll(leaves, "segment", l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, None, None, None))
    val subChannel =
      roll(leaves, "sub_channel", l => Key(Some(l.marketId), l.channelId, l.subChannelId, None, None, None, None))
    val channel = roll(leaves, "channel", l => Key(Some(l.marketId), l.channelId, None, None, None, None, None))
    val market  = roll(leaves, "market", l => Key(Some(l.marketId), None, None, None, None, None, None))
    val agent   = roll(leaves, "agent", l => Key(Some(l.marketId), None, None, None, None, None, Some(l.agentUserId)))
    branch ::: company ::: segment ::: subChannel ::: channel ::: market ::: agent
  }

  private final case class Key(
      marketId: Option[UUID],
      channelId: Option[UUID],
      subChannelId: Option[UUID],
      segment: Option[String],
      companyId: Option[UUID],
      branchId: Option[UUID],
      agentUserId: Option[UUID]
  )

  private def roll(leaves: List[Leaf], level: String, key: Leaf => Key): List[CoverageRow] =
    leaves
      .groupBy(l => (key(l), l.periodMonth, l.scenarioId))
      .toList
      .map {
        case ((k, period, scenario), group) =>
          CoverageRow(
            level = level,
            marketId = k.marketId,
            channelId = k.channelId,
            subChannelId = k.subChannelId,
            segment = k.segment,
            companyId = k.companyId,
            branchId = k.branchId,
            agentUserId = k.agentUserId,
            periodMonth = period,
            scenarioId = scenario,
            forecastQty = group.map(_.forecastQty).sum,
            weightedPipelineQty = group.map(_.weightedPipelineQty).foldLeft(BigDecimal(0))(_ + _),
            shippedQty = group.map(_.shippedQty).sum,
            activatedQty = group.map(_.activatedQty).sum,
            forecastQtyEx = group.map(_.forecastQtyEx).sum,
            forecastSource = sourceOf(group)
          )
      }

  // manual / hyperview / mixed — what backed this rolled-up key (doc 12 §6).
  private def sourceOf(group: List[Leaf]): String =
    group.map(_.source).distinct match {
      case one :: Nil => one
      case _          => "mixed"
    }
}
