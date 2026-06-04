-- Revenue recognition on dispatch (ASC 606 — control transfers on delivery, doc 04 §Ledger, doc 13 §GL).
-- H6Q runs AHEAD of shipping (forward demand); this is the OTHER side — the actuals view. When a unit is
-- actually dispatched/delivered, control transfers and revenue is recognised, posted to the TigerBeetle
-- immutable ledger so every recognised figure is provable end-to-end. This is the "clear order for revenue
-- recognition" generated inside Conduit once something has actually shipped.
--
-- One row per delivered dispatch (the recognition event), carrying the deterministic ledger transfer ids:
--   DR AR:<bill_to>  / CR Revenue:<entity>   (sale, ex-VAT)
--   DR AR:<bill_to>  / CR VAT:<entity>       (output VAT)
--   DR COGS:<entity> / CR INV:<entity>       (cost of sales at SPECIFIC batch landed cost — doc 02 §G)
-- so debits == credits per currency by construction, and the recognised revenue/COGS trace to the dispatch.
CREATE TABLE revenue_recognition (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_id      UUID NOT NULL UNIQUE REFERENCES dispatch(id),  -- idempotency: one recognition per dispatch
    order_id         UUID NOT NULL REFERENCES "order"(id),
    invoice_no       TEXT NULL,
    entity_id        UUID NULL,
    currency         CHAR(3) NOT NULL,
    revenue_ex_vat   NUMERIC(18,4) NOT NULL,
    vat              NUMERIC(18,4) NOT NULL,
    cogs             NUMERIC(18,4) NOT NULL,
    gross_margin     NUMERIC(18,4) NOT NULL,                        -- revenue_ex_vat - cogs (proved by the ledger)
    ar_transfer_id   NUMERIC(39,0) NULL,
    vat_transfer_id  NUMERIC(39,0) NULL,
    cogs_transfer_id NUMERIC(39,0) NULL,
    recognized_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX revenue_recognition_order_idx ON revenue_recognition (order_id);
