package com.hypervolt.conduit.forecast

import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The atomic forecast leaf (doc 12 §4.2): one branch/account's resolved current estimate for ONE SKU in a
// (market, channel, sub_channel, segment, company, branch, agent, product_variant, period_month, scenario), with
// its coverage components. SKU granularity is load-bearing — you can't cover a Home 3 Pro forecast with a
// Home 2.2 shipment, so coverage must be computed per variant. Both the org axis and the agent axis roll up from
// the SAME leaves, which is why the two reconcile (doc 12 §4.4).
final case class Leaf(
    marketId: UUID,
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    companyId: UUID,
    branchId: Option[UUID],
    agentUserId: UUID,
    productVariantId: UUID,
    periodMonth: LocalDate,
    scenarioId: UUID,
    forecastQty: Int,
    weightedPipelineQty: BigDecimal,
    shippedQty: Int,
    activatedQty: Int,
    source: String,
    excluded: Boolean = false // true when this leaf's account is removed by the scenario's ex-cut (doc 12 §5.2)
)

// A rolled-up coverage row at one level of the hierarchy (or the agent axis), at a SKU (productVariantId set) or
// as the all-SKU total (productVariantId None). Quantities sum on rollup; coverage_pct is RECOMPUTED from the
// summed components, never averaged (doc 12 §4.4).
final case class CoverageRow(
    level: String,
    marketId: Option[UUID],
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    companyId: Option[UUID],
    branchId: Option[UUID],
    agentUserId: Option[UUID],
    productVariantId: Option[UUID],
    periodMonth: LocalDate,
    scenarioId: UUID,
    forecastQty: Int,
    weightedPipelineQty: BigDecimal,
    shippedQty: Int,
    activatedQty: Int,
    forecastQtyEx: Int,
    weightedPipelineQtyEx: BigDecimal,
    shippedQtyEx: Int,
    forecastSource: String,
    wowDelta: Option[BigDecimal] = None
) {
  // (shipped + weighted_pipeline) / forecast — None when forecast is 0 (the 0-forecast guard, doc 12 §4.3).
  def coveragePct: Option[BigDecimal] = Coverage.ratio(shippedQty, weightedPipelineQty, forecastQty)
  // The ex-cut figure (doc 12 §5.2): the named account removed from BOTH forecast and the covering components.
  def coverageExAccountPct: Option[BigDecimal] = Coverage.ratio(shippedQtyEx, weightedPipelineQtyEx, forecastQtyEx)
}

object Coverage {

  def ratio(shipped: Int, weightedPipeline: BigDecimal, forecast: Int): Option[BigDecimal] =
    if (forecast == 0) None
    else Some(((BigDecimal(shipped) + weightedPipeline) / BigDecimal(forecast)).setScale(4, RoundingMode.HALF_UP))

  // The all-SKU rollup: a row at every ORG level (branch → company → segment → sub_channel → channel → market)
  // AND a row per AGENT, summed ACROSS SKUs (productVariantId None). The reconciliation guarantee (doc 12 §4.4):
  // within any (market, period, scenario), Σ branch == Σ agent == the market row, on every quantity.
  def rollup(leaves: List[Leaf]): List[CoverageRow] = roll(leaves, _ => None)

  // The per-SKU rollup: the same levels, but keyed additionally by product_variant — so coverage exists per SKU
  // and reconciles per SKU. Σ over SKUs of a per-SKU row == the matching all-SKU row.
  def rollupBySku(leaves: List[Leaf]): List[CoverageRow] = roll(leaves, l => Some(l.productVariantId))

  private def roll(leaves: List[Leaf], variantOf: Leaf => Option[UUID]): List[CoverageRow] = {
    def at(level: String, key: Leaf => Key): List[CoverageRow] = group(leaves, level, key, variantOf)
    at(
      "branch",
      l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, Some(l.companyId), l.branchId, None)
    ) :::
      at(
        "company",
        l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, Some(l.companyId), None, None)
      ) :::
      at("segment", l => Key(Some(l.marketId), l.channelId, l.subChannelId, l.segment, None, None, None)) :::
      at("sub_channel", l => Key(Some(l.marketId), l.channelId, l.subChannelId, None, None, None, None)) :::
      at("channel", l => Key(Some(l.marketId), l.channelId, None, None, None, None, None)) :::
      at("market", l => Key(Some(l.marketId), None, None, None, None, None, None)) :::
      at("agent", l => Key(Some(l.marketId), None, None, None, None, None, Some(l.agentUserId)))
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

  private def group(
      leaves: List[Leaf],
      level: String,
      key: Leaf => Key,
      variantOf: Leaf => Option[UUID]
  ): List[CoverageRow] =
    leaves
      .groupBy(l => (key(l), variantOf(l), l.periodMonth, l.scenarioId))
      .toList
      .map {
        case ((k, variant, period, scenario), grp) =>
          CoverageRow(
            level = level,
            marketId = k.marketId,
            channelId = k.channelId,
            subChannelId = k.subChannelId,
            segment = k.segment,
            companyId = k.companyId,
            branchId = k.branchId,
            agentUserId = k.agentUserId,
            productVariantId = variant,
            periodMonth = period,
            scenarioId = scenario,
            forecastQty = grp.map(_.forecastQty).sum,
            weightedPipelineQty = grp.map(_.weightedPipelineQty).foldLeft(BigDecimal(0))(_ + _),
            shippedQty = grp.map(_.shippedQty).sum,
            activatedQty = grp.map(_.activatedQty).sum,
            forecastQtyEx = grp.filterNot(_.excluded).map(_.forecastQty).sum,
            weightedPipelineQtyEx = grp.filterNot(_.excluded).map(_.weightedPipelineQty).foldLeft(BigDecimal(0))(_ + _),
            shippedQtyEx = grp.filterNot(_.excluded).map(_.shippedQty).sum,
            forecastSource = sourceOf(grp)
          )
      }

  // manual / hyperview / mixed — what backed this rolled-up key (doc 12 §6).
  private def sourceOf(group: List[Leaf]): String =
    group.map(_.source).distinct match {
      case one :: Nil => one
      case _          => "mixed"
    }
}
