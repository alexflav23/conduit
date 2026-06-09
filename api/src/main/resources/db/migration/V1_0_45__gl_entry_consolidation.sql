-- M13b-GL (Option B, doc 14 §5–6) — the LEDGER READ-SIDE. gl_entry is a faithful, immutable Postgres mirror of
-- every TigerBeetle posting (written by the Journal at post time), so reconciliation, the GL trial balance and the
-- finance read-models run as plain SQL — no TB fan-out on the request path. TB stays the source of truth; gl_entry
-- is reconciled back to it by CTRL-GL-MIRROR, so any projection drift FAILS LOUDLY rather than hiding.
--
-- Faithful to two-phase (commission) and cross-currency (intercompany): one row PER (transfer, side) tagged with
-- the realisation `phase`; `posted` marks the rows that contribute to the POSTED balance (single + post_pending),
-- so SUM(posted, side='debit') − SUM(posted, side='credit') per account == TigerBeetle debits_posted − credits_posted.
-- Amounts are native-currency minor units; a translated amount is NEVER stored as a fact (it is re-projected).

CREATE TABLE gl_entry (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tb_transfer_id NUMERIC(39,0) NOT NULL,            -- the TigerBeetle transfer this row mirrors (128-bit)
    side           TEXT NOT NULL,                     -- 'debit' | 'credit'
    account_key    TEXT NOT NULL,                     -- 'AR:<party>' / 'REVENUE:<entity>' / 'VAT:<entity>:GB' / 'FX_CLEARING:GBP'
    account_role   INTEGER NOT NULL,                  -- LedgerAccountCode (GL role)
    entity_id      UUID,                              -- owning entity where the key carries one
    currency       CHAR(3) NOT NULL,                  -- native currency of the posting (the TB ledger)
    amount_minor   NUMERIC(39,0) NOT NULL,            -- transfer amount in minor units, always >= 0
    phase          TEXT NOT NULL DEFAULT 'single',    -- single | pending | post_pending | void_pending
    posted         BOOLEAN NOT NULL,                  -- contributes to the POSTED balance (single & post_pending)
    transfer_code  INTEGER NOT NULL,                  -- LedgerTransferCode
    event_id       UUID NOT NULL,                     -- the business event that caused the posting (lineage)
    occurred_at    TIMESTAMPTZ NOT NULL,              -- UTC instant — period assignment + as-of are re-projections
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tb_transfer_id, side)                     -- idempotent on redelivery (deterministic transfer id)
);
CREATE INDEX gl_entry_account_idx  ON gl_entry (account_key, posted);
CREATE INDEX gl_entry_entity_idx   ON gl_entry (entity_id, occurred_at);
CREATE INDEX gl_entry_currency_idx ON gl_entry (currency, posted);
CREATE INDEX gl_entry_event_idx    ON gl_entry (event_id);

-- Consolidation / translation (ASC 830, doc 14 §2.4 / doc 13 §7.2). A consolidation run is an IMMUTABLE, reproducible
-- snapshot: each entity's native-currency as-of balances translated to the presentation currency at a provenanced
-- rate (hedge-locked where designated, else the period closing/spot rate), with CTA as the balancing plug. Like a
-- tax_quote, the inputs (which rate id / which hedge id per line) are recorded so the figure re-derives exactly.
CREATE TABLE consolidation_run (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    as_of                 DATE NOT NULL,
    presentation_currency CHAR(3) NOT NULL,
    total_assets          NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_liabilities     NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_equity          NUMERIC(18,4) NOT NULL DEFAULT 0,
    cta                   NUMERIC(18,4) NOT NULL DEFAULT 0,   -- cumulative translation adjustment (the plug)
    fx_clearing_residual  NUMERIC(18,4) NOT NULL DEFAULT 0,   -- translated FX_CLEARING net (should be ~0)
    balanced              BOOLEAN NOT NULL DEFAULT false,
    run_by                UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE consolidation_line (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id               UUID NOT NULL REFERENCES consolidation_run(id),
    entity_id            UUID NOT NULL,
    account_key          TEXT NOT NULL,
    account_role         INTEGER NOT NULL,
    rate_class           TEXT NOT NULL,               -- monetary | pnl | equity
    functional_currency  CHAR(3) NOT NULL,
    balance_functional   NUMERIC(18,4) NOT NULL,
    rate                 NUMERIC(18,8) NOT NULL,
    rate_source          TEXT NOT NULL,               -- 'hedge:<id>' | 'closing' | 'spot' | 'identity'
    exchange_rate_id     UUID,
    fx_hedge_id          UUID,
    balance_presentation NUMERIC(18,4) NOT NULL
);
CREATE INDEX consolidation_line_run_idx ON consolidation_line (run_id);

-- Automated controls (doc 14 §4). Re-performable evidence_query returns the VIOLATION COUNT (0 = pass). The two
-- translation-integrity controls (CTA balance, FX_CLEARING nets to zero) need FX translation, so they are computed
-- and recorded by the ConsolidationService directly (evidence_query NULL); the rest are pure SQL.
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-GL-MIRROR', 'GL projection mirrors the ledger',
   'gl_entry posted balances tie to TigerBeetle per account; any drift surfaces as an unsigned gl_vs_tb exception.',
   '{completeness,accuracy}', 'detective', 'continuous', true,
   'SELECT count(*) FROM reconciliation WHERE type = ''gl_vs_tb'' AND status = ''exception'' AND signed_off_by IS NULL'),
  ('CTRL-HEDGE-DRAWDOWN', 'FX hedge draw-down within notional',
   'No FX hedge is drawn beyond its contracted notional (notional_used <= notional).',
   '{valuation,rights_obligations}', 'detective', 'continuous', true,
   'SELECT count(*) FROM fx_hedge WHERE notional_used > notional'),
  ('CTRL-FXRATE-COMPLETE', 'Translation rate completeness',
   'Every non-presentation currency holding a ledger balance has a provenanced rate to the presentation currency.',
   '{completeness,valuation}', 'detective', 'at_close', true,
   'SELECT count(*) FROM (SELECT DISTINCT currency FROM gl_entry WHERE currency <> ''USD'') c
      LEFT JOIN exchange_rate r ON r.base = c.currency AND r.quote = ''USD'' WHERE r.id IS NULL'),
  ('CTRL-CTA-BALANCE', 'Consolidated translation balances',
   'A consolidation run balances: translated assets = liabilities + equity + CTA (the CTA is the explained plug).',
   '{accuracy,presentation}', 'detective', 'at_close', true, NULL),
  ('CTRL-FXCLEARING-ZERO', 'FX clearing nets to zero',
   'Translated FX_CLEARING bridge accounts net to ~zero across completed cross-currency movements.',
   '{accuracy,completeness}', 'detective', 'at_close', true, NULL)
ON CONFLICT (code) DO NOTHING;

-- Grants (doc 14 §6): consolidation/CTA/translation is treasury-layer (doc 13 §9 field_layer_map); finance/CEO/auditor
-- read the consolidated figures, treasury sees the FX provenance detail. gl read sits with the existing finance views.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'gl_entry', 'view', NULL, '{commercial,inter_entity,treasury}', '{}', 'all'
FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'consolidation', 'view', NULL, '{commercial,inter_entity,treasury}', '{}', 'all'
FROM role WHERE name IN ('finance','admin','ceo','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'consolidation', 'edit', NULL, '{commercial,treasury}', '{commercial,treasury}', 'all'
FROM role WHERE name IN ('finance','admin');
