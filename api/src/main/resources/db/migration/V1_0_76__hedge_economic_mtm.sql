-- M-IC-FX slice 4b (spec doc 28 §5.5 / §5.4b correction): the GAAP gross presentation. The recognized IC
-- monetary balance now floats at SPOT and remeasures through earnings (ASC 830 — slice 2 no longer excludes
-- hedged matches), and the hedge is an independent instrument whose period MTM posts through earnings
-- (HedgeValuationService) to OFFSET that remeasurement. Net earnings effect ≈ the hedged outcome, but the
-- gross presentation is correct and the balance carries at spot, not a frozen contracted rate.
--
-- Consequences of un-freezing the per-match lock (slice 2b):
--   * the hedge no longer locks the booking rate (FlashTitle.stampRate books spot) and no longer draws down
--     per-match capacity — so CTRL-HEDGE-LOCK (drawdown == Σ hedge-booked exposure) is retired: there is no
--     per-match hedge booking left to police. Hedge integrity is now CTRL-HEDGE-PERF (fair value re-derives)
--     + CTRL-LINEAGE-CLOSURE (the MTM leg is claimed).
UPDATE control SET status = 'superseded', updated_at = now() WHERE code = 'CTRL-HEDGE-LOCK';

-- the hedge MTM posting's claim (doc 29 A2): the period MTM leg's transfer id lands on the valuation row.
ALTER TABLE hedge_valuation ADD COLUMN tb_transfer_id NUMERIC(39,0);

-- ledger_claim (V1_0_74) + the hedge MTM leg.
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
  SELECT 'ic_true_up', id::text, 'pr', pr_leg_tb_transfer_id FROM ic_true_up WHERE pr_leg_tb_transfer_id IS NOT NULL
  UNION ALL
  SELECT 'hedge_valuation', id::text, 'mtm', tb_transfer_id FROM hedge_valuation WHERE tb_transfer_id IS NOT NULL;
