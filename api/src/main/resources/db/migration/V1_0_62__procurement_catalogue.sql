-- M-Procurement (spec doc 28): the principal/LRD structure. The procurement entity (the principal) SETS an
-- explicit per-market price catalogue for internal sales to operating entities; every customer dispatch under
-- a procurement parent books matched journals (flash title at dispatch) tracing origin batches -> the IC pair
-- -> operating-entity COGS at TRANSFER price. The whole structure rides the inter_entity data layer.

-- §2.1 the central price catalogue: a governed PRICE LIST (a number the principal sets), not a formula.
-- Append-only versions; maker <> checker, both principal-side; effective-dated.
CREATE TABLE transfer_price_list (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    procurement_entity_id  UUID NOT NULL REFERENCES entity(id),
    market_id              UUID NOT NULL,                          -- the destination market (per-market pricing)
    currency               CHAR(3) NOT NULL REFERENCES currency(code),
    status                 TEXT NOT NULL DEFAULT 'draft',          -- draft / active / superseded
    effective_from         TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to           TIMESTAMPTZ,
    version                INTEGER NOT NULL DEFAULT 1,
    proposed_by            UUID REFERENCES app_user(id),           -- maker
    approved_by            UUID REFERENCES app_user(id),           -- checker (maker <> checker)
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tpl_resolve_idx ON transfer_price_list (procurement_entity_id, market_id, status, effective_from DESC);

CREATE TABLE transfer_price_list_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    price_list_id      UUID NOT NULL REFERENCES transfer_price_list(id) ON DELETE CASCADE,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    unit_price         NUMERIC(18,4) NOT NULL CHECK (unit_price > 0),
    UNIQUE (price_list_id, product_variant_id)
);

-- §2.2 the match record: one row per recognized dispatch under a procurement parent — the full chain from
-- the customer dispatch through the IC uplift pair to the origin batches (the physical PO/CM genealogy).
-- UNIQUE(dispatch_id) + deterministic journal legs make redelivery a no-op. Append-only.
CREATE TABLE ic_match (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_id            UUID NOT NULL UNIQUE REFERENCES dispatch(id),
    order_id               UUID NOT NULL REFERENCES "order"(id),
    operating_entity_id    UUID NOT NULL REFERENCES entity(id),
    procurement_entity_id  UUID NOT NULL REFERENCES entity(id),
    price_list_id          UUID REFERENCES transfer_price_list(id), -- the catalogue version that priced it
    currency               CHAR(3) NOT NULL,
    landed_total           NUMERIC(18,4) NOT NULL,                 -- principal's cost basis (specific batches)
    transfer_total         NUMERIC(18,4) NOT NULL,                 -- the catalogue/policy price applied
    uplift_total           NUMERIC(18,4) NOT NULL,                 -- transfer - landed = the principal's margin
    origin_batch_ids       UUID[] NOT NULL DEFAULT '{}',           -- lot_batch genealogy (-> physical PO/GRN/CM)
    op_leg_tb_transfer_id  NUMERIC(39,0),                          -- operating COGS true-up leg
    pr_leg_tb_transfer_id  NUMERIC(39,0),                          -- principal margin leg
    elimination_group_id   UUID NOT NULL DEFAULT gen_random_uuid(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ic_match_order_idx ON ic_match (order_id);
CREATE INDEX ic_match_pr_idx    ON ic_match (procurement_entity_id, created_at DESC);

-- §2.3 the wall: the `procurement` preset role — view/manage the catalogue and matches, carrying the
-- inter_entity layer. Only admin + procurement (+ the existing CEO inter_entity holders) ever see this.
INSERT INTO role (name, description, is_preset)
SELECT 'procurement', 'Procurement entity (principal) staff: central price catalogue + IC matches', true
WHERE NOT EXISTS (SELECT 1 FROM role WHERE name = 'procurement');

INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, v.obj, v.act, '{volume,commercial,inter_entity}'::text[], CASE WHEN v.act = 'view' THEN '{}'::text[] ELSE '{inter_entity}'::text[] END, 'all'
FROM role r
CROSS JOIN (VALUES
  ('transfer_price_list', 'view'), ('transfer_price_list', 'create'), ('transfer_price_list', 'approve'),
  ('ic_match', 'view')) AS v(obj, act)
WHERE r.name = 'procurement'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = v.obj AND p.action = v.act);

INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, v.obj, v.act, '{volume,commercial,profitability,commission,inter_entity,treasury,pii}'::text[], '{inter_entity}'::text[], 'all'
FROM role r
CROSS JOIN (VALUES
  ('transfer_price_list', 'view'), ('transfer_price_list', 'create'), ('transfer_price_list', 'approve'),
  ('ic_match', 'view')) AS v(obj, act)
WHERE r.name = 'admin'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type = v.obj AND p.action = v.act);
