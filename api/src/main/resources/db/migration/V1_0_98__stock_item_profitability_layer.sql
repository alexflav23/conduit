-- The inventory read surface (InventoryRoutes) exposes landed cost per lot/serial. Cost rides the profitability
-- layer (FieldLayerMap), but the seeded stock_item view permission only granted {commercial}, so the lot ledger
-- rendered without any cost for everyone. Grant profitability on stock_item view to the roles that legitimately
-- see cost (finance, procurement, admin, ceo); commercial-only roles (sales, fulfilment) keep units without cost,
-- exactly as the data-layer model intends (doc 05).
UPDATE permission p
SET viewable_layers = array_append(p.viewable_layers, 'profitability')
FROM role r
WHERE p.role_id = r.id
  AND p.object_type = 'stock_item'
  AND p.action = 'view'
  AND r.name IN ('finance', 'procurement', 'admin', 'ceo')
  AND NOT ('profitability' = ANY(p.viewable_layers));
