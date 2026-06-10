-- M-Forecast: the materialized policy selection — what the tournament chose per account per origin, with its
-- blended forecast and the scored actual. Written by the backtest loop at scoring time so comparables are a
-- millisecond query (the read-time selection sweep took 21 minutes over the population), and the H6Q board
-- serves them live. Latest selection wins (evidence and code evolve); selected_at tracks recency.
CREATE TABLE IF NOT EXISTS policy_selection (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_month  DATE NOT NULL,
    company_id    UUID NOT NULL REFERENCES party (id),
    policy_key    TEXT NOT NULL,
    weights       JSONB NOT NULL,
    forecast_qty  NUMERIC(14, 4) NOT NULL,
    actual_qty    NUMERIC(14, 4) NOT NULL,
    selected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (origin_month, company_id)
);
CREATE INDEX IF NOT EXISTS policy_selection_origin_idx ON policy_selection (origin_month);

-- The live comparables view (channel × origin): what the desk H6Q board reads. Legacy HubSpot-era series
-- (pre-MRPeasy, £-denominated, dead post-Oct'25) are excluded — they carry no segment.
CREATE OR REPLACE VIEW channel_comparables AS
SELECT p.segment,
       ps.origin_month,
       count(*)                                   AS accounts,
       sum(ps.forecast_qty)                       AS forecast_units,
       sum(ps.actual_qty)                         AS actual_units,
       abs(sum(ps.forecast_qty) - sum(ps.actual_qty))
         / GREATEST(sum(ps.actual_qty), 1)        AS total_level_error
FROM policy_selection ps
JOIN party p ON p.id = ps.company_id
WHERE p.segment IS NOT NULL
GROUP BY p.segment, ps.origin_month;
