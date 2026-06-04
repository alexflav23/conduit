-- SKU mix (doc 12 §1.2; the spreadsheet's "Overall Product Sales Mix" + per-channel SKU mixes). Agents forecast
-- in unit counts, but Hypervolt sells the same model as many SKUs (colour × cable length × generation). A mix
-- splits a unit count into a per-SKU forecast from historical demand (or configured rules), so the raw H6Q
-- record stays per SKU. A channel/market-specific mix wins over the global default.
CREATE TABLE sku_mix (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT NOT NULL,
    scope_channel_id UUID NULL,     -- NULL = any channel
    scope_market_id  UUID NULL,     -- NULL = any market
    basis            TEXT NOT NULL DEFAULT 'historical', -- historical | manual_rule
    active           BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sku_mix_scope_idx ON sku_mix (active, scope_channel_id, scope_market_id) WHERE active;

CREATE TABLE sku_mix_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mix_id             UUID NOT NULL REFERENCES sku_mix(id) ON DELETE CASCADE,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    weight             NUMERIC(9,6) NOT NULL,  -- relative share; normalised at allocation time (need not sum to 1)
    UNIQUE (mix_id, product_variant_id)
);
