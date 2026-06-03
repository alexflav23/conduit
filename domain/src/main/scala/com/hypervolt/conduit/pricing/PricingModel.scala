package com.hypervolt.conduit.pricing

import java.util.UUID

// A candidate ADLP rule (already filtered to active + in-window + qty-eligible + currency by the query),
// joined to its tax rate.
final case class PriceRuleCandidate(
    id: UUID,
    channelId: Option[UUID],
    marketId: Option[UUID],
    entityId: Option[UUID],
    authorisedPrice: BigDecimal,
    maxDiscountPct: BigDecimal,
    minQty: Int,
    version: Int,
    taxRegime: String,
    taxRatePct: BigDecimal
)

final case class PriceResolution(
    ruleId: UUID,
    exVat: BigDecimal,
    maxDiscountPct: BigDecimal,
    taxRegime: String,
    taxRatePct: BigDecimal
)

final case class QuoteLine(sku: String, qty: Int, unitPriceExVat: Option[BigDecimal])

final case class QuoteLineResult(
    sku: String,
    qty: Int,
    resolvedExVat: BigDecimal,
    maxDiscountPct: BigDecimal,
    appliedDiscountPct: BigDecimal,
    unitPriceExVat: BigDecimal,
    adlpCategory: String,
    vat: BigDecimal,
    lineTotalIncVat: BigDecimal,
    priceRuleId: UUID
)

final case class QuoteResult(
    lines: List[QuoteLineResult],
    subtotalExVat: BigDecimal,
    vatTotal: BigDecimal,
    totalIncVat: BigDecimal,
    requiresException: Boolean
)
