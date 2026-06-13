package com.hypervolt.conduit.access

// Decision 14b: which field of which object sits in which data layer. Drives server-side projection
// (doc 05 §3). This is the seed; it is also loaded into the `field_layer_map` table (migration) so it
// is editable at runtime. Unmapped fields are unclassified → always visible.
object FieldLayerMap {

  val seed: Map[(String, String), DataLayer] = Map(
    // price_rule — customer pricing is commercial; the inter-entity transfer-price fields are walled off
    ("price_rule", "authorised_price") -> DataLayer.Commercial,
    ("price_rule", "max_discount_pct") -> DataLayer.Commercial,
    ("price_rule", "tp_method")        -> DataLayer.InterEntity,
    ("price_rule", "tp_markup_pct")    -> DataLayer.InterEntity,
    ("price_rule", "from_entity_id")   -> DataLayer.InterEntity,
    ("price_rule", "to_entity_id")     -> DataLayer.InterEntity,
    // ADLP exception — price banding is commercial; the margin assessment is profitability
    ("adlp_exception", "list_price")             -> DataLayer.Commercial,
    ("adlp_exception", "requested_price")        -> DataLayer.Commercial,
    ("adlp_exception", "requested_discount_pct") -> DataLayer.Commercial,
    ("adlp_exception", "max_discount_pct")       -> DataLayer.Commercial,
    ("adlp_exception", "margin_assessment")      -> DataLayer.Profitability,
    // order money
    ("order", "subtotal_ex_vat") -> DataLayer.Commercial,
    ("order", "vat_total")       -> DataLayer.Commercial,
    ("order", "total_inc_vat")   -> DataLayer.Commercial,
    // order_line margin inputs
    ("order_line", "unit_price_ex_vat") -> DataLayer.Commercial,
    // commission
    ("commission_entry", "amount")       -> DataLayer.Commission,
    ("commission_entry", "basis_amount") -> DataLayer.Profitability,
    // cost / margin
    ("lot_batch", "landed_unit_cost") -> DataLayer.Profitability,
    ("lot_batch", "unit_cost_usd")    -> DataLayer.Profitability,
    // returns / RMA — refund/credit money is commercial; the unit's landed cost is profitability (doc 09 §J)
    ("rma", "refund_amount")    -> DataLayer.Commercial,
    ("rma", "unit_landed_cost") -> DataLayer.Profitability,
    // PII
    ("contact", "email") -> DataLayer.Pii,
    ("contact", "phone") -> DataLayer.Pii,
    // H6Q coverage — units vs money vs margin on the same board
    ("pipeline_coverage", "forecast_qty") -> DataLayer.Volume,
    ("pipeline_coverage", "shipped_qty")  -> DataLayer.Volume,
    ("pipeline_coverage", "revenue")      -> DataLayer.Commercial,
    ("pipeline_coverage", "margin")       -> DataLayer.Profitability,
    // treasury
    ("fx_hedge", "contracted_rate") -> DataLayer.Treasury,
    ("fx_hedge", "notional")        -> DataLayer.Treasury,
    // intercompany / transfer pricing (doc 13 §9) — price/cost/policy detail is inter_entity-walled; the
    // physical movement (qty, stock transfer) is volume, so a fulfilment_agent sees the move, not the price
    ("transfer_price_policy", "method")            -> DataLayer.InterEntity,
    ("transfer_price_policy", "markup_pct")        -> DataLayer.InterEntity,
    ("transfer_price_policy", "resale_margin_pct") -> DataLayer.InterEntity,
    ("transfer_price_policy", "fixed_price")       -> DataLayer.InterEntity,
    ("intercompany_link", "transfer_price_total")  -> DataLayer.InterEntity,
    ("intercompany_link", "fx_rate")               -> DataLayer.InterEntity,
    ("intercompany_link", "fx_basis")              -> DataLayer.InterEntity,
    ("intercompany_link", "qty")                   -> DataLayer.Volume,
    ("intercompany_link", "stock_transfer_id")     -> DataLayer.Volume,
    ("tp_document", "transfer_unit_price")         -> DataLayer.InterEntity,
    ("tp_document", "lot_landed_unit_cost")        -> DataLayer.InterEntity,
    ("tp_document", "markup_or_margin_pct")        -> DataLayer.InterEntity,
    // the central price catalogue + flash-title matches (doc 28) — the ENTIRE structure is inter_entity-walled:
    // outside the principal, nobody learns the catalogue exists, let alone the markup or the match chain.
    ("transfer_price_list", "unit_price") -> DataLayer.InterEntity,
    ("transfer_price_list", "currency")   -> DataLayer.InterEntity,
    ("transfer_price_list", "status")     -> DataLayer.InterEntity,
    ("transfer_price_list", "market_id")  -> DataLayer.InterEntity,
    ("ic_match", "landed_total")          -> DataLayer.InterEntity,
    ("ic_match", "transfer_total")        -> DataLayer.InterEntity,
    ("ic_match", "uplift_total")          -> DataLayer.InterEntity,
    ("ic_match", "price_list_id")         -> DataLayer.InterEntity,
    ("ic_match", "origin_batch_ids")      -> DataLayer.InterEntity,
    ("ic_match", "elimination_group_id")  -> DataLayer.InterEntity,
    // doc 28 §5.1 — the booked-FX stamp on the hop is principal-side truth, same wall as the prices
    ("ic_match", "booked_rate")               -> DataLayer.InterEntity,
    ("ic_match", "rate_source")               -> DataLayer.InterEntity,
    ("ic_match", "principal_functional_ccy")  -> DataLayer.InterEntity,
    ("ic_match", "transfer_total_functional") -> DataLayer.InterEntity,
    ("ic_remeasurement", "open_txn")          -> DataLayer.InterEntity,
    ("ic_remeasurement", "closing_rate")      -> DataLayer.InterEntity,
    ("ic_remeasurement", "carrying_before")   -> DataLayer.InterEntity,
    ("ic_remeasurement", "measured")          -> DataLayer.InterEntity,
    ("ic_remeasurement", "delta")             -> DataLayer.InterEntity,
    ("ic_true_up", "prior_uplift")            -> DataLayer.InterEntity,
    ("ic_true_up", "target_uplift")           -> DataLayer.InterEntity,
    ("ic_true_up", "adjustment")              -> DataLayer.InterEntity,
    ("ic_true_up_line", "allocated")          -> DataLayer.InterEntity,
    // hedge performance is treasury (doc 28 §5.5 / ASC 815-50)
    ("hedge_valuation", "spot_rate")       -> DataLayer.Treasury,
    ("hedge_valuation", "contracted_rate") -> DataLayer.Treasury,
    ("hedge_valuation", "notional_open")   -> DataLayer.Treasury,
    ("hedge_valuation", "period_mtm")      -> DataLayer.Treasury,
    ("hedge_valuation", "cumulative_mtm")  -> DataLayer.Treasury,
    // legal documents (doc 17 §9): the money on the artefact is commercial; the rest is logistics/identity.
    ("document", "total_amount") -> DataLayer.Commercial,
    ("document", "currency")     -> DataLayer.Commercial,
    // order collection ledger (doc 13 §void): the cycle money is commercial; the structure (invoice no, status,
    // dates, void kind, replaced-by) is logistics/identity and stays visible to a volume-only viewer.
    ("collection_cycle", "total")          -> DataLayer.Commercial,
    ("collection_cycle", "revenue_ex_vat") -> DataLayer.Commercial,
    ("collection_cycle", "vat")            -> DataLayer.Commercial,
    ("collection_cycle", "cogs")           -> DataLayer.Commercial,
    ("collection_cycle", "paid")           -> DataLayer.Commercial,
    ("collection_cycle", "refunded")       -> DataLayer.Commercial,
    ("collection_cycle", "outstanding")    -> DataLayer.Commercial,
    // tax determination (doc 16 §9): amounts + jurisdiction breakdown are commercial; quantities are volume; a
    // VAT/tax registration number is PII (personal data for a sole trader). Tax never touches profitability —
    // it is computed off the ex-tax price, not cost — so a tax viewer never gains margin visibility.
    ("tax_quote", "total_tax")               -> DataLayer.Commercial,
    ("tax_quote", "buyer_tax_id")            -> DataLayer.Pii,
    ("tax_quote_line", "taxable_amount")     -> DataLayer.Commercial,
    ("tax_quote_line", "line_tax_total")     -> DataLayer.Commercial,
    ("tax_quote_line", "effective_rate_pct") -> DataLayer.Commercial,
    ("tax_quote_line", "components")         -> DataLayer.Commercial,
    ("tax_quote_line", "qty")                -> DataLayer.Volume,
    ("tax_regime", "rate_percent")           -> DataLayer.Commercial,
    ("tax_rate", "rate_pct")                 -> DataLayer.Commercial,
    ("tax_registration", "number")           -> DataLayer.Pii,
    ("nexus_profile", "sales_to_date")       -> DataLayer.Commercial,
    ("nexus_profile", "txn_count_to_date")   -> DataLayer.Volume,
    ("intrastat_line", "invoice_value")      -> DataLayer.Commercial,
    ("ec_sales_line", "net_value")           -> DataLayer.Commercial
  )

  def layerOf(objectType: String, field: String): Option[DataLayer] = seed.get((objectType, field))
}
