-- Hedge economic effectiveness — the "separate stream" (Ebury policy KPIs). Per month: the hedged (blended) GBP
-- cost of the CM USD payables vs the counterfactual ALL-SPOT GBP cost. saving_gbp = spot − hedged (negative = the
-- lock cost vs market that month; the protection also shows as near-zero effective-rate volatility vs spot). This
-- is a MEASUREMENT stream only — NEVER posted to the GAAP ledger (the books carry hedged COGS + the ASC-815 MTM).
-- Recomputed from the exposure forecast × the real FX register spot × the executed contracts.
CREATE TABLE hedge_effectiveness (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id      UUID NOT NULL REFERENCES entity(id),
    period_month   DATE NOT NULL,
    supplier       TEXT NOT NULL,
    exposure_usd   NUMERIC(18,2) NOT NULL,
    hedge_ratio    NUMERIC(6,4) NOT NULL,
    hedge_rate     NUMERIC(18,8) NOT NULL,
    spot_rate      NUMERIC(18,8) NOT NULL,
    effective_rate NUMERIC(18,8) NOT NULL,   -- the single blended GBP/USD: hedged_gbp = exposure_usd / effective_rate
    hedged_gbp     NUMERIC(18,2) NOT NULL,
    spot_gbp       NUMERIC(18,2) NOT NULL,
    saving_gbp     NUMERIC(18,2) NOT NULL,   -- spot_gbp − hedged_gbp (negative = hedge cost vs market)
    contract_no    TEXT,
    computed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, period_month, supplier)
);
CREATE INDEX hedge_effectiveness_idx ON hedge_effectiveness (entity_id, period_month);

INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('hedge_effectiveness','hedge_rate','treasury'),
    ('hedge_effectiveness','spot_rate','treasury'),
    ('hedge_effectiveness','effective_rate','treasury'),
    ('hedge_effectiveness','saving_gbp','treasury');
