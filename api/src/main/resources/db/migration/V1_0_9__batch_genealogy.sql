-- Batch / landed cost / serial genealogy (doc 02 §G/§H, doc 04 §Ledger/§FX). Cost is strictly per-lot
-- (specific-identification): the contract manufacturer can reprice lot-to-lot, and freight/duty/FX all move.

CREATE TABLE supplier (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT NOT NULL,
    billing_currency CHAR(3) NOT NULL,
    supplier_entity  TEXT,
    lead_time_days   INTEGER,
    terms            JSONB,
    contacts         JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lot_batch (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_no           TEXT NOT NULL,
    supplier_id        UUID REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    manufactured_date  DATE,
    received_date      DATE,
    qty                INTEGER NOT NULL,
    unit_cost_usd      NUMERIC(18,4) NOT NULL,          -- per-lot Luxshare price, always USD
    fx_rate            NUMERIC(18,8) NOT NULL,          -- USD -> entity functional; rate actually applied
    fx_basis           TEXT NOT NULL,                   -- spot / hedged
    hedge_ref          TEXT,
    shipping_alloc     NUMERIC(18,4) NOT NULL DEFAULT 0,
    duty_alloc         NUMERIC(18,4) NOT NULL DEFAULT 0,
    landed_unit_cost   NUMERIC(18,4) NOT NULL,          -- = unit_cost_usd*fx_rate + per-unit shipping + per-unit duty
    currency           CHAR(3) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lot_batch_variant_idx ON lot_batch (product_variant_id);
CREATE UNIQUE INDEX lot_batch_no_idx ON lot_batch (batch_no, supplier_id);

-- Now that lot_batch exists, enforce the serial -> batch reference (serials predate batches in M6).
ALTER TABLE serial_unit
    ADD CONSTRAINT serial_unit_lot_batch_fk FOREIGN KEY (lot_batch_id) REFERENCES lot_batch(id);

-- Append-only genealogy timeline per unit.
CREATE TABLE unit_lifecycle_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_unit_id UUID NOT NULL REFERENCES serial_unit(id),
    event_type     TEXT NOT NULL,
    entity_id      UUID,
    location_id    UUID,
    ref_type       TEXT,
    ref_id         UUID,
    actor_user_id  UUID,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX unit_lifecycle_serial_idx ON unit_lifecycle_event (serial_unit_id, occurred_at);
