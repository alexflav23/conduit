-- M13 — invoice raised on DISPATCH (ASC 606: control/invoice at dispatch) with a contractual due date, plus the
-- cash-waterfall inputs. Per-invoice-contact terms already live on billing_profile.payment_terms_days /
-- credit_profile.terms_days (doc 02 §C); this adds the resolved due date + settlement state to each invoice.
ALTER TABLE order_invoice ADD COLUMN due_date DATE;
ALTER TABLE order_invoice ADD COLUMN paid_at   TIMESTAMPTZ;
ALTER TABLE order_invoice ADD COLUMN status    TEXT NOT NULL DEFAULT 'open';   -- open / paid / void

CREATE INDEX order_invoice_due_idx ON order_invoice (status, due_date);

-- Credit-terms admin is a finance/admin governed surface; the cash waterfall reads the same object.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'credit_profile', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'credit_profile', 'edit', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
