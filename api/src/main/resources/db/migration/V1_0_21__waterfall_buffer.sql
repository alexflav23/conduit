-- The demand→revenue waterfall + the contract-manufacturer production reality + the parts buffer.
-- The vital end-to-end chain: sales forecast → CM commitment → CM produced → delivered → shipped → revenue.
-- Each stage is a DISTINCT quantity (they don't equate); the immutable ledger proves the shipped→revenue tail.

-- The frozen-window contractual change tolerance (often < 20%) — configurable per contract.
ALTER TABLE supply_commitment_policy ADD COLUMN frozen_tolerance_pct NUMERIC(6,2) NOT NULL DEFAULT 0;

-- What the contract manufacturer ACTUALLY produced against a firm commitment. A shortfall (produced < committed)
-- extends unmet demand into the next window (carried_to_date) — the locked production forecast may not be met.
CREATE TABLE production_actual (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NOT NULL REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    target_date        DATE NOT NULL,
    committed_qty      INTEGER NOT NULL,
    produced_qty       INTEGER NOT NULL,
    shortfall_qty      INTEGER NOT NULL,            -- max(committed - produced, 0); carried forward
    carried_to_date    DATE NULL,                   -- the window the shortfall rolled into
    reported_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, product_variant_id, target_date)
);
CREATE INDEX production_actual_target_idx ON production_actual (supplier_id, target_date);

-- A divergence between sales reality and a frozen firm PO — raised (not silently rejected) when an automated
-- trigger or sales input would change demand for a frozen SKU/week. Surfaced for action; the PO can't move.
CREATE TABLE commitment_warning (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NULL REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    target_date        DATE NOT NULL,
    zone               TEXT NOT NULL,               -- frozen | flex
    committed_qty      INTEGER NOT NULL,
    demand_qty         INTEGER NOT NULL,
    delta              INTEGER NOT NULL,
    source             TEXT NOT NULL,               -- sales_input | automated | order
    severity           TEXT NOT NULL,               -- warn | block
    message            TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX commitment_warning_open_idx ON commitment_warning (created_at DESC);

-- Parts buffer: Volex holds contractually-guaranteed COMPONENTS on site (sized to P50 demand), NOT finished
-- goods — FG would become an invoice for us. Conversion to FG is the liability/invoice trigger. Configurable.
CREATE TABLE component_buffer_policy (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NULL REFERENCES supplier(id),
    product_variant_id UUID NULL REFERENCES product_variant(id),
    target_units       INTEGER NOT NULL,            -- buffer size in finished-good-equivalent units
    basis              TEXT NOT NULL DEFAULT 'p50',  -- p50 | manual
    active             BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE component_buffer (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NOT NULL REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    parts_on_site      INTEGER NOT NULL DEFAULT 0,  -- FG-equivalent parts held as buffer (not invoiced)
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, product_variant_id)
);
