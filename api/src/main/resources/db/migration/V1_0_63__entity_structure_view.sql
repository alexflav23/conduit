-- doc 28 §2.4 — the gated entity-structure view. view:entity_structure controls WHO can see the org chart
-- at all; the inter_entity layer controls WHICH org chart they see: without it, procurement entities and
-- procurement_parent edges are absent from the payload (the structure's existence is the secret, doc 28 §2.3).
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'entity_structure', 'view',
       CASE WHEN r.name IN ('admin', 'ceo', 'procurement')
            THEN '{volume,commercial,inter_entity}'::text[]
            ELSE '{volume,commercial}'::text[] END,
       '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'procurement', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'entity_structure' AND p.action = 'view');

-- doc 28 §2.5 — cancellations & alterations carry the genealogy too. The match row is append-only: a full
-- void stamps the reversal (id + legs); partial returns accumulate the unwound uplift. The corresponding
-- journals are posted by InvoiceReversalService (full, legs 4/5) and ReturnService (pro-rata, per restock).
ALTER TABLE ic_match ADD COLUMN reversed_at TIMESTAMPTZ;
ALTER TABLE ic_match ADD COLUMN reversal_id UUID;
ALTER TABLE ic_match ADD COLUMN rev_op_leg_tb_transfer_id NUMERIC(39,0);
ALTER TABLE ic_match ADD COLUMN rev_pr_leg_tb_transfer_id NUMERIC(39,0);
ALTER TABLE ic_match ADD COLUMN returned_uplift NUMERIC(18,4) NOT NULL DEFAULT 0;
