package com.hypervolt.conduit.pricing

import java.util.UUID

// A candidate tier (band) row (doc 24 §2) — already filtered by the query to: active agreement + active rule, in
// the as-of window, applicable to the customer (open_list always; customer_set only if the party is in the set),
// currency, and qty-eligible band. `appliesTo` drives most-specific-agreement resolution; `upToQty` is the band
// ceiling (None = open-ended).
final case class PriceRuleCandidate(
    id: UUID,
    agreementId: Option[UUID],
    appliesTo: String,
    channelId: Option[UUID],
    marketId: Option[UUID],
    entityId: Option[UUID],
    authorisedPrice: BigDecimal,
    maxDiscountPct: BigDecimal,
    minQty: Int,
    upToQty: Option[Int],
    version: Int,
    taxRegime: String,
    taxRatePct: BigDecimal
)

// A band of a cumulative (non-per_order) agreement (doc 24 §4(b)) — selected by the running cumulative position,
// not the line qty. Carries the agreement's valid_from so the contract-year window can be derived.
final case class CumulativeBand(
    id: UUID,
    agreementId: UUID,
    appliesTo: String,
    validFrom: java.time.Instant,
    channelId: Option[UUID],
    marketId: Option[UUID],
    entityId: Option[UUID],
    authorisedPrice: BigDecimal,
    maxDiscountPct: BigDecimal,
    fromQty: Int,
    upToQty: Option[Int],
    version: Int,
    taxRegime: String,
    taxRatePct: BigDecimal
)

final case class PriceResolution(
    ruleId: UUID,
    agreementId: Option[UUID],
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
    priceRuleId: UUID,
    priceAgreementId: Option[UUID]
)

final case class QuoteResult(
    lines: List[QuoteLineResult],
    subtotalExVat: BigDecimal,
    vatTotal: BigDecimal,
    totalIncVat: BigDecimal,
    requiresException: Boolean
)
