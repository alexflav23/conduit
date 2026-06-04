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
    ("fx_hedge", "notional")        -> DataLayer.Treasury
  )

  def layerOf(objectType: String, field: String): Option[DataLayer] = seed.get((objectType, field))
}
