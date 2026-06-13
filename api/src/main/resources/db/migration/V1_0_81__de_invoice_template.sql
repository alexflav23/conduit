-- P2.3 (spec doc 34): the per-jurisdiction document-template matrix extends beyond GB. A German invoice template
-- (de/DE) — resolved ahead of the global fallback by DocumentService's exact-(jurisdiction,locale) → jurisdiction
-- → locale → global precedence, with the required_fields finalise gate already enforcing the mandated render-model
-- fields. The reverse-charge clause (Steuerschuldnerschaft des Leistungsempfängers) is the EU B2B legal notice.
-- Content here is a first draft for the matrix mechanism; per-market legal review is the content remainder.
INSERT INTO document_template (document_type, jurisdiction, locale, body, legal_clauses, required_fields)
VALUES ('invoice', 'DE', 'de',
        'RECHNUNG {{formatted_number}}\n{{supplier_name}}\nRechnung an: {{payer_name}}\n{{#lines}}{{description}} x{{qty}} @ {{unit_price}} = {{line_total}}\n{{/lines}}\nNetto: {{subtotal}}\nUSt: {{vat}}\nGesamt: {{total}}',
        '{"vat_note":"Umsatzsteuer-Rechnung","reverse_charge":"Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge)"}'::jsonb,
        '{supplier_name,payer_name,total}')
ON CONFLICT DO NOTHING;
