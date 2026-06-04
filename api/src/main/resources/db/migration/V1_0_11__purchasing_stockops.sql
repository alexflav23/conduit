-- Purchasing/receiving, supply planning, and maker-checker stock operations (doc 02 §H, doc 04 §Stock ops).

CREATE TABLE purchase_order (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_no        TEXT UNIQUE NOT NULL,
    entity_id    UUID REFERENCES entity(id),
    supplier_id  UUID REFERENCES supplier(id),
    type         TEXT NOT NULL DEFAULT 'external',
    status       TEXT NOT NULL DEFAULT 'open',
    order_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expected_date DATE,
    txn_currency CHAR(3) NOT NULL,
    fx_rate      NUMERIC(18,8),
    total        NUMERIC(18,4) NOT NULL DEFAULT 0,
    tb_transfer_id TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE SEQUENCE po_no_seq START 1000;

CREATE TABLE po_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_id              UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    qty                INTEGER NOT NULL,
    unit_cost          NUMERIC(18,4) NOT NULL,
    qty_received       INTEGER NOT NULL DEFAULT 0,
    expected_date      DATE
);

CREATE TABLE goods_receipt (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_id         UUID NOT NULL REFERENCES purchase_order(id),
    date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id UUID
);

CREATE TABLE goods_receipt_line (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grn_id       UUID NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    po_line_id   UUID NOT NULL REFERENCES po_line(id),
    qty_received INTEGER NOT NULL,
    serials      TEXT[],
    lot_batch_id UUID REFERENCES lot_batch(id)
);

CREATE TABLE landed_cost_component (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grn_id          UUID REFERENCES goods_receipt(id),
    po_id           UUID REFERENCES purchase_order(id),
    type            TEXT NOT NULL,
    amount          NUMERIC(18,4) NOT NULL,
    currency        CHAR(3) NOT NULL,
    allocation_basis TEXT
);

CREATE TABLE stock_count (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id     UUID,
    location_id   UUID NOT NULL REFERENCES location(id),
    type          TEXT NOT NULL DEFAULT 'cycle',
    status        TEXT NOT NULL DEFAULT 'open',
    counted_by    UUID,
    approved_by   UUID,
    scheduled_for DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stock_count_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    count_id           UUID NOT NULL REFERENCES stock_count(id) ON DELETE CASCADE,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    system_qty         INTEGER NOT NULL,
    counted_qty        INTEGER NOT NULL,
    variance           INTEGER NOT NULL,
    serials_scanned    TEXT[]
);

CREATE TABLE stock_transfer (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_location_id   UUID NOT NULL REFERENCES location(id),
    to_location_id     UUID NOT NULL REFERENCES location(id),
    entity_id          UUID,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    qty                INTEGER NOT NULL,
    serials            TEXT[],
    status             TEXT NOT NULL DEFAULT 'requested',
    dispatched_at      TIMESTAMPTZ,
    received_at        TIMESTAMPTZ,
    requested_by       UUID,
    approved_by        UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stock_adjustment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id          UUID,
    location_id        UUID NOT NULL REFERENCES location(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    serials            TEXT[],
    qty                INTEGER NOT NULL,
    kind               TEXT NOT NULL,
    reason_code        TEXT NOT NULL,
    evidence_ref       JSONB,
    status             TEXT NOT NULL DEFAULT 'pending_approval',
    requested_by       UUID,
    approved_by        UUID,
    tb_transfer_id     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE replenishment_suggestion (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id          UUID,
    location_id        UUID,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    net_requirement    INTEGER NOT NULL,
    suggested_qty      INTEGER NOT NULL,
    supplier_id        UUID,
    required_by        DATE,
    suggested_order_date DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
