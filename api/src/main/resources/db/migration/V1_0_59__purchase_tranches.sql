-- M9c — inbound tranches as first-class citizens (user spec): deliveries against OUR POs to the contract
-- manufacturers arrive in batches over different lanes (Volex Poland by truck; Luxshare Suzhou by rail/sea),
-- each tranche carrying ITS OWN inbound freight that must hit the ledger and the landed cost, and every
-- receipt rolls the SKU balance forward as an immutable as-of statement.

CREATE TABLE IF NOT EXISTS purchase_tranche (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_id                 UUID NOT NULL REFERENCES purchase_order (id),
    seq                   INT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'planned'
        CHECK (status IN ('planned', 'in_transit', 'received', 'cancelled')),
    transport_mode        TEXT NOT NULL CHECK (transport_mode IN ('truck', 'rail', 'sea', 'air')),
    origin_site           TEXT NOT NULL,
    carrier_ref           TEXT NULL,
    expected_ship_date    DATE NULL,
    expected_arrival_date DATE NULL,
    shipped_at            TIMESTAMPTZ NULL,
    received_at           TIMESTAMPTZ NULL,
    freight_amount        NUMERIC(14, 4) NOT NULL DEFAULT 0, -- the inbound shipping fee FOR THIS TRANCHE
    freight_currency      TEXT NOT NULL DEFAULT 'GBP',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (po_id, seq)
);

CREATE TABLE IF NOT EXISTS purchase_tranche_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tranche_id         UUID NOT NULL REFERENCES purchase_tranche (id),
    product_variant_id UUID NOT NULL REFERENCES product_variant (id),
    qty                NUMERIC(14, 4) NOT NULL CHECK (qty > 0),
    -- the roll-forward snapshot, written at receipt: opening + this tranche − interim issues = balance after.
    -- Immutable once set; the live balance is always derivable from stock_movement, this is the as-of record.
    balance_after      NUMERIC(14, 4) NULL,
    UNIQUE (tranche_id, product_variant_id)
);

ALTER TABLE goods_receipt ADD COLUMN IF NOT EXISTS tranche_id UUID NULL REFERENCES purchase_tranche (id);
ALTER TABLE landed_cost_component ADD COLUMN IF NOT EXISTS tranche_id UUID NULL REFERENCES purchase_tranche (id);

CREATE INDEX IF NOT EXISTS purchase_tranche_po_idx ON purchase_tranche (po_id);
CREATE INDEX IF NOT EXISTS purchase_tranche_line_tranche_idx ON purchase_tranche_line (tranche_id);

-- The rolling commitment ladder (the 10-week upgrade): every issued update to a CM is a versioned, immutable
-- statement — firm zone locked, flex zone ±tolerance, indicative horizon — re-issued on forecast deviation
-- (signal) or the contractual calendar (backstop), never silently changed.
CREATE TABLE IF NOT EXISTS cm_commitment (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id  UUID NOT NULL REFERENCES supplier (id),
    version      INT NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    firm_until   DATE NOT NULL,        -- the contractual locked window
    flex_until   DATE NOT NULL,        -- committed ±tolerance
    tolerance_pct NUMERIC(5, 2) NOT NULL DEFAULT 20,
    reason       TEXT NOT NULL,        -- 'calendar' | 'forecast_deviation' | 'manual'
    UNIQUE (supplier_id, version)
);

CREATE TABLE IF NOT EXISTS cm_commitment_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commitment_id      UUID NOT NULL REFERENCES cm_commitment (id),
    product_variant_id UUID NOT NULL REFERENCES product_variant (id),
    period_month       DATE NOT NULL,
    qty                NUMERIC(14, 4) NOT NULL,
    zone               TEXT NOT NULL CHECK (zone IN ('firm', 'flex', 'indicative')),
    UNIQUE (commitment_id, product_variant_id, period_month)
);
