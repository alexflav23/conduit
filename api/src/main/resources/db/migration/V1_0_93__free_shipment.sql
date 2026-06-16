-- Free-shipment classification (the COGS-without-revenue population, tracked distinctly). A free shipment moves
-- costed inventory with no sale; WHY matters for the P&L and for forecasting: a warranty/RMA replacement is a
-- product-quality liability accrued off sales; a sample is marketing spend with a measurable conversion rate.
-- The category is DERIVED from transparent, source-backed signals (recipient is an existing paying customer →
-- replacement; never paid → prospect sample; converted later → sample_converted; internal/marketplace by name) —
-- inferred, not invented, and human-overridable. A projection: rebuilt idempotently from recognition + order + party.
CREATE TABLE free_shipment (
    dispatch_id   UUID PRIMARY KEY,
    order_id      UUID NOT NULL,
    party_id      UUID,
    party_name    TEXT,
    entity_id     UUID,
    category      TEXT NOT NULL,          -- warranty_or_rma_replacement | sample_converted | sample_prospect | internal_demo | marketplace_return
    basis         TEXT NOT NULL,          -- the rule that fired (audit of the inference)
    cogs          NUMERIC(18,4) NOT NULL DEFAULT 0,
    currency      TEXT,
    occurred_at   TIMESTAMPTZ,            -- recognition (economic) date — drives the trend buckets
    override_by   UUID,                   -- a human reclassification wins over the derived category
    override_at   TIMESTAMPTZ,
    classified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX free_shipment_category_idx ON free_shipment (category, occurred_at);

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'free_shipment', 'view', NULL, '{commercial,profitability}', '{}', 'all' FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'free_shipment', 'edit', NULL, '{commercial,profitability}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
