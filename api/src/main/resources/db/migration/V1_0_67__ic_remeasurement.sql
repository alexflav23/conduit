-- M-IC-FX slice 2 (spec doc 28 §5.3): ASC 830-20-35 period-end remeasurement, delta method. For each open
-- IC pair whose principal functional currency differs from the transaction currency, the run measures the
-- open balance at the closing rate and posts ONLY THE DELTA since the last remeasurement — append-only, one
-- provenanced row per pair per run, the inputs stored so the figure re-derives exactly (the tax_quote /
-- consolidation_line pattern). The posting rides the PRINCIPAL'S FUNCTIONAL ledger: the transaction-currency
-- ledger is untouched truth; FX_GAINLOSS is the only account allowed to absorb rate movement (doc 28 §5.2).
CREATE TABLE ic_remeasurement (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    procurement_entity_id UUID NOT NULL REFERENCES entity(id),
    operating_entity_id   UUID NOT NULL REFERENCES entity(id),
    txn_currency          CHAR(3) NOT NULL,
    functional_currency   CHAR(3) NOT NULL,
    as_of                 DATE NOT NULL,
    open_txn              NUMERIC(18,4) NOT NULL,   -- Σ (uplift − returned) over unreversed matches, at run time
    closing_rate          NUMERIC(18,8) NOT NULL,
    rate_source           TEXT NOT NULL,            -- 'closing:<as_of>' — provenanced like every rate
    carrying_before       NUMERIC(18,4) NOT NULL,   -- booked functional + Σ prior deltas, at run time
    measured              NUMERIC(18,4) NOT NULL,   -- open_txn × closing_rate
    delta                 NUMERIC(18,4) NOT NULL,   -- measured − carrying_before (the only thing posted)
    tb_transfer_id        NUMERIC(39,0),            -- claim iff posted: a zero delta posts nothing
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ic_remeasurement_pair_idx
    ON ic_remeasurement (procurement_entity_id, operating_entity_id, created_at DESC);

-- field_layer_map: the remeasurement is principal-side truth — inter_entity, like the match it measures.
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('ic_remeasurement','open_txn','inter_entity'),
    ('ic_remeasurement','closing_rate','inter_entity'),
    ('ic_remeasurement','carrying_before','inter_entity'),
    ('ic_remeasurement','measured','inter_entity'),
    ('ic_remeasurement','delta','inter_entity')
ON CONFLICT (object_type, field) DO NOTHING;

-- The settlement-aware extension point doing its job (V1_0_64 §2): remeasurement legs join the claims.
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
  SELECT 'ic_remeasurement', id::text, 'remeasure', tb_transfer_id FROM ic_remeasurement WHERE tb_transfer_id IS NOT NULL;

-- CTRL-IC-REMEASURE: every remeasurement row re-derives exactly — measured = open × rate, delta = measured −
-- carrying, the posted leg carries exactly |delta| in functional minor units, and the claim exists iff a
-- non-zero delta posted. (No row-to-row chain check: carrying_before re-derives from the CURRENT unreversed
-- match set, so voids and new dispatches legitimately move it between runs — the truing-to-zero property is
-- pinned by IcRemeasureSuite instead.)
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-IC-REMEASURE', 'IC remeasurement re-derives exactly',
   'Each ASC 830 remeasurement row is internally exact and its posted delta matches the journal to the minor unit.',
   '{accuracy,valuation,completeness}', 'detective', 'at_close', true,
   'SELECT count(*) FROM (
      SELECT 1 FROM ic_remeasurement r
      WHERE round(r.open_txn * r.closing_rate, 4) <> r.measured
         OR r.delta <> r.measured - r.carrying_before
         OR (r.tb_transfer_id IS NULL AND round(r.delta, 2) <> 0)
         OR (r.tb_transfer_id IS NOT NULL AND round(r.delta, 2) = 0)
      UNION ALL
      SELECT 1 FROM ic_remeasurement r
      JOIN gl_entry g ON g.tb_transfer_id = r.tb_transfer_id AND g.side = ''debit''
      WHERE g.amount_minor <> round(abs(r.delta) * 100, 0)) v')
ON CONFLICT (code) DO NOTHING;
