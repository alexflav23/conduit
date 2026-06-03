package com.hypervolt.conduit.pricing

import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Pricing resolution + ADLP categorisation (doc 04 §Pricing/§ADLP). Pure: candidates come from the repo.
object PricingService {

  private val Epsilon = BigDecimal("0.0001")

  // Specificity: prefer exact channel > null, market > null, entity > null; then volume break (min_qty);
  // then highest version.
  def resolve(candidates: List[PriceRuleCandidate], channel: UUID, market: UUID, entity: Option[UUID]): Option[PriceResolution] = {
    def specificity(c: PriceRuleCandidate): Int =
      List(
        c.channelId.contains(channel),
        c.marketId.contains(market),
        entity.exists(e => c.entityId.contains(e))
      ).count(identity)

    candidates
      .sortBy(c => (-specificity(c), -c.minQty, -c.version))
      .headOption
      .map(c => PriceResolution(c.id, c.authorisedPrice, c.maxDiscountPct, c.taxRegime, c.taxRatePct))
  }

  def appliedDiscountPct(listExVat: BigDecimal, unitPriceExVat: BigDecimal): BigDecimal =
    if (listExVat.signum == 0) BigDecimal(0)
    else ((listExVat - unitPriceExVat) / listExVat * 100).setScale(2, RoundingMode.HALF_UP)

  def categorise(resolution: PriceResolution, unitPriceExVat: BigDecimal): String =
    if (appliedDiscountPct(resolution.exVat, unitPriceExVat) <= resolution.maxDiscountPct + Epsilon) "standard"
    else "exception"

  def vat(unitPriceExVat: BigDecimal, qty: Int, ratePct: BigDecimal): BigDecimal =
    (unitPriceExVat * BigDecimal(qty) * ratePct / 100).setScale(2, RoundingMode.HALF_UP)

  def priceLine(resolution: PriceResolution, line: QuoteLine): QuoteLineResult = {
    val unitPrice = line.unitPriceExVat.getOrElse(resolution.exVat)
    val lineVat   = vat(unitPrice, line.qty, resolution.taxRatePct)
    val exVatLine = (unitPrice * BigDecimal(line.qty)).setScale(2, RoundingMode.HALF_UP)
    QuoteLineResult(
      sku = line.sku,
      qty = line.qty,
      resolvedExVat = resolution.exVat,
      maxDiscountPct = resolution.maxDiscountPct,
      appliedDiscountPct = appliedDiscountPct(resolution.exVat, unitPrice),
      unitPriceExVat = unitPrice,
      adlpCategory = categorise(resolution, unitPrice),
      vat = lineVat,
      lineTotalIncVat = exVatLine + lineVat,
      priceRuleId = resolution.ruleId
    )
  }

  def assemble(lines: List[QuoteLineResult]): QuoteResult = {
    val subtotal = lines.map(l => (l.unitPriceExVat * BigDecimal(l.qty)).setScale(2, RoundingMode.HALF_UP)).foldLeft(BigDecimal(0))(_ + _)
    val vatTotal = lines.map(_.vat).foldLeft(BigDecimal(0))(_ + _)
    QuoteResult(
      lines = lines,
      subtotalExVat = subtotal,
      vatTotal = vatTotal,
      totalIncVat = subtotal + vatTotal,
      requiresException = lines.exists(_.adlpCategory == "exception")
    )
  }
}
