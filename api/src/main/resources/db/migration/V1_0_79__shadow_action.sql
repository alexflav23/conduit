-- M-Ingest slice (spec doc 33 §5): the audit of every outbound side-effect SUPPRESSED while in shadow mode.
-- In a parallel dual-run Conduit computes + posts to its own ledger but must not act on the outside world
-- (no Xero push, no HubSpot write-back, no customer invoice/email, no Stripe charge). Each suppressed effect
-- writes one row here instead — so the things we *would* have done are themselves reviewable (and become the
-- go-live checklist of outbound effects to un-mute at cutover). Append-only.
CREATE TABLE shadow_action (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action      TEXT NOT NULL,                 -- e.g. xero.invoice.create | xero.invoice.void | hubspot.upsert
    ref         TEXT NOT NULL,                 -- the business key (invoice_no, party id, …)
    detail      JSONB NOT NULL DEFAULT '{}',   -- the payload that would have been sent
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shadow_action_action_idx ON shadow_action (action, created_at);

INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'shadow_action', 'view', '{volume}'::text[], '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'shadow_action' AND p.action = 'view');
