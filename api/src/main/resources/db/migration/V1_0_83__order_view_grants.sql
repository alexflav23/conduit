-- The base access seed (V1_0_4) granted view:order only to retail_sales_agent (breadth 'own'), so leadership and
-- finance roles could not see the order register at all — the order worklist 403'd for the CEO. Grant view:order
-- to the roles that legitimately oversee the whole book, at full breadth, with the layers each role already holds
-- elsewhere. Idempotent: skip if the (role, object, action) grant already exists.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'order', 'view', NULL, layers.viewable, '{}', 'all'
FROM role r
JOIN (VALUES
  ('ceo',     '{volume,commercial,profitability,commission,inter_entity,treasury}'::text[]),
  ('admin',   '{volume,commercial,profitability,commission,inter_entity,treasury}'::text[]),
  ('finance', '{volume,commercial,profitability,commission,inter_entity,treasury}'::text[]),
  ('auditor', '{volume,commercial,profitability,commission,inter_entity,treasury}'::text[]),
  ('deal_desk','{volume,commercial,profitability}'::text[])
) AS layers(role_name, viewable) ON layers.role_name = r.name
WHERE NOT EXISTS (
  SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = 'order' AND p.action = 'view'
);
