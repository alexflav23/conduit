-- M-IC-FX slice 3 (spec doc 28 §5.4): settlement. A governed run (maker <> checker) settles the FULL open
-- set of an IC pair: cash legs in the transaction currency on both sides; a final remeasure-to-settlement
-- delta brings cumulative unrealized FX to exactly the realized total (settled − booked); a reclass leg then
-- clears the remeasurement adjunct into FX_SETTLED — unrealized becomes realized EXACTLY ONCE, never twice.
-- Hedge-booked exposure settles at the contracted rate (zero FX — the lock proven in cash) and releases its
-- live drawdown. Partial/explicit-selection settlement arrives with the desk UI; the full-set run is the
-- slice-3 contract.
CREATE TABLE ic_settlement (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    procurement_entity_id UUID NOT NULL REFERENCES entity(id),
    operating_entity_id   UUID NOT NULL REFERENCES entity(id),
    txn_currency          CHAR(3) NOT NULL,
    functional_currency   CHAR(3) NOT NULL,
    as_of                 DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'proposed',  -- proposed | settled
    net_txn               NUMERIC(18,4),                     -- Σ (uplift − returned) over the covered set
    hedged_txn            NUMERIC(18,4),                     -- the hedge-booked portion (settles at contracted)
    booked_functional     NUMERIC(18,4),
    settled_rate          NUMERIC(18,8),                     -- the unhedged portion's settlement-date spot
    rate_source           TEXT,
    settled_functional    NUMERIC(18,4),
    realized_fx           NUMERIC(18,4),                     -- settled − booked; reclassified, never double-counted
    prior_deltas_at_settle NUMERIC(18,4),                    -- the adjunct position consumed by this settlement:
                                                             -- post-settle adjunct = Σ rem.delta − Σ this (telescopes)
    proposed_by           UUID NOT NULL,
    approved_by           UUID,
    op_cash_tb_transfer_id NUMERIC(39,0),                    -- leg 0: DR IC_AP / CR BANK (operating side)
    pr_cash_tb_transfer_id NUMERIC(39,0),                    -- leg 1: DR BANK / CR IC_AR (principal side)
    fx_final_tb_transfer_id NUMERIC(39,0),                   -- leg 2: the final remeasure-to-settlement delta
    fx_reclass_tb_transfer_id NUMERIC(39,0),                 -- leg 3: adjunct -> FX_SETTLED (the reclass)
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at            TIMESTAMPTZ
);
CREATE INDEX ic_settlement_pair_idx
    ON ic_settlement (procurement_entity_id, operating_entity_id, created_at DESC);

-- Settled matches are stamped (append-only, NULL -> value once) — open-exposure definitions everywhere
-- (remeasurement, hedge live drawdown) exclude them, and lineage closure binds match <-> settlement.
ALTER TABLE ic_match ADD COLUMN settlement_id UUID REFERENCES ic_settlement(id);
ALTER TABLE ic_match ADD COLUMN settled_at TIMESTAMPTZ;

INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('ic_settlement','net_txn','inter_entity'),
    ('ic_settlement','hedged_txn','inter_entity'),
    ('ic_settlement','booked_functional','inter_entity'),
    ('ic_settlement','settled_rate','inter_entity'),
    ('ic_settlement','settled_functional','inter_entity'),
    ('ic_settlement','realized_fx','inter_entity')
ON CONFLICT (object_type, field) DO NOTHING;

-- The extension point again: settlement legs join the claims (spec doc 31 §1.4 / V1_0_64 §2).
CREATE OR REPLACE VIEW ledger_claim (fact_table, fact_id, leg, tb_transfer_id) AS
  SELECT 'revenue_recognition', dispatch_id::text, 'ar',       ar_transfer_id       FROM revenue_recognition WHERE ar_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'revenue_recognition', dispatch_id::text, 'vat',      vat_transfer_id      FROM revenue_recognition WHERE vat_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'revenue_recognition', dispatch_id::text, 'cogs',     cogs_transfer_id     FROM revenue_recognition WHERE cogs_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'revenue_recognition', dispatch_id::text, 'carriage', carriage_transfer_id FROM revenue_recognition WHERE carriage_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'invoice_reversal', id::text, 'rev_ar',       rev_ar_transfer_id       FROM invoice_reversal WHERE rev_ar_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'invoice_reversal', id::text, 'rev_vat',      rev_vat_transfer_id      FROM invoice_reversal WHERE rev_vat_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'invoice_reversal', id::text, 'rev_cogs',     rev_cogs_transfer_id     FROM invoice_reversal WHERE rev_cogs_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'invoice_reversal', id::text, 'rev_carriage', rev_carriage_transfer_id FROM invoice_reversal WHERE rev_carriage_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_match', dispatch_id::text, 'op',     op_leg_tb_transfer_id     FROM ic_match WHERE op_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_match', dispatch_id::text, 'pr',     pr_leg_tb_transfer_id     FROM ic_match WHERE pr_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_match', dispatch_id::text, 'rev_op', rev_op_leg_tb_transfer_id FROM ic_match WHERE rev_op_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_match', dispatch_id::text, 'rev_pr', rev_pr_leg_tb_transfer_id FROM ic_match WHERE rev_pr_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'payment', id::text, 'cash', tb_transfer_id FROM payment WHERE tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'payment_payout', payout_ref, 'bank', bank_tb_transfer_id FROM payment_payout WHERE bank_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'payment_payout', payout_ref, 'fee',  fee_tb_transfer_id  FROM payment_payout WHERE fee_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'vat_remittance', id::text, 'remit', tb_transfer_id FROM vat_remittance WHERE tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'intercompany_link', id::text, 'sell',      sell_tb_transfer_id      FROM intercompany_link WHERE sell_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'intercompany_link', id::text, 'buy',       buy_tb_transfer_id       FROM intercompany_link WHERE buy_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'intercompany_link', id::text, 'fx_bridge', fx_bridge_tb_transfer_id FROM intercompany_link WHERE fx_bridge_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'commission_entry', id::text, 'entry',  tb_transfer_id::numeric FROM commission_entry WHERE tb_transfer_id IS NOT NULL AND tb_transfer_id <> ''
  UNION ALL
  SELECT 'commission_entry', id::text, 'settle', settle_tb_transfer_id   FROM commission_entry WHERE settle_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'stock_adjustment', id::text, 'adjust', tb_transfer_id::numeric FROM stock_adjustment WHERE tb_transfer_id IS NOT NULL AND tb_transfer_id <> ''
  UNION ALL
  SELECT 'stock_count_line', id::text, 'variance', tb_transfer_id FROM stock_count_line WHERE tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rma', id::text, 'refund_ar',  refund_ar_transfer_id  FROM rma WHERE refund_ar_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rma', id::text, 'refund_vat', refund_vat_transfer_id FROM rma WHERE refund_vat_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rma', id::text, 'refund_ar_legacy', tb_reversal_group::numeric FROM rma
    WHERE tb_reversal_group IS NOT NULL AND tb_reversal_group <> '' AND refund_ar_transfer_id IS NULL
  UNION ALL
  SELECT 'rma_line', id::text, 'restock',   restock_tb_transfer_id   FROM rma_line WHERE restock_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rma_line', id::text, 'unwind_op', unwind_op_tb_transfer_id FROM rma_line WHERE unwind_op_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rma_line', id::text, 'unwind_pr', unwind_pr_tb_transfer_id FROM rma_line WHERE unwind_pr_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'migration_record', id::text, 'opening', tb_transfer_id FROM migration_record WHERE tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'rebate_posting', agreement_id::text, kind, tb_transfer_id FROM rebate_posting
  UNION ALL
  SELECT 'ic_remeasurement', id::text, 'remeasure', tb_transfer_id FROM ic_remeasurement WHERE tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_settlement', id::text, 'op_cash',    op_cash_tb_transfer_id    FROM ic_settlement WHERE op_cash_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_settlement', id::text, 'pr_cash',    pr_cash_tb_transfer_id    FROM ic_settlement WHERE pr_cash_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_settlement', id::text, 'fx_final',   fx_final_tb_transfer_id   FROM ic_settlement WHERE fx_final_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_settlement', id::text, 'fx_reclass', fx_reclass_tb_transfer_id FROM ic_settlement WHERE fx_reclass_tb_transfer_id IS NOT NULL;

-- Live hedged exposure now means unreversed AND unsettled (settlement consumes the hedge: notional stays
-- used, the live drawdown releases).
UPDATE control SET evidence_query =
   'SELECT count(*) FROM fx_hedge h
    WHERE h.ic_drawdown <> COALESCE((SELECT SUM(m.uplift_total - m.returned_uplift) FROM ic_match m
                                     WHERE m.rate_source = ''hedge:'' || h.id::text
                                       AND m.reversed_at IS NULL AND m.settlement_id IS NULL), 0)'
WHERE code = 'CTRL-HEDGE-LOCK';

-- CTRL-IC-SETTLE-ZERO: a settled run re-derives exactly — the covered set sums to the netted amount,
-- realized = settled − booked, cash claims exist iff money moved, the reclass claim iff FX realized, and
-- every stamped match points at a settled settlement.
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-IC-SETTLE-ZERO', 'IC settlement nets the covered set to zero',
   'Each settled run covers matches summing exactly to its netted amount, realizes settled − booked exactly once, and leaves no half-stamped match.',
   '{completeness,accuracy,existence}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (
      SELECT 1 FROM ic_settlement s
      WHERE s.status = ''settled'' AND (
            s.net_txn <> COALESCE((SELECT SUM(m.uplift_total - m.returned_uplift) FROM ic_match m
                                   WHERE m.settlement_id = s.id), 0)
         OR s.realized_fx <> s.settled_functional - s.booked_functional
         OR (s.op_cash_tb_transfer_id IS NULL AND round(s.net_txn, 2) <> 0)
         OR (s.pr_cash_tb_transfer_id IS NULL AND round(s.net_txn, 2) <> 0)
         OR (s.fx_reclass_tb_transfer_id IS NULL AND round(s.realized_fx, 2) <> 0))
      UNION ALL
      SELECT 1 FROM ic_match m LEFT JOIN ic_settlement s ON s.id = m.settlement_id
      WHERE m.settlement_id IS NOT NULL AND (s.id IS NULL OR s.status <> ''settled'')) v')
ON CONFLICT (code) DO NOTHING;
