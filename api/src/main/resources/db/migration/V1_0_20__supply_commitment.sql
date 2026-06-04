-- Supply-commitment time fences (the contract manufacturer's "firm commitment window"). Volex (and any CM) has
-- decision gates: a frozen window inside the production lead time (the PO is firm — changing it incurs penalty
-- interest against the 6-month buffer), a flex window where the forecast may still move within tolerance (the
-- 20% margin), and a free window beyond. doc 12 (H6Q) feeds forward demand into this; this is the buy-side gate.
CREATE TABLE supply_commitment_policy (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NULL REFERENCES supplier(id),  -- NULL = the default policy
    name               TEXT NOT NULL,
    lead_time_days     INTEGER NOT NULL,                   -- within this horizon the PO is FROZEN (≈ production lead time)
    flex_horizon_days  INTEGER NOT NULL,                   -- out to here, change allowed within flex_tolerance_pct
    flex_tolerance_pct NUMERIC(6,2) NOT NULL,              -- the ± band in the flex window (e.g. 20.00)
    buffer_days        INTEGER NOT NULL DEFAULT 180,       -- the unsold-inventory buffer before penalty interest
    active             BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX supply_commitment_policy_supplier_idx ON supply_commitment_policy (supplier_id) WHERE active;

-- The firm PO commitment per SKU per target week (the "Confirmed Volex PO" by SKU by week). status records the
-- fence zone it was placed in; changing a frozen commitment requires escalation and is liability-bearing.
CREATE TABLE supply_commitment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NOT NULL REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    target_date        DATE NOT NULL,                      -- the delivery week/period this commitment covers
    qty                INTEGER NOT NULL,
    zone               TEXT NOT NULL,                      -- frozen | flex | free (zone at time of (re)commit)
    placed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, product_variant_id, target_date)
);
CREATE INDEX supply_commitment_target_idx ON supply_commitment (supplier_id, target_date);

-- A sensible default policy: 8-week frozen production lead time, 6-month flex horizon, 20% flex tolerance.
INSERT INTO supply_commitment_policy (supplier_id, name, lead_time_days, flex_horizon_days, flex_tolerance_pct, buffer_days)
VALUES (NULL, 'Default contract-manufacturer fence', 56, 180, 20.00, 180);
