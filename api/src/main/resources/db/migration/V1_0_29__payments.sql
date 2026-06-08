-- M13 — payments / cash application. We OWN the order→cash lifecycle: a payment settles AR on the immutable
-- ledger (DR cash/clearing, CR AR), allocates to one or more invoices, and flips them open→part_paid→paid. The
-- cash waterfall self-corrects (paid invoices leave the open set). Stripe is one source feeding this; bank/manual
-- payments use the same path. (Ledger account codes Bank/StripeClearing/FeeExpense added in Ledgers.scala.)

CREATE TABLE payment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID REFERENCES entity(id),
    bill_to_party_id UUID NOT NULL REFERENCES party(id),
    currency         CHAR(3) NOT NULL,
    amount           NUMERIC(18,4) NOT NULL,
    method           TEXT NOT NULL,                 -- stripe / bank / card / credit_memo
    account_kind     TEXT NOT NULL,                 -- bank / stripe_clearing (which asset received the cash)
    external_ref     TEXT,                          -- stripe payment_intent id / bank remittance ref (idempotency)
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tb_transfer_id   NUMERIC(39,0),                 -- the DR cash / CR AR transfer
    status           TEXT NOT NULL DEFAULT 'applied',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- a source ref settles at most once (idempotent under webhook redelivery)
CREATE UNIQUE INDEX payment_external_idx ON payment (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX payment_party_idx ON payment (bill_to_party_id, received_at DESC);

-- one payment can settle several invoices (a central-billing master pays many branch invoices, doc 02 §C).
CREATE TABLE payment_allocation (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id       UUID NOT NULL REFERENCES payment(id),
    order_invoice_id UUID NOT NULL REFERENCES order_invoice(id),
    amount           NUMERIC(18,4) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_alloc_invoice_idx ON payment_allocation (order_invoice_id);

INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('payment','amount','commercial')
ON CONFLICT (object_type, field) DO NOTHING;

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'payment', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'payment', 'create', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
