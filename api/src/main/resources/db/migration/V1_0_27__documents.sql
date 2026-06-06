-- M13 — document generation (spec doc 17). Legally-required artefacts (invoice/credit_note/proforma/
-- packing_list/commercial_invoice/statement) as RENDERED PROJECTIONS of typed truth: numbers are read from
-- order_invoice (never recomputed), numbering is gapless + immutable + never reused, and a finalised fiscal
-- document is WORM (no-update after finalise; corrections are new documents).

-- §2.1 template registry — keyed by (document_type, jurisdiction, locale[, entity]); resolution with fallback.
CREATE TABLE document_template (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type  TEXT NOT NULL,
    jurisdiction   CHAR(2),                              -- NULL = jurisdiction-agnostic fallback
    locale         TEXT,                                 -- NULL = locale-agnostic fallback
    entity_id      UUID REFERENCES entity(id),
    body           TEXT NOT NULL,                         -- logic-light markup over the render model (§5.1)
    legal_clauses  JSONB NOT NULL DEFAULT '{}',
    required_fields TEXT[] NOT NULL DEFAULT '{}',         -- mandated render-model fields (finalise gate, §2.4)
    status         TEXT NOT NULL DEFAULT 'active',        -- draft/active/superseded
    version        INTEGER NOT NULL DEFAULT 1,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX document_template_resolve_idx ON document_template (document_type, jurisdiction, locale, status, effective_from DESC);

-- §3.1 the gapless allocator — one row per (entity, document_type[, jurisdiction]); current_seq advances FOR UPDATE.
CREATE TABLE document_number_series (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id     UUID NOT NULL REFERENCES entity(id),
    document_type TEXT NOT NULL,
    jurisdiction  CHAR(2),
    series_code   TEXT NOT NULL,
    format        TEXT NOT NULL,                          -- e.g. {series}-{yyyy}-{seq:06d}
    period_scope  TEXT NOT NULL DEFAULT 'continuous',     -- continuous / annual
    current_seq   BIGINT NOT NULL DEFAULT 0,
    status        TEXT NOT NULL DEFAULT 'active',
    UNIQUE (entity_id, document_type, jurisdiction, series_code)
);

-- §3.2 the allocation ledger (append-only) — every number recorded; voids keep their seq (never reused).
CREATE TABLE document_number (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    series_id        UUID NOT NULL REFERENCES document_number_series(id),
    seq              BIGINT NOT NULL,
    formatted_number TEXT NOT NULL,
    document_id      UUID,
    status           TEXT NOT NULL,                        -- allocated/issued/voided
    voided_reason    TEXT,
    allocated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (series_id, seq),
    UNIQUE (series_id, formatted_number)
);
CREATE INDEX document_number_doc_idx ON document_number (document_id);

-- §4.1 the WORM document record.
CREATE TABLE document (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type         TEXT NOT NULL,
    entity_id             UUID NOT NULL REFERENCES entity(id),
    document_number_id    UUID REFERENCES document_number(id),
    formatted_number      TEXT,
    order_invoice_id      UUID REFERENCES order_invoice(id),
    order_id              UUID REFERENCES "order"(id),
    tranche_id            UUID REFERENCES delivery_tranche(id),
    dispatch_id           UUID REFERENCES dispatch(id),
    bill_to_party_id      UUID REFERENCES party(id),
    locale                TEXT NOT NULL,
    jurisdiction          CHAR(2) NOT NULL,
    template_id           UUID NOT NULL REFERENCES document_template(id),
    template_version      INTEGER NOT NULL,
    currency              CHAR(3),
    total_amount          NUMERIC(18,4),
    render_model          JSONB NOT NULL,                  -- the frozen typed inputs the PDF rendered from
    corrects_document_id  UUID REFERENCES document(id),
    status                TEXT NOT NULL,                   -- draft/rendering/finalised/void
    storage_uri           TEXT,
    content_sha256        TEXT,                            -- tamper-evidence / WORM proof + determinism
    issued_at             TIMESTAMPTZ,
    accounting_period_key TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX document_order_idx     ON document (order_id);
CREATE INDEX document_invoice_idx   ON document (order_invoice_id);
CREATE INDEX document_payer_idx     ON document (bill_to_party_id, document_type, issued_at DESC);
CREATE INDEX document_number_fk_idx ON document (formatted_number);

-- field_layer_map: money/totals → commercial; render_model PII handled at projection. (doc 17 §9)
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('document','total_amount','commercial'),
    ('document','currency','commercial')
ON CONFLICT (object_type, field) DO NOTHING;

-- view/create grants (doc 17 §9). packing_list = volume only; fiscal docs = commercial.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'document', 'view', NULL, '{volume,commercial,pii}', '{}', 'all' FROM role WHERE name IN ('finance','tax_specialist','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'document', 'create', NULL, '{volume,commercial,pii}', '{volume,commercial,pii}', 'all' FROM role WHERE name='finance';

-- Year-1 seed: a UK invoice numbering series + a GB/en invoice template (doc 02 §A "year-1 = UK only").
-- The seed entity is created here only if no entity exists yet (local/dev); prod entities come from org setup.
INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'invoice', 'GB', 'HV-UK-INV', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('invoice', 'GB', 'en',
        'INVOICE {{formatted_number}}\n{{supplier_name}} (VAT {{supplier_vat}})\nBill to: {{payer_name}}\n{{#lines}}{{description}} x{{qty}} @ {{unit_price}} = {{line_total}}\n{{/lines}}\nTotal ex VAT: {{subtotal}}\nVAT: {{vat}}\nTotal: {{total}}',
        '{"vat_note":"VAT invoice"}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;
