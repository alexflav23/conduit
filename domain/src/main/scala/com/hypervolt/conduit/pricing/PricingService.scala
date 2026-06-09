package com.hypervolt.conduit.pricing

import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Pricing resolution + ADLP categorisation (doc 04 §Pricing/§ADLP, doc 24). Pure: candidates come from the repo.
object PricingService {

  private val Epsilon = BigDecimal("0.0001")

  // Most-specific agreement wins (doc 24 §2): a customer_set agreement naming the party beats a segment, beats a
  // sector, beats the open_list. Within the chosen agreement the band is the highest from_qty the order's qty
  // reaches (the candidates are already filtered to qty-eligible bands by the query).
  private def appliesRank(appliesTo: String): Int =
    appliesTo match {
      case "customer_set" => 3
      case "segment"      => 2
      case "sector"       => 1
      case _              => 0 // open_list
    }

  def resolve(
      candidates: List[PriceRuleCandidate],
      channel: UUID,
      market: UUID,
      entity: Option[UUID]
  ): Option[PriceResolution] = {
    def specificity(c: PriceRuleCandidate): Int =
      List(
        c.channelId.contains(channel),
        c.marketId.contains(market),
        entity.exists(e => c.entityId.contains(e))
      ).count(identity)

    candidates
      .sortBy(c => (-appliesRank(c.appliesTo), -specificity(c), -c.minQty, -c.version))
      .headOption
      .map(c => PriceResolution(c.id, c.agreementId, c.authorisedPrice, c.maxDiscountPct, c.taxRegime, c.taxRatePct))
  }

  def appliedDiscountPct(listExVat: BigDecimal, unitPriceExVat: BigDecimal): BigDecimal =
    if (listExVat.signum == 0) BigDecimal(0)
    else ((listExVat - unitPriceExVat) / listExVat * 100).setScale(2, RoundingMode.HALF_UP)

  def categorise(resolution: PriceResolution, unitPriceExVat: BigDecimal): String =
    if (appliedDiscountPct(resolution.exVat, unitPriceExVat) <= resolution.maxDiscountPct + Epsilon) "standard"
    else "exception"

  // No typed prices (doc 24 §3): the authorized price is the resolved tier price. A supplied price is accepted only
  // if it equals the tier price (idempotent re-quote); any other value is rejected upstream — never silently honoured.
  def isTierPrice(resolution: PriceResolution, supplied: Option[BigDecimal]): Boolean =
    supplied.forall(p => (p - resolution.exVat).abs <= Epsilon)

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
      priceRuleId = resolution.ruleId,
      priceAgreementId = resolution.agreementId
    )
  }

  def assemble(lines: List[QuoteLineResult]): QuoteResult = {
    val subtotal = lines
      .map(l => (l.unitPriceExVat * BigDecimal(l.qty)).setScale(2, RoundingMode.HALF_UP))
      .foldLeft(BigDecimal(0))(_ + _)
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
