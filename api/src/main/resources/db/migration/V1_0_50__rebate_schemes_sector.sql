-- M-Pricing slice 4 (doc 24 §4.4 / §5.8): the generalised rebate scheme (any time-bound rebate), the party sector
-- taxonomy (the one genuine FACT — persists on the party), and the agreement scope value sector/segment agreements
-- match on. Term/renewal lifecycle stays DERIVED from valid_from/valid_to + renews_from — no status column.

-- Sector: a governed coarse industry taxonomy, coarser than party.segment (doc 24 §5.8). Persists on the party
-- (cannot be derived); a join-away dimension for agreement scope, H6Q rollups, and reporting breakdowns.
CREATE TABLE sector (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL
);
INSERT INTO sector (code, name) VALUES
    ('energy', 'Energy'),
    ('automotive', 'Automotive'),
    ('construction', 'Construction'),
    ('retail', 'Retail'),
    ('property', 'Property & facilities'),
    ('other', 'Other');

ALTER TABLE party ADD COLUMN sector TEXT REFERENCES sector(code);

-- The value a segment/sector-scoped agreement matches on (doc 24 §2 applies_to). NULL for open_list/customer_set.
ALTER TABLE price_agreement ADD COLUMN scope_value TEXT;

-- Generalised, arbitrary time-bound rebate (doc 24 §4.4) — the §5 contract-year volume rebate is one row. window =
-- begin/end timestamps (a UI shape like contract_year/rolling(n) is RESOLVED to these on creation, never enumerated).
-- qualifying_filter = whose units accumulate toward the tier (e.g. product_class=charger); applies_filter = which
-- products receive it (doc 24 §4.5). Evaluated by the same accrue/settle engine, each over its own window.
CREATE TABLE rebate_scheme (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_id      UUID NOT NULL REFERENCES price_agreement(id) ON DELETE CASCADE,
    price_tier_id     UUID REFERENCES price_rule(id),            -- NULL = agreement-wide
    name              TEXT NOT NULL,
    valid_from        TIMESTAMPTZ NOT NULL,
    valid_to          TIMESTAMPTZ,
    basis             TEXT NOT NULL DEFAULT 'volume',            -- volume | spend | growth_vs_prior | flat
    unit              TEXT NOT NULL DEFAULT 'unit',              -- unit | currency
    qualifying_filter JSONB NOT NULL DEFAULT '{}',               -- {product_class:[...], family:[...], variant:[...]}
    applies_filter    JSONB NOT NULL DEFAULT '{}',
    treatment         TEXT NOT NULL DEFAULT 'retrospective',     -- prospective | retrospective
    ladder            JSONB NOT NULL DEFAULT '[]',               -- volume: [{from_threshold, value}] ; flat: [{from_threshold:0, value:pct}]
    status            TEXT NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rebate_scheme_agreement_idx ON rebate_scheme (agreement_id, status);
