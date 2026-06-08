-- M13-Docs.7 — commercial invoice (doc 17): the customs document for a cross-border shipment. Needs the customs
-- facts the other docs don't carry: HS tariff code + country of origin per product, and the order's incoterms.
-- hs_code already exists on product_variant (V1_0_5). Add the rest idempotently.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS country_of_origin CHAR(2);  -- ISO country of manufacture
ALTER TABLE "order"         ADD COLUMN IF NOT EXISTS incoterms         TEXT;     -- DAP / DDP / EXW … (who bears duty/carriage)

INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
SELECT id, 'commercial_invoice', 'GB', 'HV-UK-CI', '{series}-{yyyy}-{seq:06d}' FROM entity LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('commercial_invoice', 'GB', 'en',
        'COMMERCIAL INVOICE\n{{supplier_name}}\nShip to: {{payer_name}}\nTotal customs value: {{total}}',
        '{"note":"For customs purposes only"}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;
