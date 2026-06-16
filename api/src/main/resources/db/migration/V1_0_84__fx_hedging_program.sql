-- M12-Treasury: the FX hedging PROGRAM (operational treasury), provider-agnostic. The hedge ACCOUNTING (ASC-815
-- MTM — fx_hedge, hedge_valuation/disclosure/performance/economic_mtm) already exists; this is the layer that runs
-- the program per the board policy (doc: "Hypervolt - Ebury USD FX Hedging"): hedge the USD payables to the
-- contract manufacturer (Volex now, Luxshare from 2026-08) to protect GBP margin. Ebury is the FIRST provider;
-- others are an adapter away (mirrors tax_routing / TaxProvider). No provider is hard-coded.

-- FX hedge providers (treasury counterparties). 'adapter' selects the FxHedgeProvider implementation.
CREATE TABLE hedge_provider (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    adapter    TEXT NOT NULL DEFAULT 'manual',   -- 'ebury' | 'manual' | future API adapters
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A credit/hedging facility with a provider (e.g. the Ebury GBP 9,000,000 line). Contracts draw against it.
CREATE TABLE hedge_facility (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id          UUID NOT NULL REFERENCES hedge_provider(id),
    entity_id            UUID NOT NULL REFERENCES entity(id),
    pair_from            CHAR(3) NOT NULL,        -- GBP (functional/reporting)
    pair_to              CHAR(3) NOT NULL,        -- USD (the exposure currency)
    credit_limit         NUMERIC(18,2) NOT NULL,
    limit_currency       CHAR(3) NOT NULL,
    interest_free        BOOLEAN NOT NULL DEFAULT TRUE,
    margin_variation_pct NUMERIC(6,4) NOT NULL DEFAULT 0.0500,
    margin_call_pct      NUMERIC(6,4) NOT NULL DEFAULT 0.0500,  -- margin call if GBP appreciates >= this vs USD
    opened_on            DATE NOT NULL,
    status               TEXT NOT NULL DEFAULT 'active',        -- active | closed
    doc_ref              TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hedge_facility_provider_idx ON hedge_facility (provider_id, entity_id, status);

-- Program policy: the hedge ratio per exposure type (the 50% / 50% / 100% policy), effective-dated.
CREATE TABLE hedge_policy (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id          UUID NOT NULL REFERENCES entity(id),
    exposure_type      TEXT NOT NULL,             -- cm_payment | cm_prepayment | cm_deposit
    hedge_ratio        NUMERIC(6,4) NOT NULL,     -- 0.50 / 0.50 / 1.00
    tenor_months       INT NOT NULL DEFAULT 3,
    payment_terms_days INT NOT NULL DEFAULT 0,    -- 45 for cm_payment
    effective_from     DATE NOT NULL,
    effective_to       DATE,
    note               TEXT,
    UNIQUE (entity_id, exposure_type, effective_from)
);

-- Forecast USD exposure per supplier/type/month — drives required hedge notional = ratio x exposure.
CREATE TABLE hedge_exposure_forecast (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id     UUID NOT NULL REFERENCES entity(id),
    supplier      TEXT NOT NULL,                  -- Volex | Luxshare
    exposure_type TEXT NOT NULL,
    period_month  DATE NOT NULL,                  -- first of month
    amount_usd    NUMERIC(18,2) NOT NULL,
    source        TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, supplier, exposure_type, period_month)
);

-- Multi-party named approval of a hedge decision (the CEO/CTO/CFO/COO sign-off + the recorded provider acceptance).
CREATE TABLE hedge_approval (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hedge_id         UUID NOT NULL REFERENCES fx_hedge(id),
    decision         TEXT NOT NULL,               -- execute | extend | unwind | accept_terms
    required_role    TEXT NOT NULL,               -- ceo | cto | cfo | coo | treasury
    approver_user_id UUID REFERENCES app_user(id),
    approver_name    TEXT,                         -- named signatory (for seeded historical sign-offs)
    status           TEXT NOT NULL DEFAULT 'pending', -- pending | signed | declined
    signed_at        TIMESTAMPTZ,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hedge_approval_hedge_idx ON hedge_approval (hedge_id, decision);

-- Extend fx_hedge into a PROGRAM contract: facility/provider it belongs to, the contract label, instrument, the
-- ratio it represents, the exposure it hedges, and a parent link for maturity extensions. The existing `status`
-- gains the program lifecycle: proposed | approved | executed | extended | settled | unwound (app-enforced).
ALTER TABLE fx_hedge ADD COLUMN facility_id     UUID REFERENCES hedge_facility(id);
ALTER TABLE fx_hedge ADD COLUMN provider_id     UUID REFERENCES hedge_provider(id);
ALTER TABLE fx_hedge ADD COLUMN contract_no     TEXT;
ALTER TABLE fx_hedge ADD COLUMN instrument      TEXT NOT NULL DEFAULT 'forward';
ALTER TABLE fx_hedge ADD COLUMN hedge_ratio     NUMERIC(6,4);
ALTER TABLE fx_hedge ADD COLUMN parent_hedge_id UUID REFERENCES fx_hedge(id);
ALTER TABLE fx_hedge ADD COLUMN supplier        TEXT;          -- Volex | Luxshare (exposure being hedged)
ALTER TABLE fx_hedge ADD COLUMN exposure_type   TEXT;          -- cm_payment | cm_prepayment | cm_deposit

-- Treasury figures sit on the 'treasury' data layer (consistent with the hedge_valuation seeds in V1_0_75).
INSERT INTO field_layer_map (object_type, field, data_layer) VALUES
    ('hedge_facility','credit_limit','treasury'),
    ('hedge_facility','margin_call_pct','treasury'),
    ('hedge_facility','margin_variation_pct','treasury'),
    ('hedge_policy','hedge_ratio','treasury'),
    ('hedge_exposure_forecast','amount_usd','treasury'),
    ('fx_hedge','contract_no','treasury'),
    ('fx_hedge','hedge_ratio','treasury');

-- Seed the providers (global; no entity FK). Ebury is real and current; others register here so the program can
-- route to them without a code change (provider-agnostic). The facility, policy and the real contracts are seeded
-- at runtime once the operating entity exists (HedgeProgramSeed), since entity is empty in a fresh DB.
INSERT INTO hedge_provider (code, name, adapter) VALUES
    ('ebury','Ebury Partners','ebury')
ON CONFLICT (code) DO NOTHING;
