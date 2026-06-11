-- The CFO/CEO approves tax governance (rates, regimes, routing) — maker-checker requires the checker to SEE
-- what they approve. Exposed by the sign-in session reset: approval previously rode the maker's client-side
-- table state; a fresh CFO session listing rates got 403 and an empty board. View accompanies approve.
INSERT INTO permission (role_id, object_type, action)
SELECT r.id, v.object_type, 'view'
FROM role r
CROSS JOIN (VALUES ('tax_rate'), ('tax_regime'), ('tax_routing'), ('tax_quote')) AS v(object_type)
WHERE r.name = 'ceo'
  AND NOT EXISTS (
    SELECT 1 FROM permission p
    WHERE p.role_id = r.id AND p.object_type = v.object_type AND p.action = 'view'
  );
