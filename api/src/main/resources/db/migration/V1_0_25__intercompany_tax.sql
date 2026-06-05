-- M12 — Intercompany & transfer pricing (spec doc 13). Creates the policy/agreement record, the paired-leg
-- movement link, the reproducible TP-documentation artefact, the derived topology projection, and the FX-hedge
-- treasury register. Builds on entity (doc 02 §A already carries entity_type/procurement_parent_id/functional_currency),
-- lot_batch.landed_unit_cost (specific-identification cost basis), exchange_rate, stock_transfer, purchase_order,
-- "order", price_rule(surface='inter_entity'). All money NUMERIC; no float.

-- §2.1 transfer_price_policy — the policy/agreement record (basis, OECD method, governance, TP-doc fields).
CREATE TABLE transfer_price_policy (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_entity_id       UUID NOT NULL REFERENCES entity(id),     -- the seller (procurement parent)
    to_entity_id         UUID NOT NULL REFERENCES entity(id),     -- the buyer
    method               TEXT NOT NULL,                           -- cost_plus / resale_minus / fixed
    basis                TEXT NOT NULL DEFAULT 'landed_cost',     -- computed off lot_batch.landed_unit_cost
    markup_pct           NUMERIC(7,4),                            -- cost_plus
    resale_margin_pct    NUMERIC(7,4),                            -- resale_minus
    fixed_price          NUMERIC(18,4),                           -- fixed
    fixed_currency       CHAR(3),
    tp_currency          CHAR(3),                                 -- struck-in currency (default seller functional)
    rounding_boundary    TEXT NOT NULL DEFAULT 'unit',            -- unit / line (RoundingPolicy, doc 14 §1.2)
    documentation_method TEXT,                                    -- OECD label: CUP/cost_plus/resale_price/TNMM
    arms_length_band     JSONB,                                   -- optional {min_pct,max_pct} governance band
    product_scope        JSONB NOT NULL DEFAULT '{}',             -- {} = all; {"family":[..]} / {"variant":[..]}
    effective_from       TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to         TIMESTAMPTZ,
    status               TEXT NOT NULL DEFAULT 'draft',           -- draft / active / superseded
    owner_user_id        UUID REFERENCES app_user(id),           -- maker
    approved_by          UUID REFERENCES app_user(id),            -- checker (CFO; maker <> checker)
    version              INTEGER NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tpp_resolve_idx ON transfer_price_policy (from_entity_id, to_entity_id, status, effective_from DESC);

-- §3.1 intercompany_link — one hop's paired legs (sell order + buy PO), the two linked TB transfers, the FX
-- bridge, elimination grouping and buy-side import-tax status. Append-mostly; status drives the state machine.
CREATE TABLE intercompany_link (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sell_order_id            UUID REFERENCES "order"(id),         -- seller IC order (type='intercompany')
    buy_po_id                UUID REFERENCES purchase_order(id),  -- buyer IC PO (type='intercompany')
    status                   TEXT NOT NULL DEFAULT 'draft',       -- draft/priced/ready/posted/completed/cancelled/reversed
    from_entity_id           UUID NOT NULL REFERENCES entity(id),
    to_entity_id             UUID NOT NULL REFERENCES entity(id),
    hop_seq                  INTEGER,
    stock_transfer_id        UUID REFERENCES stock_transfer(id),
    transfer_price_total     NUMERIC(18,4) NOT NULL DEFAULT 0,    -- Σ transfer_unit_price × qty
    tp_currency              CHAR(3),
    fx_rate                  NUMERIC(18,8),                       -- seller→buyer functional, if currencies differ
    fx_basis                 TEXT,                                -- spot / hedged (audit)
    sell_tb_transfer_id      NUMERIC(39,0),                       -- seller-ledger leg
    buy_tb_transfer_id       NUMERIC(39,0),                       -- buyer-ledger leg
    fx_bridge_tb_transfer_id NUMERIC(39,0),                       -- FX_CLEARING bridge leg (cross-currency only)
    elimination_group_id     UUID,                                -- groups paired legs for consolidation (§7)
    import_tax_status        TEXT,                                -- n/a / quoted / posted
    import_tax               JSONB,                               -- the recorded TaxQuote result (§6)
    accounting_period_key    TEXT,                                -- resolved at post (period-projection, doc 14 §2)
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ic_link_hop_idx     ON intercompany_link (from_entity_id, to_entity_id, status);
CREATE INDEX ic_link_elim_idx    ON intercompany_link (elimination_group_id);
CREATE INDEX ic_link_transfer_idx ON intercompany_link (stock_transfer_id);

-- §2.4 tp_document — immutable, reproducible TP-documentation: policy version + specific batch cost → TP.
CREATE TABLE tp_document (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intercompany_link_id  UUID NOT NULL REFERENCES intercompany_link(id),
    from_entity_id        UUID NOT NULL REFERENCES entity(id),
    to_entity_id          UUID NOT NULL REFERENCES entity(id),
    product_variant_id    UUID NOT NULL REFERENCES product_variant(id),
    lot_batch_id          UUID NOT NULL REFERENCES lot_batch(id),     -- the specific lot moved (cost basis)
    policy_id             UUID NOT NULL REFERENCES transfer_price_policy(id),
    policy_version        INTEGER NOT NULL,
    method                TEXT NOT NULL,
    documentation_method  TEXT,
    lot_landed_unit_cost  NUMERIC(18,4) NOT NULL,                     -- cost basis snapshot
    markup_or_margin_pct  NUMERIC(7,4),
    resale_anchor_price   NUMERIC(18,4),
    qty                   INTEGER NOT NULL,
    transfer_unit_price   NUMERIC(18,4) NOT NULL,                     -- recorded TP per unit
    tp_currency           CHAR(3) NOT NULL,
    fx_rate_applied       NUMERIC(18,8),
    fx_rate_source        TEXT,
    computed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    reproducible_inputs   JSONB NOT NULL                              -- full input snapshot to re-derive the TP
);
CREATE INDEX tp_doc_link_idx ON tp_document (intercompany_link_id);
CREATE INDEX tp_doc_hop_idx  ON tp_document (from_entity_id, to_entity_id, computed_at DESC);
CREATE INDEX tp_doc_lot_idx  ON tp_document (lot_batch_id);

-- §1.3 entity_topology_edge — derived projection of the procurement chain (fast queries + consolidation).
-- Rebuilt from entity + transfer_price_policy; not a source of truth.
CREATE TABLE entity_topology_edge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_entity_id  UUID NOT NULL REFERENCES entity(id),         -- seller (procurement parent)
    to_entity_id    UUID NOT NULL REFERENCES entity(id),         -- buyer
    hop_seq         INTEGER NOT NULL,                            -- 1 = nearest the external root
    policy_id       UUID REFERENCES transfer_price_policy(id),
    from_currency   CHAR(3) NOT NULL,
    to_currency     CHAR(3) NOT NULL,
    is_cross_border BOOLEAN NOT NULL,                            -- different jurisdictions → import VAT/duty leg
    is_intragroup   BOOLEAN NOT NULL DEFAULT true,               -- drives elimination tagging
    UNIQUE (from_entity_id, to_entity_id)
);
CREATE INDEX topo_edge_to_idx ON entity_topology_edge (to_entity_id, hop_seq);

-- §4 fx_hedge — treasury register: a designated hedge fixes the FX applied to a lot's cost / an IC hop and
-- drives consolidated reporting. Administered under the treasury permission; projects to the treasury layer.
-- (field_layer_map already maps fx_hedge.contracted_rate/notional → treasury in V1_0_4.)
CREATE TABLE fx_hedge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id       UUID NOT NULL REFERENCES entity(id),         -- the hedging operating market
    pair_from       CHAR(3) NOT NULL,                            -- exposure currency (seller functional)
    pair_to         CHAR(3) NOT NULL,                            -- functional currency hedged into (buyer)
    contracted_rate NUMERIC(18,8) NOT NULL,
    notional        NUMERIC(18,4) NOT NULL,                      -- in pair_from units
    notional_used   NUMERIC(18,4) NOT NULL DEFAULT 0,
    valid_from      DATE NOT NULL,
    valid_to        DATE NOT NULL,
    status          TEXT NOT NULL DEFAULT 'active',              -- active / closed
    created_by      UUID REFERENCES app_user(id),
    approved_by     UUID REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fx_hedge_resolve_idx ON fx_hedge (pair_from, pair_to, entity_id, status, valid_from DESC);

-- field_layer_map: transfer price / lot cost / policy → inter_entity; consolidation/CTA/translation → treasury;
-- the physical qty/move → volume (a fulfilment_agent sees the move, not the price). doc 13 §9.
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('transfer_price_policy','method','inter_entity'),
    ('transfer_price_policy','markup_pct','inter_entity'),
    ('transfer_price_policy','resale_margin_pct','inter_entity'),
    ('transfer_price_policy','fixed_price','inter_entity'),
    ('intercompany_link','transfer_price_total','inter_entity'),
    ('intercompany_link','fx_rate','inter_entity'),
    ('intercompany_link','fx_basis','inter_entity'),
    ('intercompany_link','qty','volume'),
    ('intercompany_link','stock_transfer_id','volume'),
    ('tp_document','transfer_unit_price','inter_entity'),
    ('tp_document','lot_landed_unit_cost','inter_entity'),
    ('tp_document','markup_or_margin_pct','inter_entity'),
    ('entity_topology_edge','is_intragroup','treasury')
ON CONFLICT (object_type, field) DO NOTHING;

-- Policy grants (doc 13 §9). finance: view inter_entity policies/movements/tp_document + create movements.
-- CFO (ceo role): approve transfer_price_policy. treasury: consolidation + hedge admin. tax_specialist/auditor:
-- view. fulfilment_agent: the physical move (volume) only — never the price.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'transfer_price_policy', 'view', NULL, '{inter_entity}', '{}', 'all' FROM role WHERE name IN ('finance','tax_specialist','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'transfer_price_policy', 'create', NULL, '{inter_entity}', '{inter_entity}', 'all' FROM role WHERE name='finance';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'transfer_price_policy', 'approve', NULL, '{inter_entity}', '{inter_entity}', 'all' FROM role WHERE name='ceo';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'intercompany_link', 'view', NULL, '{volume,inter_entity}', '{}', 'all' FROM role WHERE name IN ('finance','tax_specialist','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'intercompany_link', 'create', NULL, '{volume,inter_entity}', '{volume,inter_entity}', 'all' FROM role WHERE name='finance';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'intercompany_link', 'view', NULL, '{volume}', '{}', 'all' FROM role WHERE name='fulfilment_agent';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'tp_document', 'view', NULL, '{inter_entity}', '{}', 'all' FROM role WHERE name IN ('finance','tax_specialist','auditor');

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'consolidation', 'view', NULL, '{treasury}', '{}', 'all' FROM role WHERE name IN ('treasury','finance','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'fx_hedge', 'view', NULL, '{treasury}', '{}', 'all' FROM role WHERE name IN ('treasury','finance','auditor');
