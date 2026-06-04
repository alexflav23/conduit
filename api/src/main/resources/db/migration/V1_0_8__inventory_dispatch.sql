-- Inventory, serials, allocation, dispatch (doc 02 §F/§G, doc 04 §ATP). On-hand is the immutable sum of
-- stock_movement; allocation is concurrency-safe via row locks; serials are captured at dispatch.

CREATE TABLE location (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id  UUID REFERENCES entity(id),
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    address    JSONB,
    type       TEXT NOT NULL DEFAULT 'warehouse',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stock_item (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id          UUID,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    location_id        UUID NOT NULL REFERENCES location(id),
    qty_on_hand        INTEGER NOT NULL DEFAULT 0,
    qty_allocated      INTEGER NOT NULL DEFAULT 0,
    qty_incoming       INTEGER NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, product_variant_id, location_id)
);

-- Append-only; on-hand reconstructs as the sum of movements. Corrections are new signed rows, never edits.
CREATE TABLE stock_movement (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type               TEXT NOT NULL,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    location_id        UUID NOT NULL REFERENCES location(id),
    entity_id          UUID,
    qty                INTEGER NOT NULL,
    ref_type           TEXT,
    ref_id             UUID,
    reason_code        TEXT,
    actor_user_id      UUID,
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX stock_movement_variant_idx ON stock_movement (product_variant_id, location_id);

-- serial_unit lands here (lot_batch_id stays NULL until M7 batches exist).
CREATE TABLE serial_unit (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_no          TEXT UNIQUE NOT NULL,
    generation         TEXT NOT NULL,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    lot_batch_id       UUID,
    status             TEXT NOT NULL DEFAULT 'in_stock',
    entity_id          UUID,
    location_id        UUID REFERENCES location(id),
    order_line_id      UUID REFERENCES order_line(id),
    dispatch_id        UUID,
    company_id         UUID,
    installer_user_id  TEXT,
    owner_keycloak_id  TEXT,
    activated_at       TIMESTAMPTZ,
    warranty_end       DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX serial_unit_variant_loc_idx ON serial_unit (product_variant_id, location_id, status);

CREATE TABLE carrier (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT NOT NULL,
    type           TEXT NOT NULL DEFAULT 'parcel',
    service_levels JSONB
);

CREATE TABLE allocation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_line_id  UUID NOT NULL REFERENCES order_line(id) ON DELETE CASCADE,
    tranche_id     UUID REFERENCES delivery_tranche(id) ON DELETE CASCADE,
    location_id    UUID NOT NULL REFERENCES location(id),
    serial_unit_id UUID REFERENCES serial_unit(id),
    qty            INTEGER NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX allocation_line_idx ON allocation (order_line_id);

CREATE TABLE dispatch (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_no  TEXT UNIQUE NOT NULL,
    order_id     UUID NOT NULL REFERENCES "order"(id),
    tranche_id   UUID REFERENCES delivery_tranche(id),
    date         TIMESTAMPTZ NOT NULL DEFAULT now(),
    carrier_id   UUID REFERENCES carrier(id),
    tracking_no  TEXT,
    destination  JSONB,
    status       TEXT NOT NULL DEFAULT 'created',
    otd_due      DATE,
    delivered_at TIMESTAMPTZ,
    tb_transfer_id TEXT
);
CREATE INDEX dispatch_order_idx ON dispatch (order_id);

CREATE TABLE dispatch_line (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_id   UUID NOT NULL REFERENCES dispatch(id) ON DELETE CASCADE,
    order_line_id UUID NOT NULL REFERENCES order_line(id),
    tranche_id    UUID REFERENCES delivery_tranche(id),
    qty           INTEGER NOT NULL
);

CREATE SEQUENCE dispatch_no_seq START 1000;

-- Auto-issued on delivery (ASC 606): one invoice per drop (doc 02 §F, doc 04 §Ledger).
CREATE TABLE order_invoice (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES "order"(id),
    tranche_id      UUID REFERENCES delivery_tranche(id),
    invoice_no      TEXT UNIQUE NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_ex_vat    NUMERIC(18,4) NOT NULL DEFAULT 0,
    vat_total       NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_inc_vat   NUMERIC(18,4) NOT NULL DEFAULT 0,
    tax_regime      TEXT,
    xero_invoice_id TEXT,
    email_state     TEXT,
    tb_transfer_id  TEXT
);
CREATE INDEX order_invoice_order_idx ON order_invoice (order_id);
CREATE SEQUENCE invoice_no_seq START 1000;
