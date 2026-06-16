-- Sales-order commitment / backlog (M4, doc 03 §order.placed → "ledger commitment"). An order books NOTHING to
-- the GL at placement under ASC 606 — control (and revenue/AR/COGS) transfers at dispatch, which the recognition
-- pipeline already posts. The commitment is the BACKLOG memo: the obligation committed at placement, captured
-- immutably (amendments arrive as their own events) and drawn down as the order's dispatches recognise. This is
-- the baseline reality validated in shadow (committed = recognised + open) before Conduit becomes system of record.
CREATE TABLE order_commitment (
    order_id          UUID PRIMARY KEY REFERENCES "order"(id),
    entity_id         UUID,
    currency          TEXT NOT NULL,
    committed_ex_vat  NUMERIC(18,4) NOT NULL DEFAULT 0,
    committed_vat     NUMERIC(18,4) NOT NULL DEFAULT 0,
    committed_inc_vat NUMERIC(18,4) NOT NULL DEFAULT 0,
    placed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status            TEXT NOT NULL DEFAULT 'open',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX order_commitment_entity_idx ON order_commitment (entity_id);
