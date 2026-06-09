-- M-Pricing slice 3 (doc 24 §5): retrospective volume rebate — ASC 606 variable consideration. The earned rebate is
-- a DERIVED projection over the order stream + tier ladder (recomputable to the penny, never stored). It accrues on
-- the immutable TigerBeetle ledger as REBATE_ACCRUAL (a contra-revenue liability). SETTLEMENT — the customer actually
-- receiving the rebate — is a SEPARATE, discrete, maker-checker-governed, idempotent act that draws the accrual down.
-- This table records those discrete settlements (the only thing that isn't derivable). ACCRUE ≠ APPLY (doc 24 §5).

CREATE TABLE rebate_settlement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_id        UUID NOT NULL REFERENCES price_agreement(id),
    contract_year_index INTEGER NOT NULL,                 -- derived year N (doc 24 §5.1), recorded on settlement
    milestone           TEXT NOT NULL DEFAULT 'year_end', -- year_end | an agreed mid-term milestone
    entity_id           UUID NOT NULL,
    amount              NUMERIC(18,4) NOT NULL,           -- the earned amount being settled
    currency            CHAR(3) NOT NULL,
    status              TEXT NOT NULL DEFAULT 'proposed', -- proposed | approved (maker-checker)
    proposed_by         UUID,
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- one settlement per agreement / contract year / milestone — business-level idempotency for the draw-down
    UNIQUE (agreement_id, contract_year_index, milestone)
);
CREATE INDEX rebate_settlement_agreement_idx ON rebate_settlement (agreement_id, contract_year_index);

-- CTRL-REBATE-ACCRUAL (doc 24 §5.6): conservation guardrail — a rebate can never be over-settled. On the gl_entry
-- mirror, posted debits (settlements) to a REBATE_ACCRUAL account must never exceed posted credits (accruals), i.e.
-- outstanding = Σaccrued − Σsettled ≥ 0. Any account where settled > accrued is a violation. (role 19 = RebateAccrual.)
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-REBATE-ACCRUAL', 'Rebate accrual is never over-settled',
   'Posted settlements drawn from a REBATE_ACCRUAL account never exceed the accrued credits (outstanding >= 0); settled + outstanding = accrued.',
   '{valuation,rights_obligations}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (SELECT account_key, SUM(CASE WHEN side = ''debit'' AND posted THEN amount_minor ELSE 0 END) AS dr, SUM(CASE WHEN side = ''credit'' AND posted THEN amount_minor ELSE 0 END) AS cr FROM gl_entry WHERE account_role = 19 GROUP BY account_key) t WHERE t.dr > t.cr');
