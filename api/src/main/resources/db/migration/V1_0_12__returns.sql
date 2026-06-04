-- Returns / RMA — first-class (doc 09). Money reverses by reversing transfers at specific batch cost;
-- serials never silently re-enter sellable stock; every transition emits a return.* event.

ALTER TABLE "order" ADD COLUMN origin_rma_id UUID;

CREATE TABLE reason_code (
    code                 TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    category             TEXT NOT NULL,
    default_disposition  TEXT,
    counts_against_supplier BOOLEAN NOT NULL DEFAULT false,
    status               TEXT NOT NULL DEFAULT 'active'
);
INSERT INTO reason_code (code, name, category, default_disposition, counts_against_supplier) VALUES
    ('faulty','Faulty','fault','refurbish',true), ('doa','Dead on arrival','fault','return_to_supplier',true),
    ('not_as_described','Not as described','customer','assess',false), ('damaged_in_transit','Damaged in transit','logistics','scrap',false),
    ('changed_mind','Changed mind','customer','restock',false), ('wrong_item','Wrong item','logistics','restock',false),
    ('over_shipment','Over-shipment','logistics','restock',false), ('warranty_fault','Warranty fault','fault','return_to_supplier',true),
    ('goodwill','Goodwill','commercial','assess',false), ('recall','Recall','fault','scrap',true);

CREATE TABLE return_type_rule (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type               TEXT NOT NULL,
    entity_id          UUID,
    market_id          UUID,
    refund_basis       TEXT NOT NULL,
    restocking_fee_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    return_window_days INTEGER,
    issues_replacement BOOLEAN NOT NULL DEFAULT false,
    replacement_priced BOOLEAN NOT NULL DEFAULT false,
    default_disposition TEXT NOT NULL,
    commission_treatment TEXT NOT NULL,
    warranty_effect    TEXT NOT NULL,
    requires_memo      BOOLEAN NOT NULL DEFAULT false,
    approval_threshold NUMERIC(18,4),
    version            INTEGER NOT NULL DEFAULT 1,
    effective_from     TIMESTAMPTZ NOT NULL DEFAULT '2000-01-01',
    effective_to       TIMESTAMPTZ
);
INSERT INTO return_type_rule (type, refund_basis, restocking_fee_pct, return_window_days, issues_replacement, replacement_priced, default_disposition, commission_treatment, warranty_effect, requires_memo, approval_threshold) VALUES
    ('full_unit','line_value',0,30,false,false,'assess','claw','void',false,5000),
    ('part_only','component_value',0,30,false,false,'assess','retain','none',false,NULL),
    ('multi_unit','line_value',0,30,false,false,'assess','claw','void',false,5000),
    ('dead_on_arrival','full',0,NULL,true,false,'return_to_supplier','claw','fresh_on_replacement',false,NULL),
    ('warranty_replacement','none',0,NULL,true,false,'return_to_supplier','retain','draw_down',false,NULL),
    ('goodwill','per_approval',0,NULL,false,false,'assess','per_approval','per_approval',true,NULL);

CREATE TABLE rma (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rma_no             TEXT UNIQUE NOT NULL,
    order_id           UUID NOT NULL REFERENCES "order"(id),
    entity_id          UUID,
    sold_to_party_id   UUID,
    bill_to_party_id   UUID,
    channel_id         UUID,
    market_id          UUID,
    type               TEXT NOT NULL,
    scope              TEXT NOT NULL,
    reason_code        TEXT REFERENCES reason_code(code),
    refund_currency    CHAR(3) NOT NULL,
    refund_amount      NUMERIC(18,4),
    restocking_fee     NUMERIC(18,4) NOT NULL DEFAULT 0,
    replacement_order_id UUID REFERENCES "order"(id),
    credit_note_id     UUID,
    status             TEXT NOT NULL DEFAULT 'raised',
    requested_by       UUID,
    approved_by        UUID,
    approval_memo_ref  TEXT,
    assessed_by        UUID,
    received_at        TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ,
    tb_reversal_group  TEXT,
    attributes         JSONB NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rma_order_idx ON rma (order_id);
CREATE INDEX rma_status_idx ON rma (status);
CREATE SEQUENCE rma_no_seq START 1000;

CREATE TABLE rma_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rma_id             UUID NOT NULL REFERENCES rma(id) ON DELETE CASCADE,
    order_line_id      UUID NOT NULL REFERENCES order_line(id),
    tranche_id         UUID REFERENCES delivery_tranche(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    serial_unit_id     UUID REFERENCES serial_unit(id),
    component_ref      TEXT,
    qty                INTEGER NOT NULL DEFAULT 1,
    reason_code        TEXT REFERENCES reason_code(code),
    condition_grade    TEXT,
    disposition        TEXT,
    lot_batch_id       UUID REFERENCES lot_batch(id),
    unit_landed_cost   NUMERIC(18,4),
    line_refund_amount NUMERIC(18,4),
    commission_entry_id UUID,
    restock_location_id UUID REFERENCES location(id),
    status             TEXT NOT NULL DEFAULT 'expected'
);
CREATE INDEX rma_line_rma_idx ON rma_line (rma_id);

CREATE TABLE return_disposition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rma_line_id     UUID NOT NULL REFERENCES rma_line(id),
    serial_unit_id  UUID REFERENCES serial_unit(id),
    disposition     TEXT NOT NULL,
    from_status     TEXT NOT NULL,
    to_status       TEXT NOT NULL,
    location_id     UUID REFERENCES location(id),
    stock_movement_id UUID,
    supplier_claim_ref TEXT,
    evidence_ref    JSONB,
    actor_user_id   UUID,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credit_note (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rma_id           UUID NOT NULL REFERENCES rma(id),
    order_id         UUID NOT NULL REFERENCES "order"(id),
    order_invoice_id UUID REFERENCES order_invoice(id),
    credit_note_no   TEXT UNIQUE NOT NULL,
    bill_to_party_id UUID,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_ex_vat     NUMERIC(18,4) NOT NULL,
    vat_total        NUMERIC(18,4) NOT NULL,
    total_inc_vat    NUMERIC(18,4) NOT NULL,
    tax_regime       TEXT,
    refund_method    TEXT NOT NULL,
    stripe_refund_id TEXT,
    xero_credit_note_id TEXT,
    tb_transfer_id   TEXT,
    status           TEXT NOT NULL DEFAULT 'issued'
);
CREATE SEQUENCE credit_note_no_seq START 1000;
