-- M-IC-FX slice 4a (spec doc 28 §5.5): hedge performance + ASC 815-50 / Reg S-K Item 305 disclosure. A hedge
-- is now a first-class VALUED instrument: each period we record its fair value and gain/loss, so treasury can
-- see how every individual hedge actually performs, per market. This is the measurement/disclosure layer —
-- tracking only, no ledger posting (the gross-presentation correction that posts the offsetting MTM through
-- earnings and un-freezes the hedged balance is slice 4b). Designation classifies the hedge per ASC 815;
-- 'economic' is the default (the GAAP baseline — undesignated, MTM through earnings), and a designated
-- cash_flow / net_investment hedge requires contemporaneous documentation (doc_ref) — enforced fail-closed in
-- HedgeValuationService.designate, never backfilled (ASC 815-20-25 inception-documentation rule).
ALTER TABLE fx_hedge ADD COLUMN designation TEXT NOT NULL DEFAULT 'economic'; -- economic | cash_flow | net_investment
ALTER TABLE fx_hedge ADD COLUMN doc_ref     TEXT;                             -- inception documentation reference

-- Per-period fair-value snapshot of a hedge: the gain/loss vs the contracted rate at the period spot. The
-- fair value (cumulative_mtm) re-derives exactly from (contracted_rate − spot_rate) × notional_open, so the
-- performance number is provenanced, not asserted. period_mtm is the change since the prior snapshot.
CREATE TABLE hedge_valuation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fx_hedge_id     UUID NOT NULL REFERENCES fx_hedge(id),
    as_of           DATE NOT NULL,
    spot_rate       NUMERIC(18,8) NOT NULL,
    contracted_rate NUMERIC(18,8) NOT NULL,
    notional_open   NUMERIC(18,4) NOT NULL,   -- notional − notional_used at the snapshot
    period_mtm      NUMERIC(18,4) NOT NULL,    -- cumulative − prior cumulative
    cumulative_mtm  NUMERIC(18,4) NOT NULL,    -- (contracted − spot) × notional_open : the hedge's fair value
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hedge_valuation_idx ON hedge_valuation (fx_hedge_id, as_of DESC);

INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('hedge_valuation','spot_rate','treasury'),
    ('hedge_valuation','contracted_rate','treasury'),
    ('hedge_valuation','notional_open','treasury'),
    ('hedge_valuation','period_mtm','treasury'),
    ('hedge_valuation','cumulative_mtm','treasury')
ON CONFLICT (object_type, field) DO NOTHING;

-- The ASC 815-50 / Item 305 disclosure surface: per hedge per market (pair), the notional, the contracted
-- rate, the latest spot, the current fair value (gain/loss), and the designation — one row an auditor or the
-- market-risk table reads directly.
CREATE VIEW hedge_disclosure AS
  SELECT h.id, h.entity_id, h.pair_from, h.pair_to, h.designation, h.doc_ref,
         h.contracted_rate, h.notional, h.notional_used, (h.notional - h.notional_used) AS notional_open,
         h.valid_from, h.valid_to, h.status,
         v.as_of AS valued_as_of, v.spot_rate AS latest_spot, v.cumulative_mtm AS fair_value_gain_loss
  FROM fx_hedge h
  LEFT JOIN LATERAL (
    SELECT as_of, spot_rate, cumulative_mtm FROM hedge_valuation
    WHERE fx_hedge_id = h.id ORDER BY as_of DESC, created_at DESC LIMIT 1
  ) v ON true;

-- CTRL-HEDGE-PERF: every valuation re-derives exactly — cumulative_mtm == (contracted − spot) × notional_open
-- — and the period deltas chain to the cumulative (Σ period_mtm == latest cumulative per hedge). A breach is
-- a corrupted performance figure.
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-HEDGE-PERF', 'Hedge fair value re-derives and chains',
   'Each hedge valuation equals (contracted − spot) × open notional to the minor unit, and the period deltas sum to the latest cumulative — the per-hedge performance is provenanced, not asserted.',
   '{accuracy,valuation}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (
      SELECT 1 FROM hedge_valuation v
      WHERE round(v.cumulative_mtm, 4) <> round((v.contracted_rate - v.spot_rate) * v.notional_open, 4)
      UNION ALL
      SELECT 1 FROM (
        SELECT fx_hedge_id, SUM(period_mtm) AS sum_periods,
               (SELECT cumulative_mtm FROM hedge_valuation x
                WHERE x.fx_hedge_id = hv.fx_hedge_id ORDER BY as_of DESC, created_at DESC LIMIT 1) AS latest
        FROM hedge_valuation hv GROUP BY fx_hedge_id
      ) c WHERE round(c.sum_periods, 4) <> round(c.latest, 4)
    ) v')
ON CONFLICT (code) DO NOTHING;
