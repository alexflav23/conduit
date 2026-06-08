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
    // legal documents (doc 17 §9): the money on the artefact is commercial; the rest is logistics/identity.
    ("document", "total_amount") -> DataLayer.Commercial,
    ("document", "currency")     -> DataLayer.Commercial
  )

  def layerOf(objectType: String, field: String): Option[DataLayer] = seed.get((objectType, field))
}
