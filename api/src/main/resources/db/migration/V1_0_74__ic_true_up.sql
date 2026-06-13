-- M-IC-FX slice 5 (spec doc 28 §5.6): §482 / OECD year-end transfer-pricing true-up. A governed event
-- (maker <> checker) adjusts the PERIOD's AGGREGATE intercompany uplift to the arm's-length target — one
-- matched journal pair (the same IC_AP/IC_AR/IC_MARGIN shape as the flash hop, sign-aware), eliminated at
-- group. The adjustment is allocated conservingly across the period's matches FOR TP DOCUMENTATION ONLY
-- (ic_true_up_line) — the ic_match rows are NEVER rewritten (L6). This is §482 compliance, a DIFFERENT
-- standard from the ASC-606 customer rebate (doc 24) — similar machinery, separate row in the A3 matrix,
-- never conflated.
CREATE TABLE ic_true_up (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    procurement_entity_id UUID NOT NULL REFERENCES entity(id),
    operating_entity_id   UUID NOT NULL REFERENCES entity(id),
    txn_currency          CHAR(3) NOT NULL,
    period_from           DATE NOT NULL,
    period_to             DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'proposed',  -- proposed | approved
    prior_uplift          NUMERIC(18,4),                     -- Σ (uplift − returned) booked over the window
    target_uplift         NUMERIC(18,4) NOT NULL,            -- the arm's-length aggregate the period should show
    adjustment            NUMERIC(18,4),                     -- target − prior (the only thing posted)
    op_leg_tb_transfer_id NUMERIC(39,0),                     -- DR COGS / CR IC_AP (operating), sign-aware
    pr_leg_tb_transfer_id NUMERIC(39,0),                     -- DR IC_AR / CR IC_MARGIN (principal), sign-aware
    proposed_by           UUID NOT NULL,
    approved_by           UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at           TIMESTAMPTZ
);
CREATE INDEX ic_true_up_pair_idx ON ic_true_up (procurement_entity_id, operating_entity_id, created_at DESC);

-- The TP-documentation allocation: which match (product/customer) each slice of the adjustment pertains to.
-- Σ(allocated) == adjustment, EXACTLY (the conserving largest-remainder allocator, L1). Documentation, not
-- a rewrite — ic_match is untouched.
CREATE TABLE ic_true_up_line (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    true_up_id  UUID NOT NULL REFERENCES ic_true_up(id) ON DELETE CASCADE,
    dispatch_id UUID NOT NULL REFERENCES dispatch(id),
    allocated   NUMERIC(18,4) NOT NULL
);
CREATE INDEX ic_true_up_line_idx ON ic_true_up_line (true_up_id);

INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('ic_true_up','prior_uplift','inter_entity'),
    ('ic_true_up','target_uplift','inter_entity'),
    ('ic_true_up','adjustment','inter_entity'),
    ('ic_true_up_line','allocated','inter_entity')
ON CONFLICT (object_type, field) DO NOTHING;

-- the settlement-aware claims view (V1_0_69) + the two true-up legs.
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
  SELECT 'ic_settlement', id::text, 'fx_reclass', fx_reclass_tb_transfer_id FROM ic_settlement WHERE fx_reclass_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_true_up', id::text, 'op', op_leg_tb_transfer_id FROM ic_true_up WHERE op_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'ic_true_up', id::text, 'pr', pr_leg_tb_transfer_id FROM ic_true_up WHERE pr_leg_tb_transfer_id IS NOT NULL;

-- CTRL-IC-TRUEUP: every approved true-up re-derives exactly — adjustment = target − prior, the allocation
-- conserves (Σ lines == adjustment), legs exist iff a non-zero adjustment posted, and maker ≠ checker.
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-IC-TRUEUP', '§482 true-up conserves and is governed',
   'Each approved transfer-pricing true-up posts adjustment = target − prior, allocates it conservingly across the period matches (Σ == adjustment), and was approved by someone other than its proposer.',
   '{accuracy,completeness,authorization}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (
      SELECT 1 FROM ic_true_up t WHERE t.status = ''approved'' AND (
            t.adjustment <> t.target_uplift - t.prior_uplift
         OR (t.approved_by IS NULL OR t.approved_by = t.proposed_by)
         OR (t.op_leg_tb_transfer_id IS NULL AND round(t.adjustment, 2) <> 0)
         OR round(t.adjustment, 2) <> COALESCE((SELECT round(SUM(l.allocated), 2) FROM ic_true_up_line l WHERE l.true_up_id = t.id), 0))
    ) v')
ON CONFLICT (code) DO NOTHING;
