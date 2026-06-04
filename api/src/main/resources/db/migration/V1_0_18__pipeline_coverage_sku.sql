-- Per-SKU coverage (doc 12 §4 — built on the product catalogue). Capture is already per variant; coverage must
-- be too, because demand for different SKUs does not equate (you cannot cover a Home 3 Pro forecast with a
-- Home 2.2 shipment). pipeline_coverage gains product_variant_id: NULL = the all-SKU total at that level (the
-- existing board), a value = the per-SKU breakdown. Both are materialised so the board drills SKU and rolls up.
ALTER TABLE pipeline_coverage ADD COLUMN product_variant_id UUID NULL;

-- The dedupe key now includes the SKU dimension (NULL collapses to the sentinel like the other axes).
DROP INDEX uq_pipeline_coverage_dim;
CREATE UNIQUE INDEX uq_pipeline_coverage_dim ON pipeline_coverage (
    level,
    COALESCE(channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(sub_channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(segment, ''),
    COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(branch_company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(agent_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(market_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(product_variant_id, '00000000-0000-0000-0000-000000000000'::uuid),
    period_month,
    scenario_id
);
CREATE INDEX pipeline_coverage_sku_idx ON pipeline_coverage (market_id, period_month, scenario_id, product_variant_id);
