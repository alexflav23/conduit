-- M-Proof P2 (spec doc 31): the Proof Center surface. view:proof_center gates the formal-proof pages
-- (admin/ceo/finance/auditor); manage:proof_center gates the non-prod Tamper Sandbox (admin only — and the
-- route ALSO requires HYPERVOLT_ENV != prod, the same double gate as dev tokens).
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'proof_center', 'view', '{volume,commercial}'::text[], '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'proof_center' AND p.action = 'view');

INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'proof_center', 'edit', '{volume,commercial}'::text[], '{volume,commercial}'::text[], 'all'
FROM role r
WHERE r.name = 'admin'
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'proof_center' AND p.action = 'edit');

-- The tamper stash: every seeded corruption is recorded so restore() can undo it in reverse — a restart
-- cannot strand a corrupted demo book.
CREATE TABLE proof_tamper_stash (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind       TEXT NOT NULL,
    payload    JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
