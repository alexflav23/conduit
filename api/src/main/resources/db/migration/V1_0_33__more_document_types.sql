-- M13-Docs.7 — the remaining legal document types (doc 17 §5). All are rendered projections of typed truth via
-- the same engine: gapless numbering, immutable, WORM. proforma = a pre-payment invoice (no AR, before dispatch);
-- packing_list = a volume-only shipment doc (no money — packed contents + serials). Year-1 UK series are seeded
-- best-effort for an existing entity; tests create their own per-entity series.

INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'proforma', 'GB', 'HV-UK-PF', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;
INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'packing_list', 'GB', 'HV-UK-PL', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('proforma', 'GB', 'en',
        'PROFORMA INVOICE\n{{supplier_name}}\nBill to: {{payer_name}}\nTotal: {{total}}',
        '{"note":"Proforma — not a VAT invoice"}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('packing_list', 'GB', 'en',
        'PACKING LIST\n{{supplier_name}}\nShip to: {{payer_name}}',
        '{}'::jsonb,
        '{supplier_name,payer_name}')
ON CONFLICT DO NOTHING;

-- packing_list is volume-only (doc 17 §9): it lists contents + serials, never money. View grant to fulfilment +
-- the finance/auditor roles already get 'document' view (V1_0_27). Nothing money-classified is added here.
