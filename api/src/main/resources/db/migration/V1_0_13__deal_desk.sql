-- Deal Desk: ADLP exception governance (doc 04 §ADLP, doc 02 §E). The exception carries the agent's
-- narrative + volume expectation; approval is CEO-only, timed, volume-contingent and customer-specific.

ALTER TABLE adlp_exception ADD COLUMN party_id            UUID;
ALTER TABLE adlp_exception ADD COLUMN notes               TEXT;
ALTER TABLE adlp_exception ADD COLUMN doc_refs            JSONB;
ALTER TABLE adlp_exception ADD COLUMN margin_assessment   JSONB;
ALTER TABLE adlp_exception ADD COLUMN list_price          NUMERIC(18,4);
ALTER TABLE adlp_exception ADD COLUMN max_discount_pct    NUMERIC(5,2);
ALTER TABLE adlp_exception ADD COLUMN approved_valid_from TIMESTAMPTZ;   -- timed approval window
ALTER TABLE adlp_exception ADD COLUMN approved_valid_to   TIMESTAMPTZ;
ALTER TABLE adlp_exception ADD COLUMN approved_volume_min INTEGER;        -- volume contingency

CREATE TABLE rebate (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id         UUID,
    agent_id         UUID,
    budget_ref       TEXT,
    type             TEXT,
    basis            TEXT,
    amount           NUMERIC(18,4),
    currency         CHAR(3),
    status           TEXT NOT NULL DEFAULT 'draft',
    performance_link TEXT,
    hubspot_ref      TEXT,
    approved_by      UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deal Desk role: assembles exceptions (edit) but CANNOT approve. Approval stays CEO-only (seeded in V1_0_4).
INSERT INTO role (name, description, is_preset) VALUES ('deal_desk', 'Deal Desk (assemble ADLP exceptions; cannot approve)', true);
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT id, 'adlp_exception', 'edit', '{volume,commercial,profitability}', '{volume,commercial,profitability}', 'all' FROM role WHERE name='deal_desk';
INSERT INTO permission (role_id, object_type, action, viewable_layers, data_breadth)
SELECT id, 'adlp_exception', 'view', '{volume,commercial,profitability}', 'all' FROM role WHERE name='deal_desk';
INSERT INTO permission (role_id, object_type, action, viewable_layers, data_breadth)
SELECT id, 'price_rule', 'view', '{volume,commercial}', 'all' FROM role WHERE name='deal_desk';

-- Agents propose narratives on their own orders' exceptions (edit, own), but never approve.
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT id, 'adlp_exception', 'edit', '{volume,commercial}', '{volume,commercial}', 'own' FROM role WHERE name='retail_sales_agent';
INSERT INTO permission (role_id, object_type, action, viewable_layers, data_breadth)
SELECT id, 'adlp_exception', 'view', '{volume,commercial}', 'own' FROM role WHERE name='retail_sales_agent';

-- CEO can also view the exception (already approves it).
INSERT INTO permission (role_id, object_type, action, viewable_layers, data_breadth)
SELECT id, 'adlp_exception', 'view', '{volume,commercial,profitability,commission,inter_entity,treasury}', 'all' FROM role WHERE name='ceo';
