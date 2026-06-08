package com.hypervolt.conduit.tax

import doobie._

// The pluggable boundary (doc 16 §3.3): one interface, two+ implementations. Callers depend only on this. The
// default rate-table path is pure/deterministic; an external vendor (Avalara/TaxJar/Stripe Tax) is a different
// instance behind the same contract, registered under its provider name and selected by a `tax_routing` row —
// flipping a market to a vendor is config + an adapter, never a caller change.
trait TaxProvider {
  def name: String
  def quote(req: TaxQuoteRequest): ConnectionIO[TaxQuoteResponse]
}

object RateTableProvider extends TaxProvider {
  val name: String                                                = RateTableTaxEngine.ProviderName
  def quote(req: TaxQuoteRequest): ConnectionIO[TaxQuoteResponse] = RateTableTaxEngine.quoteC(req)
}
