-- RBAC for the operational surfaces being wired in (M6 fulfilment, M9 purchasing, M5 commission read). The domain
-- services are proven; these grants let the matching roles actually drive them through the new REST routes. Layers
-- follow doc 05 (commercial for operational data; profitability stays walled). Breadth: agents scoped to their
-- entity/market, admin all.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'create', NULL, '{commercial}', '{commercial}', 'scoped' FROM role WHERE name IN ('fulfilment_agent');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'create', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'edit', NULL, '{commercial}', '{commercial}', 'scoped' FROM role WHERE name IN ('fulfilment_agent');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'edit', NULL, '{commercial}', '{commercial}', 'all' FROM role WHERE name IN ('admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'view', NULL, '{commercial}', '{}', 'scoped' FROM role WHERE name IN ('fulfilment_agent');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dispatch', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('admin','ceo');

-- Stock availability / ATP (read).
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'stock_item', 'view', NULL, '{commercial}', '{}', 'scoped' FROM role WHERE name IN ('fulfilment_agent','procurement','retail_sales_agent');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'stock_item', 'view', NULL, '{commercial}', '{}', 'all' FROM role WHERE name IN ('admin','ceo');

-- Purchasing / receiving (M9). Receiving lands profitability data (landed cost), so procurement gets that layer.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'create', NULL, '{commercial,profitability}', '{commercial,profitability}', 'scoped' FROM role WHERE name IN ('procurement');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'edit', NULL, '{commercial,profitability}', '{commercial,profitability}', 'scoped' FROM role WHERE name IN ('procurement');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'view', NULL, '{commercial,profitability}', '{}', 'scoped' FROM role WHERE name IN ('procurement');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'create', NULL, '{commercial,profitability}', '{commercial,profitability}', 'all' FROM role WHERE name IN ('admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'edit', NULL, '{commercial,profitability}', '{commercial,profitability}', 'all' FROM role WHERE name IN ('admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'purchase_order', 'view', NULL, '{commercial,profitability}', '{}', 'all' FROM role WHERE name IN ('admin','ceo');

-- Commission statements (read). retail_sales_agent already sees its OWN; finance/admin see all for reconciliation.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'commission_entry', 'view', NULL, '{commercial,profitability}', '{}', 'all' FROM role WHERE name IN ('finance','admin');
