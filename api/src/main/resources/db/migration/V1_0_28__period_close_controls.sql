-- M13b — period close + reconciliation + Auditability Center (doc 14 §5–6). The tables (accounting_period,
-- reconciliation, control/control_run, period_close_task) exist from M1; this seeds the automated controls and
-- the finance/auditor grants for the close + audit surfaces.

-- Automated controls with re-performable evidence_query. Convention: the query returns a single integer = the
-- VIOLATION COUNT; 0 = pass, >0 = fail. (The ControlRunner records control_run from this.)
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-DOC-GAPLESS', 'Gapless document numbering',
   'Statutory document numbers are sequential with no holes; voids keep their seq (never reused).',
   '{completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (SELECT series_id FROM document_number GROUP BY series_id HAVING max(seq) <> count(*)) holes'),
  ('CTRL-RECON-EXCEPTIONS', 'No open reconciliation exceptions',
   'Every reconciliation for a period is matched (or signed off) before the period locks.',
   '{completeness,accuracy}', 'detective', 'at_close', true,
   'SELECT count(*) FROM reconciliation WHERE status = ''exception'' AND signed_off_by IS NULL'),
  ('CTRL-INV-CONSERVATION', 'Invoice ties to its order',
   'Every invoice total equals its order total (Σ lines == invoice; document is a projection, not a recompute).',
   '{valuation,accuracy}', 'detective', 'continuous', true,
   'SELECT count(*) FROM order_invoice i JOIN "order" o ON o.id = i.order_id WHERE i.total_inc_vat <> o.total_inc_vat')
ON CONFLICT (code) DO NOTHING;

-- Close + audit grants (doc 14 §6): finance runs/close; auditor reads everything; the read-only auditor portal.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'accounting_period', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'accounting_period', 'edit', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'reconciliation', 'view', NULL, '{commercial,inter_entity,treasury}', '{}', 'all' FROM role WHERE name IN ('finance','admin','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'reconciliation', 'edit', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'control', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('finance','admin','auditor');
