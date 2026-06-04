-- M11 — H6Q forecasting (doc 12; storage home doc 02 §K). Bottom-up demand forecast: many account owners
-- each forecast their own accounts every weekly cycle, captured append-only and auto-rolled-up to the H6Q
-- hierarchy (org axis) AND by sales agent (ownership axis) — the two must reconcile. Materialised, replayable
-- projections rebuilt by event consumers; no spreadsheet re-keying.

-- The three probability bands are independent numbers per (variant, month), not derived from one another
-- (doc 12 §5.1). P80 = high conviction, P50 = management discretion (working default), P20 = upside (the guide).
-- A scenario may also carry an ex-cut basis (ex_octopus / ex_motability) applied at coverage time (§5.2).
CREATE TABLE forecast_scenario (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type         TEXT NOT NULL,                       -- P20 | P50 | P80
    name         TEXT NOT NULL,
    toggle_basis TEXT NULL,                           -- NULL = a band; else an ex-cut (ex_octopus, ex_motability, inc_motability)
    is_default   BOOLEAN NOT NULL DEFAULT false,      -- P50 is the working default
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (type, toggle_basis)
);
INSERT INTO forecast_scenario (type, name, toggle_basis, is_default) VALUES
    ('P20','Upside (20%)', NULL, false),
    ('P50','Management (50%)', NULL, true),
    ('P80','High conviction (80%)', NULL, false),
    ('P50','Management ex-Octopus', 'ex_octopus', false),
    ('P50','Management ex-Motability', 'ex_motability', false);

-- The weekly capture WINDOW (when an owner may submit) — distinct from forecast_entry.period_month (the
-- horizon month being forecast). One cycle captures estimates for many future months (doc 12 §2.1).
CREATE TABLE forecast_cycle (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT NOT NULL UNIQUE,                -- ISO week, e.g. 2026-W23
    cadence      TEXT NOT NULL DEFAULT 'weekly',      -- data-driven; switching cadence is config, not code
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    reference_tz TEXT NOT NULL DEFAULT 'Europe/London', -- interprets DATE bounds only (§2.1); group reporting TZ
    status       TEXT NOT NULL DEFAULT 'open',        -- open | closed
    opened_at    TIMESTAMPTZ NULL,
    closed_at    TIMESTAMPTZ NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- At most ONE open cycle per cadence at any instant (doc 12 §2.8 invariant).
CREATE UNIQUE INDEX uq_forecast_cycle_open ON forecast_cycle (cadence) WHERE status = 'open';

-- One owner's act of forecasting one account/branch in one cycle (doc 12 §3.1). Generated 'outstanding' at
-- cycle open; flips to 'submitted' on capture; 'skipped' on explicit skip / reassignment.
CREATE TABLE forecast_submission (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id               UUID NOT NULL REFERENCES forecast_cycle(id),
    forecaster_user_id     UUID NOT NULL,             -- the owning agent (app_user)
    company_id             UUID NOT NULL,             -- the account or branch being forecast (party)
    status                 TEXT NOT NULL DEFAULT 'outstanding', -- outstanding | submitted | skipped
    skip_reason            TEXT NULL,                 -- reassigned | no_demand | owner_skipped
    submitted_at           TIMESTAMPTZ NULL,
    submitted_via_cycle_id UUID NULL REFERENCES forecast_cycle(id), -- if it landed in a later window (late drain)
    device                 TEXT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cycle_id, forecaster_user_id, company_id)
);
CREATE INDEX forecast_submission_cycle_idx ON forecast_submission (cycle_id, status);
CREATE INDEX forecast_submission_owner_idx ON forecast_submission (forecaster_user_id, cycle_id);

-- A single VERSIONED estimate — immutable. A revision inserts a new row and stamps superseded_by on the prior;
-- the current estimate is the latest non-superseded row for the estimate key (doc 12 §3.1). This append-only
-- chain is what makes accuracy scoring honest and WoW reconstructable.
-- Estimate key = (company_id|branch_company_id, product_variant_id, period_month, scenario_id, source).
CREATE TABLE forecast_entry (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id      UUID NULL REFERENCES forecast_submission(id), -- NULL for hyperview/system rows
    cycle_id           UUID NULL REFERENCES forecast_cycle(id),
    forecaster_user_id UUID NULL,                     -- NULL for hyperview (attributed to model_version)
    channel_id         UUID NULL,
    sub_channel_id     UUID NULL,
    segment            TEXT NULL,
    market_id          UUID NULL,
    company_id         UUID NULL,                     -- enclosing customer
    branch_company_id  UUID NULL,                     -- the branch, when capturing at branch level
    product_variant_id UUID NULL,                     -- NULL = account total for the month/scenario
    period_month       DATE NOT NULL,                 -- horizon month (first of month)
    scenario_id        UUID NOT NULL REFERENCES forecast_scenario(id),
    qty                INTEGER NOT NULL,
    source             TEXT NOT NULL DEFAULT 'manual', -- manual | hyperview
    model_version      TEXT NULL,                     -- hyperview provenance
    superseded_by      UUID NULL REFERENCES forecast_entry(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- The "current estimate" read path: latest non-superseded row per estimate key.
CREATE INDEX forecast_entry_key_idx
    ON forecast_entry (market_id, period_month, scenario_id, source)
    WHERE superseded_by IS NULL;
CREATE INDEX forecast_entry_leaf_idx
    ON forecast_entry (branch_company_id, company_id, product_variant_id, period_month, scenario_id)
    WHERE superseded_by IS NULL;
CREATE INDEX forecast_entry_submission_idx ON forecast_entry (submission_id);

-- Materialised coverage projection (doc 12 §4). One row PER LEVEL of the hierarchy AND per agent, so the board
-- sums up / drills down and the same atomic numbers re-aggregate by person. Rebuilt by event consumers;
-- reproducible by replay. Quantities sum on rollup; coverage_pct is RECOMPUTED from summed components, never
-- averaged (doc 12 §4.4).
CREATE TABLE pipeline_coverage (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level                   TEXT NOT NULL,            -- branch|company|segment|sub_channel|channel|market|agent
    channel_id              UUID NULL,
    sub_channel_id          UUID NULL,
    segment                 TEXT NULL,
    company_id              UUID NULL,
    branch_company_id       UUID NULL,
    agent_user_id           UUID NULL,
    market_id               UUID NULL,
    period_month            DATE NOT NULL,
    scenario_id             UUID NOT NULL REFERENCES forecast_scenario(id),
    forecast_qty            INTEGER NOT NULL DEFAULT 0,
    weighted_pipeline_qty   NUMERIC(18,4) NOT NULL DEFAULT 0,
    shipped_qty             INTEGER NOT NULL DEFAULT 0,
    activated_qty           INTEGER NOT NULL DEFAULT 0,
    coverage_pct            NUMERIC(9,4) NULL,        -- (shipped + weighted_pipeline) / forecast; NULL if forecast=0
    forecast_qty_ex         INTEGER NOT NULL DEFAULT 0, -- forecast with the scenario's ex-cut applied
    coverage_ex_account_pct NUMERIC(9,4) NULL,
    wow_delta               NUMERIC(9,4) NULL,        -- WoW change in coverage_pct vs prior cycle close
    forecast_source         TEXT NULL,               -- manual | hyperview | mixed
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Idempotent upsert key over the full dimension (doc 12 §4.7). NULLs collapse to a sentinel so the unique
-- constraint treats "no channel" rows as equal (Postgres treats NULLs as distinct otherwise).
CREATE UNIQUE INDEX uq_pipeline_coverage_dim ON pipeline_coverage (
    level,
    COALESCE(channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(sub_channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(segment, ''),
    COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(branch_company_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(agent_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(market_id, '00000000-0000-0000-0000-000000000000'::uuid),
    period_month,
    scenario_id
);
CREATE INDEX pipeline_coverage_board_idx ON pipeline_coverage (market_id, period_month, scenario_id, level);

-- Sell-in (dispatch) vs sell-through (v3 activations) + overhang (doc 12 §7). Rebuilt by dispatch.* /
-- activation.recorded consumers. Overhang/sell-through count v3 only (the V2/V3 rule).
CREATE TABLE sell_through (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          UUID NOT NULL,
    channel_id          UUID NULL,
    period_month        DATE NOT NULL,
    sell_in_qty         INTEGER NOT NULL DEFAULT 0,
    sell_through_qty    INTEGER NOT NULL DEFAULT 0,
    overhang_qty        INTEGER NULL,                -- cumulative sell_in - cumulative sell_through (v3)
    generation_scope    TEXT NOT NULL DEFAULT 'v3',  -- audit of the V2/V3 rule
    last_shipment_date  DATE NULL,
    last_activation_date DATE NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, channel_id, period_month)
);

-- Per-owner estimate-vs-actual accuracy (doc 12 §9). error/bias/MAPE; scored from append-only history so it is
-- replayable. The 20%-margin-of-error rule (the Volex constraint, forecasting guide §4) is a threshold over mape.
CREATE TABLE forecast_accuracy (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    forecaster_user_id UUID NULL,                     -- NULL for hyperview (keyed by model_version)
    company_id         UUID NOT NULL,
    product_variant_id UUID NULL,
    period_month       DATE NOT NULL,
    scenario_id        UUID NULL REFERENCES forecast_scenario(id),
    actual_basis       TEXT NOT NULL DEFAULT 'sell_in', -- sell_in | sell_through
    forecast_qty       INTEGER NOT NULL,
    actual_qty         INTEGER NOT NULL,
    error              INTEGER NOT NULL,              -- actual - forecast (signed)
    bias               NUMERIC(12,4) NULL,
    mape               NUMERIC(9,4) NULL,
    model_version      TEXT NULL,
    scored_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (forecaster_user_id, company_id, product_variant_id, period_month, scenario_id, actual_basis, model_version)
);
CREATE INDEX forecast_accuracy_owner_idx ON forecast_accuracy (forecaster_user_id, company_id, period_month);
CREATE INDEX forecast_accuracy_model_idx ON forecast_accuracy (model_version, period_month);

-- Layer-aware projection (doc 12 §8.3): a volume-only viewer sees units/coverage; commercial adds revenue;
-- profitability adds margin. Revenue/margin are derived at read time (H6Q owns no money), so only the unit
-- fields are mapped here as volume; the commercial/profitability derived fields gate on the layer in the route.
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('pipeline_coverage','forecast_qty','volume'),
    ('pipeline_coverage','weighted_pipeline_qty','volume'),
    ('pipeline_coverage','shipped_qty','volume'),
    ('pipeline_coverage','activated_qty','volume'),
    ('pipeline_coverage','coverage_pct','volume'),
    ('pipeline_coverage','coverage_ex_account_pct','volume'),
    ('pipeline_coverage','wow_delta','volume'),
    ('pipeline_coverage','forecast_revenue','commercial'),
    ('pipeline_coverage','shipped_revenue','commercial'),
    ('pipeline_coverage','forecast_margin','profitability'),
    ('pipeline_coverage','shipped_gp','profitability'),
    ('sell_through','sell_in_qty','volume'),
    ('sell_through','sell_through_qty','volume'),
    ('sell_through','overhang_qty','volume')
ON CONFLICT (object_type, field) DO NOTHING;

-- Policy-layer grants for the new H6Q objects (doc 12 §12). Capture (forecast) is own-scope create for account
-- owners; the board (pipeline_coverage) is view for owners (own) and finance/CEO/auditor (all, all layers).
-- An account owner sees the unit board for their own scope; finance adds commercial/profitability overlays.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'forecast', 'create', NULL, '{volume}', '{volume}', 'own' FROM role WHERE name='retail_sales_agent';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'pipeline_coverage', 'view', NULL, '{volume}', '{}', 'own' FROM role WHERE name='retail_sales_agent';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'pipeline_coverage', 'view', NULL, '{volume,commercial,profitability}', '{}', 'all' FROM role WHERE name='finance';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'pipeline_coverage', 'export', NULL, '{volume,commercial,profitability}', '{}', 'all' FROM role WHERE name='finance';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'pipeline_coverage', 'view', NULL, '{volume,commercial,profitability,inter_entity,treasury}', '{}', 'all' FROM role WHERE name='ceo';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'pipeline_coverage', 'view', NULL, '{volume,commercial,profitability}', '{}', 'all' FROM role WHERE name='auditor';
