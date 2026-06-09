-- M-Forecast / H6Q views (doc 26, doc 24 §5.8, doc 12 §4): the governed SECTOR (energy / retail / wholesale /
-- installers / automotive — party.sector, M-Pricing slice 4) becomes a first-class rollup dimension of the
-- coverage projection. GLOBAL and cross-market-sector views are READ-TIME aggregations over the per-market rows
-- (a market recompute can only know its own slice; deriving global keeps the projection honest per market —
-- which is also what lets seasonality differ per market without blending).
ALTER TABLE pipeline_coverage ADD COLUMN sector TEXT;

-- The dedupe key gains the sector dimension (NULL collapses to the sentinel like the other axes).
DROP INDEX uq_pipeline_coverage_dim;
CREATE UNIQUE INDEX uq_pipeline_coverage_dim ON pipeline_coverage (
    level,
    COALESCE(channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(sub_channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(segment, ''),
    COALESCE(sector, ''),
    COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(branch_company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(agent_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(market_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(product_variant_id, '00000000-0000-0000-0000-000000000000'::uuid),
    period_month,
    scenario_id
);

-- The major sectors the business attributes against (product owner: "energy, retail, wholesale, installers,
-- automotive and so on") — extending the governed taxonomy seeded in V1_0_50.
INSERT INTO sector (code, name) VALUES
    ('wholesale', 'Wholesale'),
    ('installers', 'Installers')
ON CONFLICT (code) DO NOTHING;
