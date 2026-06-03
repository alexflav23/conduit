-- RBAC + scope + data-layer projection (doc 02 §B, doc 05). Enforced server-side by the policy layer.

CREATE TABLE team (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    member_user_ids UUID[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id TEXT UNIQUE NOT NULL,
    name        TEXT,
    email       CITEXT UNIQUE,
    status      TEXT NOT NULL DEFAULT 'active',
    team_id     UUID REFERENCES team(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT UNIQUE NOT NULL,
    description TEXT,
    is_preset   BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permission (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id         UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    object_type     TEXT NOT NULL,
    action          TEXT NOT NULL,
    section         TEXT,
    viewable_layers TEXT[] NOT NULL DEFAULT '{}',
    editable_layers TEXT[] NOT NULL DEFAULT '{}',
    data_breadth    TEXT NOT NULL DEFAULT 'scoped',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX permission_role_idx ON permission (role_id);

CREATE TABLE role_assignment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id          UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    scope_entities   UUID[] NOT NULL DEFAULT '{}',
    scope_markets    UUID[] NOT NULL DEFAULT '{}',
    scope_channels   UUID[] NOT NULL DEFAULT '{}',
    breadth_override TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX role_assignment_user_idx ON role_assignment (user_id);

CREATE TABLE data_layer (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL
);
INSERT INTO data_layer (code, name) VALUES
    ('volume','Units & coverage'), ('commercial','Price & revenue'), ('profitability','Cost & margin'),
    ('commission','Commission'), ('pii','Contact PII'), ('inter_entity','Inter-entity / transfer pricing'),
    ('treasury','Treasury / FX hedges');

-- Decision 14b: field -> data-layer map (mirrors access.FieldLayerMap.seed; editable at runtime).
CREATE TABLE field_layer_map (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type TEXT NOT NULL,
    field       TEXT NOT NULL,
    data_layer  TEXT NOT NULL REFERENCES data_layer(code),
    UNIQUE (object_type, field)
);
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('price_rule','authorised_price','commercial'), ('price_rule','max_discount_pct','commercial'),
    ('price_rule','tp_method','inter_entity'), ('price_rule','tp_markup_pct','inter_entity'),
    ('price_rule','from_entity_id','inter_entity'), ('price_rule','to_entity_id','inter_entity'),
    ('order','subtotal_ex_vat','commercial'), ('order','vat_total','commercial'),
    ('order','total_inc_vat','commercial'), ('order_line','unit_price_ex_vat','commercial'),
    ('commission_entry','amount','commission'), ('commission_entry','basis_amount','profitability'),
    ('lot_batch','landed_unit_cost','profitability'), ('lot_batch','unit_cost_usd','profitability'),
    ('contact','email','pii'), ('contact','phone','pii'),
    ('pipeline_coverage','forecast_qty','volume'), ('pipeline_coverage','shipped_qty','volume'),
    ('pipeline_coverage','revenue','commercial'), ('pipeline_coverage','margin','profitability'),
    ('fx_hedge','contracted_rate','treasury'), ('fx_hedge','notional','treasury');

-- Preset roles (doc 05 §4). Cloneable/editable via the permission builder.
INSERT INTO role (name, description, is_preset) VALUES
    ('retail_sales_agent','Retail sales agent', true),
    ('customer_service_agent','Customer service', true),
    ('fulfilment_agent','Fulfilment', true),
    ('tax_specialist','Tax specialist', true),
    ('finance','Finance', true),
    ('admin','Administrator (no ADLP approval, no audit edit)', true),
    ('ceo','CEO/CFO approver', true),
    ('treasury','Treasury (FX hedges)', true),
    ('auditor','Read-only auditor', true);

-- Representative permission seeds for the roles the spine tests/flows depend on.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'order', 'view', NULL, '{volume,commercial}', '{}', 'own' FROM role WHERE name='retail_sales_agent';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'order', 'create', NULL, '{volume,commercial}', '{volume,commercial}', 'own' FROM role WHERE name='retail_sales_agent';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'price_rule', 'view', NULL, '{volume,commercial}', '{}', 'scoped' FROM role WHERE name='retail_sales_agent';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'commission_entry', 'view', NULL, '{commission}', '{}', 'own' FROM role WHERE name='retail_sales_agent';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'adlp_exception', 'approve', NULL, '{volume,commercial,profitability,commission,inter_entity,treasury}', '{}', 'all' FROM role WHERE name='ceo';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'price_rule', 'edit', NULL, '{volume,commercial,profitability,inter_entity}', '{volume,commercial,profitability,inter_entity}', 'all' FROM role WHERE name='ceo';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'price_rule', 'view', NULL, '{volume,commercial,profitability,inter_entity}', '{}', 'all' FROM role WHERE name='finance';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'role', 'edit', NULL, '{}', '{}', 'all' FROM role WHERE name='admin';
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'role', 'create', NULL, '{}', '{}', 'all' FROM role WHERE name='admin';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'fx_hedge', 'edit', NULL, '{treasury}', '{treasury}', 'all' FROM role WHERE name='treasury';
