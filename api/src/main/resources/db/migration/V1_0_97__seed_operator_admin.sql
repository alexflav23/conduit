-- Seed the operator's Workspace identity as an admin so a clean boot lands them with access.
--
-- A real Google/Keycloak sign-in resolves the principal by verified e-mail (AuthService → loadPrincipalByEmail),
-- which AUTO-PROVISIONS an unknown e-mail as an app_user with NO role assignments — authenticated but
-- deny-by-default (doc 05). Correct for a stranger; wrong for the operator running the ignition, who otherwise
-- signs in to a desk that is honestly blank everywhere. The demo dev-token principals (V1_0_89) don't help a
-- real OIDC login. Seed the operator the same way the runtime auto-provision does (keycloak_id 'google:<email>',
-- e-mail the unique key) and grant 'admin' (broadest breadth, all scopes), so a fresh down -v reconverges to a
-- desk the operator can actually see. Idempotent: ON CONFLICT (email) matches the runtime upsert.
INSERT INTO app_user (keycloak_id, name, email) VALUES
  ('google:flavian@hypervolt.co.uk', 'Flavian Alexandru', 'flavian@hypervolt.co.uk')
ON CONFLICT (email) DO NOTHING;

-- "Full admin" for the operator means SEE EVERYTHING. The doc-05 preset roles are deliberately segmented (no
-- single role carries every object × every data layer — admin is systems/RBAC, finance owns forecast/P&L,
-- treasury owns FX, etc.). A principal's grants are the UNION of its role_assignments and the projection takes
-- the most-permissive layer set across them, so the faithful superuser is the operator holding every role —
-- without mutating the presets (the SoD close/lock demo still runs cleanly via the demo-token personas).
INSERT INTO role_assignment (user_id, role_id)
SELECT u.id, r.id FROM app_user u CROSS JOIN role r WHERE u.email = 'flavian@hypervolt.co.uk'
  AND NOT EXISTS (
    SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id
  );
