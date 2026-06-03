-- Catalogue + variants + ADLP pricing (doc 02 §D/§E, doc 04 §Pricing).

CREATE TABLE tax_regime (
    code         TEXT PRIMARY KEY,
    rate_percent NUMERIC(7,4) NOT NULL,
    jurisdiction CHAR(2),
    kind         TEXT NOT NULL DEFAULT 'standard'
);
INSERT INTO tax_regime (code, rate_percent, jurisdiction, kind) VALUES
    ('GB_STANDARD', 20.0000, 'GB', 'standard'),
    ('IE_STANDARD', 23.0000, 'IE', 'standard'),
    ('DE_STANDARD', 19.0000, 'DE', 'standard'),
    ('TAX_FREE',     0.0000, NULL, 'zero'),
    ('REVERSE_CHARGE', 0.0000, NULL, 'reverse_charge'),
    ('IMPORT',       0.0000, NULL, 'import');

CREATE TABLE product_family (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       TEXT UNIQUE NOT NULL,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_variant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id       UUID NOT NULL REFERENCES product_family(id),
    sku             TEXT UNIQUE NOT NULL,
    trade_sku       TEXT,
    mrp_sku         TEXT,
    length_m        NUMERIC(4,1),
    colour          TEXT,
    connector_type  TEXT,
    generation      TEXT NOT NULL,
    is_serialised   BOOLEAN NOT NULL DEFAULT true,
    is_kit          BOOLEAN NOT NULL DEFAULT false,
    uom             TEXT NOT NULL DEFAULT 'each',
    hs_code         TEXT,
    std_cost        NUMERIC(18,4),
    warranty_months INTEGER,
    status          TEXT NOT NULL DEFAULT 'active',
    attributes      JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX product_variant_family_idx ON product_variant (family_id);

-- The ADLP table: runtime, versioned, governed. Changing a price is a UI action (insert a new version),
-- never a migration. `surface='inter_entity'` rows are field-layer-mapped to the inter_entity layer.
CREATE TABLE price_rule (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    surface            TEXT NOT NULL,
    product_variant_id UUID REFERENCES product_variant(id),
    bundle_id          UUID REFERENCES product_variant(id),
    channel_id         UUID,
    market_id          UUID,
    entity_id          UUID,
    currency           CHAR(3) NOT NULL,
    tax_regime         TEXT REFERENCES tax_regime(code),
    authorised_price   NUMERIC(18,4) NOT NULL,
    max_discount_pct   NUMERIC(5,2) NOT NULL DEFAULT 0,
    min_qty            INTEGER NOT NULL DEFAULT 1,
    from_entity_id     UUID,
    to_entity_id       UUID,
    tp_method          TEXT,
    tp_markup_pct      NUMERIC(7,4),
    version            INTEGER NOT NULL DEFAULT 1,
    effective_from     TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to       TIMESTAMPTZ,
    status             TEXT NOT NULL DEFAULT 'draft',
    owner_user_id      UUID,
    approved_by        UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX price_rule_resolution_idx
    ON price_rule (surface, product_variant_id, channel_id, market_id, currency, status, effective_from DESC);

-- Append-only governance log; also emitted as pricing.rule.changed.
CREATE TABLE pricing_change_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    price_rule_id UUID NOT NULL REFERENCES price_rule(id),
    before        JSONB,
    after         JSONB,
    actor         UUID,
    approved_by   UUID,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
