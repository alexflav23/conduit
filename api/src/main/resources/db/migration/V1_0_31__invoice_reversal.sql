-- M13-Void — invoice invalidation (ASC 606). The immutable-log rule: an invoice is never edited or deleted.
-- Invalidating it is an APPEND-ONLY reversal that negates the recognition on the TigerBeetle ledger and stamps a
-- marker everywhere the original landed (the invoice row, its documents, downstream feeds). This is how mistakes,
-- cancellations, refunds and post-invoice corrections are handled without ever rewriting history.

-- Markers on the invoice row (status flips to 'void'; the row is otherwise unchanged).
ALTER TABLE order_invoice ADD COLUMN voided_at             TIMESTAMPTZ;
ALTER TABLE order_invoice ADD COLUMN void_reason           TEXT;
ALTER TABLE order_invoice ADD COLUMN void_kind             TEXT;   -- mistake / cancellation / refund / correction
ALTER TABLE order_invoice ADD COLUMN replaced_by_invoice_id UUID REFERENCES order_invoice(id);  -- correction → re-invoice

-- The invalidation marker carried by the original document (WORM: the bytes never change, only the row is stamped).
-- The credit note that supersedes it is a NEW document linked via the existing document.corrects_document_id.
ALTER TABLE document ADD COLUMN voided_at   TIMESTAMPTZ;
ALTER TABLE document ADD COLUMN void_reason TEXT;

-- The immutable reversal fact (one per invalidated invoice). Carries the reversed amounts and the reversing ledger
-- transfer ids, so the reversal is provable and re-performable. Deterministic id = the void event id.
CREATE TABLE invoice_reversal (
    id                     UUID PRIMARY KEY,                         -- deterministic: nameUUID("invoice-void:" + order_invoice_id)
    order_invoice_id       UUID NOT NULL UNIQUE REFERENCES order_invoice(id),  -- one reversal per invoice (idempotency)
    order_id               UUID NOT NULL REFERENCES "order"(id),
    dispatch_id            UUID REFERENCES dispatch(id),
    invoice_no             TEXT NOT NULL,
    kind                   TEXT NOT NULL,                            -- mistake / cancellation / refund / correction
    reason                 TEXT NOT NULL,
    currency               CHAR(3) NOT NULL,
    reversed_revenue_ex_vat NUMERIC(18,4) NOT NULL,
    reversed_vat           NUMERIC(18,4) NOT NULL,
    reversed_cogs          NUMERIC(18,4) NOT NULL,
    rev_ar_transfer_id     NUMERIC(39,0),                            -- DR Revenue / CR AR (ex-VAT)
    rev_vat_transfer_id    NUMERIC(39,0),                            -- DR VAT / CR AR
    rev_cogs_transfer_id   NUMERIC(39,0),                            -- DR INV / CR COGS
    replacement_invoice_id UUID REFERENCES order_invoice(id),
    created_by             TEXT NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX invoice_reversal_order_idx ON invoice_reversal (order_id);

-- A credit-note numbering series + GB/en template for the year-1 UK entity (mirrors the invoice seed in V1_0_27).
INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'credit_note', 'GB', 'HV-UK-CN', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('credit_note', 'GB', 'en',
        'CREDIT NOTE for {{corrects_number}}\n{{supplier_name}}\nBill to: {{payer_name}}\nReason: {{reason}}\nTotal credited: {{total}}',
        '{"vat_note":"VAT credit note"}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;
