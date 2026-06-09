-- M13-Docs.9 (doc 25): associated / inbound documents. Complements doc 17's GENERATED `document` table (which has a
-- render_model because Conduit produced it) with documents Conduit RECEIVES — a customer's purchase order, the
-- signed supply agreement + schedules, delivery notes, certificates — each ASSOCIATED with the subject it belongs
-- to. Same WORM storage port + content_sha256 discipline; immutable (a correction is a new attachment that
-- supersedes via metadata). This closes the revenue-provenance chain: a recognised figure drills to the order, the
-- order to its source customer PO, the line's price agreement to the signed contract its tiers were entered from.

CREATE TABLE document_attachment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction      TEXT NOT NULL DEFAULT 'inbound',     -- inbound | uploaded
    kind           TEXT NOT NULL,                       -- customer_po | signed_contract | contract_schedule | certificate
                                                        -- | delivery_note | proof_of_delivery | correspondence | other
    subject_type   TEXT NOT NULL,                       -- order | party | price_agreement | dispatch | rma | invoice
    subject_id     UUID NOT NULL,
    filename       TEXT NOT NULL,
    content_type   TEXT NOT NULL,
    byte_size      BIGINT NOT NULL,
    storage_uri    TEXT NOT NULL,                       -- WORM object (S3 object-lock), shared bucket with doc 17
    content_sha256 TEXT NOT NULL,                       -- tamper-evidence + dedupe key
    external_ref   TEXT,                                -- the source's own number (e.g. "HK00547")
    source         TEXT NOT NULL DEFAULT 'upload',      -- upload | email_ingest | api
    uploaded_by    UUID,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    data_layer     TEXT,                                -- doc 05 layer tag — gates view/download
    metadata       JSONB NOT NULL DEFAULT '{}',         -- extracted fields (po_total, line count) for reconciliation
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- the same bytes attached to the same subject resolve to ONE attachment (idempotent re-upload)
    UNIQUE (subject_type, subject_id, content_sha256)
);
CREATE INDEX document_attachment_subject_idx ON document_attachment (subject_type, subject_id);
CREATE INDEX document_attachment_ref_idx ON document_attachment (external_ref);

-- The PO an order was created from (doc 25 §4) — the strong provenance link, beside customer_po_number.
ALTER TABLE "order" ADD COLUMN source_attachment_id UUID REFERENCES document_attachment(id);
