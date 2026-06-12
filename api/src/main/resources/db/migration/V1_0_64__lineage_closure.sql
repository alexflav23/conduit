-- M-Assurance A2 (spec doc 29): CTRL-LINEAGE-CLOSURE — bidirectional, no orphans. The law (doc 30 L13):
-- if you post it, you record it. Every TigerBeetle transfer must be CLAIMED by exactly one business fact
-- column; every claimed leg must exist in the gl_entry mirror (both sides). The closure audit found six
-- posting sites whose legs were computed-but-ephemeral — this migration gives every leg a claim home, and
-- the services stamp claims iff the leg was actually posted (the Journal drops zero-amount legs, so an
-- unconditional stamp would be a false claim).

-- §1 the missing claim homes
ALTER TABLE invoice_reversal ADD COLUMN rev_carriage_transfer_id NUMERIC(39,0);
ALTER TABLE rma              ADD COLUMN refund_ar_transfer_id    NUMERIC(39,0);
ALTER TABLE rma              ADD COLUMN refund_vat_transfer_id   NUMERIC(39,0);
ALTER TABLE rma_line         ADD COLUMN restock_tb_transfer_id   NUMERIC(39,0);
ALTER TABLE rma_line         ADD COLUMN unwind_op_tb_transfer_id NUMERIC(39,0);
ALTER TABLE rma_line         ADD COLUMN unwind_pr_tb_transfer_id NUMERIC(39,0);
ALTER TABLE stock_count_line ADD COLUMN tb_transfer_id           NUMERIC(39,0);
ALTER TABLE commission_entry ADD COLUMN settle_tb_transfer_id    NUMERIC(39,0);

-- Stripe payouts posted two legs (bank, fee) with no fact table at all.
CREATE TABLE payment_payout (
    payout_ref          TEXT PRIMARY KEY,
    bank_tb_transfer_id NUMERIC(39,0),
    fee_tb_transfer_id  NUMERIC(39,0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rebate accrual/true-up/settlement ids are deterministic (doc 24 §5.5) but were never persisted —
-- reproducible-by-design is not traceable-by-SQL. One claim row per posted rebate movement.
CREATE TABLE rebate_posting (
    tb_transfer_id NUMERIC(39,0) PRIMARY KEY,
    agreement_id   UUID NOT NULL,
    kind           TEXT NOT NULL,                 -- accrue / trueup_up / trueup_down / settle
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- §2 the claims register: every transfer-id-bearing fact column, one row per claimed leg.
-- M-IC-FX (doc 28 §5.3–5.4): ic_settlement and remeasurement legs UNION in here when they land —
-- this view is the settlement-aware extension point the control closes over.
CREATE VIEW ledger_claim (fact_table, fact_id, leg, tb_transfer_id) AS
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
  -- pre-A2 refunds claimed leg 10 via the legacy text group field; honour it where the new column is empty
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
  SELECT 'rebate_posting', agreement_id::text, kind, tb_transfer_id FROM rebate_posting;

-- §3 the violations, each with the precise identity of the break (doc 29 A2: the control must NAME it).
CREATE VIEW lineage_closure_violation (kind, fact_table, fact_id, leg, tb_transfer_id) AS
  -- backward: a claimed leg whose gl_entry mirror is absent or one-sided
  SELECT 'missing_leg', c.fact_table, c.fact_id, c.leg, c.tb_transfer_id
  FROM ledger_claim c
  WHERE (SELECT count(*) FROM gl_entry g WHERE g.tb_transfer_id = c.tb_transfer_id) <> 2
  UNION ALL
  -- forward: a posted transfer no business fact claims (the "extra" leg)
  SELECT 'orphan_transfer', 'gl_entry', g.event_id::text, NULL::text, g.tb_transfer_id
  FROM (SELECT DISTINCT tb_transfer_id, event_id FROM gl_entry) g
  WHERE NOT EXISTS (SELECT 1 FROM ledger_claim c WHERE c.tb_transfer_id = g.tb_transfer_id)
  UNION ALL
  -- the mirror itself must be two-sided (one row per side, UNIQUE-capped at two)
  SELECT 'one_sided_mirror', 'gl_entry', min(event_id::text), NULL::text, tb_transfer_id
  FROM gl_entry GROUP BY tb_transfer_id HAVING count(*) <> 2
  UNION ALL
  -- structural: a flash match (or its reversal) missing the leg set its uplift demands
  SELECT 'incomplete_fact', 'ic_match', dispatch_id::text,
         CASE WHEN op_leg_tb_transfer_id IS NULL OR pr_leg_tb_transfer_id IS NULL
              THEN 'uplift_pair' ELSE 'reversal_pair' END,
         NULL::numeric
  -- round(_, 2) matches the minor-unit test in the services: a sub-penny uplift posts nothing, claims nothing
  FROM ic_match
  WHERE (round(uplift_total, 2) <> 0 AND (op_leg_tb_transfer_id IS NULL OR pr_leg_tb_transfer_id IS NULL))
     OR (reversed_at IS NOT NULL AND round(uplift_total, 2) <> 0
         AND (rev_op_leg_tb_transfer_id IS NULL OR rev_pr_leg_tb_transfer_id IS NULL));

-- §4 the controls (re-performable; ControlRunner counts violations, 0 = pass)
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-LINEAGE-CLOSURE', 'Money lineage closes in both directions',
   'Every GL figure traces to a claimed business fact and every recognized fact owns its complete posted leg set — no orphan transfers, no missing legs, no one-sided mirrors.',
   '{completeness,accuracy,existence}', 'detective', 'continuous', true,
   'SELECT count(*) FROM lineage_closure_violation'),
  ('CTRL-IC-MATCH', 'IC match journal integrity',
   'Every flash-title match decomposes exactly (uplift = transfer - landed), unwinds within bounds and with the uplift''s sign, and carries its full leg genealogy including reversals.',
   '{completeness,accuracy,valuation}', 'detective', 'continuous', true,
   'SELECT count(*) FROM ic_match
    WHERE uplift_total <> transfer_total - landed_total
       OR abs(returned_uplift) > abs(uplift_total)
       OR (returned_uplift <> 0 AND sign(returned_uplift) <> sign(uplift_total))
       OR (round(uplift_total, 2) <> 0 AND (op_leg_tb_transfer_id IS NULL OR pr_leg_tb_transfer_id IS NULL))
       OR (reversed_at IS NOT NULL AND reversal_id IS NULL)'),
  ('CTRL-IC-CATALOGUE', 'Transfer-price catalogue governance',
   'No active price list is self-approved or unapproved, and no two active lists overlap for the same procurement entity and market.',
   '{authorization,rights_obligations}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (
      SELECT 1 FROM transfer_price_list WHERE status = ''active'' AND (approved_by IS NULL OR approved_by = proposed_by)
      UNION ALL
      SELECT 1 FROM transfer_price_list a JOIN transfer_price_list b
        ON a.id < b.id AND a.procurement_entity_id = b.procurement_entity_id AND a.market_id = b.market_id
       AND a.status = ''active'' AND b.status = ''active''
       AND a.effective_from::date <= COALESCE(b.effective_to::date, DATE ''9999-12-31'')
       AND b.effective_from::date <= COALESCE(a.effective_to::date, DATE ''9999-12-31'')) v')
ON CONFLICT (code) DO NOTHING;
