-- M-Assurance B (doc 29 / doc 30 L9): the authz matrix (AuthzMatrixSuite) found four preset-role grants
-- that could ACT on an object without being able to VIEW it — the checker-cant-see-what-they-approve class
-- the V1_0_61 fix first exposed. Each is closed here by adding the missing view, matching the acting grant's
-- layers and breadth, so "no role acts on what it cannot see" holds across the whole seed.

-- (1) the exact V1_0_61 class: the CFO/CEO approves the transfer-price policy but could not view it.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'transfer_price_policy', 'view', NULL, '{inter_entity}'::text[], '{}'::text[], 'all'
FROM role r WHERE r.name = 'ceo'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = 'transfer_price_policy' AND p.action = 'view');

-- (2) the CEO edits price_rule (V1_0_4) with no matching view — grant view at the same layers it edits.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'price_rule', 'view', NULL, '{volume,commercial,profitability,inter_entity}'::text[], '{}'::text[], 'all'
FROM role r WHERE r.name = 'ceo'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = 'price_rule' AND p.action = 'view');

-- (3) admin creates/edits roles (V1_0_4) with no view:role — the permission builder should see what it manages.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'role', 'view', NULL, '{}'::text[], '{}'::text[], 'all'
FROM role r WHERE r.name = 'admin'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = 'role' AND p.action = 'view');

-- (4) the retail agent captures own forecasts (V1_0_15 create:forecast) but had no view:forecast — grant it
-- own-scope at the same volume layer, so the agent can read back what it submitted.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'forecast', 'view', NULL, '{volume}'::text[], '{}'::text[], 'own'
FROM role r WHERE r.name = 'retail_sales_agent'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = 'forecast' AND p.action = 'view');
