-- M13-Tax.3 — access for the tax subsystem (doc 16 §9). tax_specialist proposes regimes/rates/routing and manages
-- registrations/nexus + runs quotes; CFO (ceo) approves rate/routing changes (maker-checker); finance/auditor view.
-- Layers: amounts + breakdown are commercial, quantities volume, VAT/registration numbers pii — never profitability.

-- tax_specialist: view + create across the config + quote surface (incl. PII so they see VAT/registration numbers).
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, t.obj, t.act, 'tax_config', '{volume,commercial,pii}', '{volume,commercial,pii}', 'all'
FROM role r
CROSS JOIN (VALUES
    ('tax_regime','view'), ('tax_regime','create'),
    ('tax_rate','view'), ('tax_rate','create'),
    ('tax_routing','view'), ('tax_routing','create'),
    ('tax_registration','view'), ('tax_registration','create'),
    ('nexus_profile','view'), ('nexus_profile','create'),
    ('tax_quote','view'), ('tax_quote','create')
) AS t(obj, act)
WHERE r.name = 'tax_specialist';

-- CFO (ceo): approve the governed config changes (proposer ≠ approver).
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, t.obj, 'approve', 'tax_config', '{volume,commercial,pii}', '{volume,commercial,pii}', 'all'
FROM role r
CROSS JOIN (VALUES ('tax_regime'), ('tax_rate'), ('tax_routing')) AS t(obj)
WHERE r.name = 'ceo';

-- finance: view config + quotes + nexus (amounts, not PII).
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, t.obj, 'view', 'tax_config', '{volume,commercial}', '{}', 'all'
FROM role r
CROSS JOIN (VALUES ('tax_regime'), ('tax_rate'), ('tax_routing'), ('tax_registration'), ('nexus_profile'), ('tax_quote')) AS t(obj)
WHERE r.name = 'finance';

-- auditor: read-only on the audit-relevant tax objects.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, t.obj, 'view', 'tax_config', '{volume,commercial}', '{}', 'all'
FROM role r
CROSS JOIN (VALUES ('tax_regime'), ('tax_rate'), ('tax_quote')) AS t(obj)
WHERE r.name = 'auditor';

-- field_layer_map (mirrors FieldLayerMap.seed — the Scala map is the projection's source of truth, doc 05 §3).
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('tax_quote','total_tax','commercial'),
    ('tax_quote','buyer_tax_id','pii'),
    ('tax_quote_line','taxable_amount','commercial'),
    ('tax_quote_line','line_tax_total','commercial'),
    ('tax_quote_line','effective_rate_pct','commercial'),
    ('tax_quote_line','components','commercial'),
    ('tax_quote_line','qty','volume'),
    ('tax_regime','rate_percent','commercial'),
    ('tax_rate','rate_pct','commercial'),
    ('tax_registration','number','pii'),
    ('nexus_profile','sales_to_date','commercial'),
    ('nexus_profile','txn_count_to_date','volume')
ON CONFLICT (object_type, field) DO NOTHING;
