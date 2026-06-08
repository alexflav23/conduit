-- M13-Docs.7 — customer account statement (doc 17). A point-in-time projection of a party's OPEN invoices for an
-- entity (what they owe, by due date). Gapless-numbered + WORM like every document; idempotent per (party, period).
INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'statement', 'GB', 'HV-UK-ST', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('statement', 'GB', 'en',
        'STATEMENT OF ACCOUNT\n{{supplier_name}}\nAccount: {{payer_name}}\nTotal outstanding: {{total}}',
        '{}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;
