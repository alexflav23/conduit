-- S2.1 line items: HubSpot deal line-items as related lifecycle entities of a deal (the per-deal product
-- breakdown). Staging-style natural key on the HubSpot line_item id (like hubspot_*_raw); deal_id links it to
-- deal_snapshot. Fed live by the HubSpot connector (line_item → deal via v4 associations) → the desk can render
-- a deal's lines alongside its company attribution. Idempotent on line_item_id.
CREATE TABLE deal_line (
    line_item_id TEXT PRIMARY KEY,
    deal_id      TEXT,
    sku          TEXT,
    name         TEXT,
    qty          NUMERIC(18, 4),
    unit_price   NUMERIC(18, 4),
    amount       NUMERIC(18, 4),
    first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX deal_line_deal_idx ON deal_line (deal_id);
