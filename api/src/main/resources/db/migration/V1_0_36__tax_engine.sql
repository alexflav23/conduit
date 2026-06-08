-- M13-Tax (doc 16) — the tax & customs determination subsystem. The design point: tax is a QUOTE, not a rate
-- column. The regime catalogue (tax_regime, code-PK, kept as-is + additive descriptive columns) is separated from
-- the RATE VALUES, which live in `tax_rate` — effective-dated, multi-level (state/county/district), postcode-prefix
-- aware. A rate change is a NEW dated row, never an in-place edit, so the rate in force at any historic as_of is
-- reproducible (doc 16 §7). One tax_rate row = one component in the jurisdiction breakdown (doc 16 §2.7).

-- 1. tax_regime — additive descriptive columns (existing code PK + rate_percent unchanged; FKs intact).
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS tax_type           TEXT    NOT NULL DEFAULT 'VAT';
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS region             TEXT;
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS economic_zone      TEXT;     -- EU/EEA/UK/ROW/NA
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS reverse_chargeable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS rounding_policy    TEXT    NOT NULL DEFAULT 'line';   -- line/invoice (doc 14 §1.2)
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS rounding_mode      TEXT    NOT NULL DEFAULT 'HALF_UP';
ALTER TABLE tax_regime ADD COLUMN IF NOT EXISTS provider           TEXT    NOT NULL DEFAULT 'rate_table';

UPDATE tax_regime SET economic_zone = 'UK'  WHERE jurisdiction = 'GB';
UPDATE tax_regime SET economic_zone = 'EU'  WHERE jurisdiction IN ('IE','DE');
UPDATE tax_regime SET reverse_chargeable = true WHERE code = 'REVERSE_CHARGE';
INSERT INTO tax_regime (code, rate_percent, jurisdiction, kind, economic_zone) VALUES
    ('EXPORT', 0.0000, NULL, 'export', 'ROW')
ON CONFLICT (code) DO NOTHING;

-- 2. tax_rate — the effective-dated, multi-level rate source of truth. One row per taxing authority.
--    Lookup: jurisdiction match + (region null OR =dest region) + (postcode_prefix null OR dest startsWith it) +
--    (tax_category null OR =line category), in the effective window, status='active'. Grouped by `level`; the most
--    specific row in each level wins; components summed. A future-dated rate change is just another row.
CREATE TABLE tax_rate (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_type          TEXT    NOT NULL DEFAULT 'VAT',          -- VAT/GST/HST/PST/QST/sales_tax/consumption
    jurisdiction      CHAR(2) NOT NULL,                        -- ISO country (GB, US, CA, IE …)
    region            TEXT,                                    -- US state / CA province; NULL = national/federal
    postcode_prefix   TEXT,                                    -- longest-prefix match (US ZIP → county/district); NULL = whole region
    level             TEXT    NOT NULL DEFAULT 'national',     -- national/state/county/city/district/federal/provincial
    tax_category_code TEXT,                                    -- NULL = all product categories
    name              TEXT    NOT NULL,                        -- "UK VAT", "California", "Los Angeles County"
    rate_pct          NUMERIC(9,4) NOT NULL,
    kind              TEXT    NOT NULL DEFAULT 'standard',      -- standard/reduced/zero/import
    recoverable       BOOLEAN NOT NULL DEFAULT true,           -- import VAT recoverable for B2B
    effective_from    DATE    NOT NULL DEFAULT DATE '1970-01-01',
    effective_to      DATE,
    source            TEXT    NOT NULL DEFAULT 'manual',       -- manual/hmrc/avalara_snapshot
    status            TEXT    NOT NULL DEFAULT 'active',        -- draft/active/superseded (maker-checker in Tax.3)
    proposed_by       UUID,
    approved_by       UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tax_rate_lookup_idx ON tax_rate (jurisdiction, tax_type, effective_from DESC);
CREATE INDEX tax_rate_region_idx ON tax_rate (jurisdiction, region, postcode_prefix);

INSERT INTO tax_rate (tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind) VALUES
    -- UK VAT (year-1 seed): standard 20, reduced 5, zero 0 — one national component.
    ('VAT', 'GB', NULL, NULL, 'national', 'goods_standard', 'UK VAT',          20.0000, 'standard'),
    ('VAT', 'GB', NULL, NULL, 'national', 'goods_reduced',  'UK VAT reduced',   5.0000, 'reduced'),
    ('VAT', 'GB', NULL, NULL, 'national', 'zero_rated',     'UK VAT zero',      0.0000, 'zero'),
    -- EU single-rate (for intra-community classification demos)
    ('VAT', 'IE', NULL, NULL, 'national', 'goods_standard', 'Ireland VAT',     23.0000, 'standard'),
    ('VAT', 'DE', NULL, NULL, 'national', 'goods_standard', 'Germany VAT',     19.0000, 'standard'),
    -- US California destination stack: state + LA-county + LA-Metro-district (postcode 900xx) = 8.5%. Multi-level.
    ('sales_tax', 'US', 'CA', NULL,  'state',    'goods_standard', 'California',      6.0000, 'standard'),
    ('sales_tax', 'US', 'CA', '900', 'county',   'goods_standard', 'Los Angeles',    0.2500, 'standard'),
    ('sales_tax', 'US', 'CA', '900', 'district', 'goods_standard', 'LA Metro',       2.2500, 'standard'),
    -- Canada: federal GST (country-wide) + BC provincial PST = two components.
    ('GST', 'CA', NULL, NULL, 'federal',     'goods_standard', 'Canada GST',     5.0000, 'standard'),
    ('PST', 'CA', 'BC', NULL, 'provincial',  'goods_standard', 'BC PST',         7.0000, 'standard');

-- 3. duty_rate — import customs duty, HS-prefix matched (longest prefix wins), effective-dated.
CREATE TABLE duty_rate (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    destination    CHAR(2) NOT NULL,             -- importing country
    hs_prefix      TEXT    NOT NULL DEFAULT '',   -- '' = catch-all default for the destination
    rate_pct       NUMERIC(9,4) NOT NULL,
    name           TEXT,
    effective_from DATE NOT NULL DEFAULT DATE '1970-01-01',
    effective_to   DATE,
    source         TEXT NOT NULL DEFAULT 'manual',
    status         TEXT NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX duty_rate_idx ON duty_rate (destination, hs_prefix, effective_from DESC);

INSERT INTO duty_rate (destination, hs_prefix, rate_pct, name) VALUES
    ('GB', '',     2.0000, 'UK default duty'),
    ('GB', '8504', 0.0000, 'UK electrical (chargers) duty-free'),
    ('DE', '',     2.7000, 'EU default duty'),
    ('DE', '8504', 0.0000, 'EU electrical (chargers) duty-free');

-- 4. tax_category — product tax classification (doc 16 §2.3).
CREATE TABLE tax_category (
    code              TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    default_kind      TEXT NOT NULL DEFAULT 'standard',
    provider_tax_code JSONB,
    hs_chapter_hint   TEXT
);
INSERT INTO tax_category (code, name, default_kind) VALUES
    ('goods_standard', 'Standard-rated goods', 'standard'),
    ('goods_reduced',  'Reduced-rate goods',   'reduced'),
    ('service',        'Service',              'standard'),
    ('warranty',       'Warranty product',     'standard'),
    ('digital',        'Digital service',      'standard'),
    ('zero_rated',     'Zero-rated',           'zero');
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS tax_category_code TEXT REFERENCES tax_category(code);

-- 5. tax_routing — which provider serves a (market, jurisdiction, tax_type). Config, not code (doc 16 §2.4).
--    Year-1: everything routes to rate_table. US sales_tax is ALSO seeded to rate_table here so the multi-level
--    rate stack above drives it end-to-end without a vendor account; flipping it to 'avalara' is a row change.
CREATE TABLE tax_routing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id           UUID,
    jurisdiction        CHAR(2),
    tax_type            TEXT,
    provider            TEXT NOT NULL DEFAULT 'rate_table',   -- rate_table/avalara/taxjar/stripe_tax
    provider_config_ref TEXT,
    priority            INTEGER NOT NULL DEFAULT 100,
    status              TEXT NOT NULL DEFAULT 'active',
    effective_from      DATE NOT NULL DEFAULT DATE '1970-01-01',
    effective_to        DATE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tax_routing_idx ON tax_routing (jurisdiction, tax_type, status);
INSERT INTO tax_routing (jurisdiction, tax_type, provider, priority) VALUES
    (NULL, NULL,         'rate_table', 100),
    ('US', 'sales_tax',  'rate_table',  10),
    ('CA', 'GST',        'rate_table',  10);

-- 6. tax_registration — the proof an entity has nexus / is obliged to charge in (jurisdiction, region) (doc 16 §2.2).
CREATE TABLE tax_registration (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID NOT NULL REFERENCES entity(id),
    tax_type          TEXT NOT NULL DEFAULT 'VAT',
    number            TEXT,
    jurisdiction      CHAR(2) NOT NULL,
    region            TEXT,
    registration_kind TEXT NOT NULL DEFAULT 'domestic',   -- domestic/oss/ioss/import/nexus
    collects_tax      BOOLEAN NOT NULL DEFAULT true,
    effective_from    DATE,
    effective_to      DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tax_registration_idx ON tax_registration (entity_id, jurisdiction, region);

-- 7. nexus_profile — US/CA economic-nexus thresholds + rolling totals (doc 16 §2.5).
CREATE TABLE nexus_profile (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id           UUID NOT NULL REFERENCES entity(id),
    jurisdiction        CHAR(2) NOT NULL,
    region              TEXT NOT NULL,
    threshold_amount    NUMERIC(18,4),
    threshold_txn_count INTEGER,
    threshold_currency  CHAR(3) NOT NULL DEFAULT 'USD',
    lookback            TEXT NOT NULL DEFAULT 'rolling_12m',
    sales_to_date       NUMERIC(18,4) NOT NULL DEFAULT 0,
    txn_count_to_date   INTEGER NOT NULL DEFAULT 0,
    status              TEXT NOT NULL DEFAULT 'monitoring',   -- monitoring/approaching/crossed/registered
    crossed_at          TIMESTAMPTZ,
    registration_id     UUID REFERENCES tax_registration(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, jurisdiction, region)
);
CREATE INDEX nexus_profile_status_idx ON nexus_profile (status);

-- 8. tax_quote / tax_quote_line — the persisted, immutable, reproducible determination (the audit anchor, doc 16 §2.6).
--    Append-only; a re-quote inserts a new row and points the prior at it via superseded_by. request/response
--    snapshots are the complete replay input/output.
CREATE TABLE tax_quote (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    context                TEXT NOT NULL,                  -- quote_preview/order_placed/invoice/intercompany_import
    order_id               UUID,
    tranche_id             UUID,
    order_invoice_id       UUID,
    intercompany_link_id   UUID,
    entity_id              UUID NOT NULL REFERENCES entity(id),
    ship_from_jurisdiction CHAR(2) NOT NULL,
    ship_from_region       TEXT,
    ship_to_jurisdiction   CHAR(2) NOT NULL,
    ship_to_region         TEXT,
    ship_to_postcode       TEXT,
    party_tax_status       TEXT NOT NULL,                  -- consumer/business/business_with_vat_id/exempt
    buyer_tax_id           TEXT,
    supply_kind            TEXT NOT NULL,
    provider               TEXT NOT NULL,
    provider_ref           TEXT,
    provider_version       TEXT,
    currency               CHAR(3) NOT NULL,
    total_tax              NUMERIC(18,4) NOT NULL,
    reverse_charge         BOOLEAN NOT NULL DEFAULT false,
    rounding_policy        TEXT NOT NULL DEFAULT 'line',
    rates_asof             DATE NOT NULL,
    request_snapshot       JSONB NOT NULL,
    response_snapshot      JSONB NOT NULL,
    superseded_by          UUID REFERENCES tax_quote(id),
    determined_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tax_quote_order_idx  ON tax_quote (order_id, context, determined_at DESC);
CREATE INDEX tax_quote_inv_idx    ON tax_quote (order_invoice_id);
CREATE INDEX tax_quote_ic_idx     ON tax_quote (intercompany_link_id);
CREATE INDEX tax_quote_entity_idx ON tax_quote (entity_id, ship_to_jurisdiction, determined_at);

CREATE TABLE tax_quote_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_quote_id       UUID NOT NULL REFERENCES tax_quote(id),
    order_line_id      UUID,
    product_variant_id UUID,
    line_ref           TEXT,
    tax_category_code  TEXT,
    hs_code            TEXT,
    qty                INTEGER NOT NULL DEFAULT 0,
    taxable_amount     NUMERIC(18,4) NOT NULL,
    line_tax_total     NUMERIC(18,4) NOT NULL,
    effective_rate_pct NUMERIC(9,4) NOT NULL,
    reverse_charge     BOOLEAN NOT NULL DEFAULT false,
    regime_code        TEXT,
    components         JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tax_quote_line_quote_idx ON tax_quote_line (tax_quote_id);
CREATE INDEX tax_quote_line_ol_idx    ON tax_quote_line (order_line_id);

-- 9. Intrastat / EC sales — EU statistical + recapitulative reporting. DORMANT year-1 (UK-only, post-Brexit GB);
--    tables exist so turning them on for the first EU operating entity is config + projection, no schema change.
CREATE TABLE intrastat_declaration (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID NOT NULL REFERENCES entity(id),
    jurisdiction CHAR(2) NOT NULL,
    period_key   TEXT NOT NULL,
    flow         TEXT NOT NULL,                  -- arrival/dispatch
    status       TEXT NOT NULL DEFAULT 'open',   -- open/submitted
    submitted_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE intrastat_line (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    declaration_id        UUID NOT NULL REFERENCES intrastat_declaration(id),
    hs_code               TEXT NOT NULL,
    partner_country       CHAR(2),
    nature_of_transaction TEXT,
    net_mass_kg           NUMERIC(12,3),
    invoice_value         NUMERIC(18,4) NOT NULL,
    currency              CHAR(3) NOT NULL,
    qty                   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX intrastat_line_idx ON intrastat_line (declaration_id, hs_code);
CREATE TABLE ec_sales_line (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id       UUID NOT NULL REFERENCES entity(id),
    period_key      TEXT NOT NULL,
    customer_vat_id TEXT NOT NULL,
    customer_country CHAR(2),
    indicator       TEXT NOT NULL DEFAULT 'goods',  -- goods/triangulation/services
    net_value       NUMERIC(18,4) NOT NULL,
    currency        CHAR(3) NOT NULL,
    status          TEXT NOT NULL DEFAULT 'open'
);
CREATE INDEX ec_sales_line_idx ON ec_sales_line (entity_id, period_key);
