-- Two pieces that make a clean period close fully replayable from a fresh boot:
--
-- 1) Per-dispatch backfill invoices. Historical (ingested) dispatches have no order_invoice — the normal flow
--    raises one in DispatchService at dispatch time, but the trade history predates Conduit. Recognition now
--    issues one backfill invoice PER recognised historical dispatch (keyed by dispatch_id for idempotency), at the
--    exact figures it posts to AR, so the ar_vs_invoices reconciliation TIES instead of being signed off. The
--    dispatch_id column distinguishes a backfill invoice from a real (DispatchService) one — multiple NULLs are
--    allowed (real invoices), non-null ones are unique (one backfill per dispatch).
ALTER TABLE order_invoice ADD COLUMN dispatch_id UUID REFERENCES dispatch(id);
CREATE UNIQUE INDEX order_invoice_dispatch_uidx ON order_invoice (dispatch_id);

-- 2) Dev-door SoD principals. The roles (finance/admin/ceo/...) and their permissions are seeded by earlier
--    migrations, but app_user/role_assignment were created by hand — so a clean boot had no one who could run a
--    close or a lock. Seed the three dev-token identities (resolved by AuthService as dev:demo-<id> → keycloak_id)
--    with full-breadth roles, so segregation-of-duties (closer=finance ≠ locker=admin) works post-boot. These are
--    dev/local operating identities only; real access is Keycloak/Google-federated and RBAC-granted.
INSERT INTO app_user (keycloak_id, name, email) VALUES
  ('demo-finance', 'Demo Finance', 'finance@demo.hypervolt.local'),
  ('demo-admin',   'Demo Admin',   'admin@demo.hypervolt.local'),
  ('demo-ceo',     'Demo CEO',     'ceo@demo.hypervolt.local')
ON CONFLICT (keycloak_id) DO NOTHING;

INSERT INTO role_assignment (user_id, role_id)
SELECT u.id, r.id FROM app_user u JOIN role r ON r.name = 'finance' WHERE u.keycloak_id = 'demo-finance'
ON CONFLICT DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
SELECT u.id, r.id FROM app_user u JOIN role r ON r.name = 'admin' WHERE u.keycloak_id = 'demo-admin'
ON CONFLICT DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
SELECT u.id, r.id FROM app_user u JOIN role r ON r.name = 'ceo' WHERE u.keycloak_id = 'demo-ceo'
ON CONFLICT DO NOTHING;
