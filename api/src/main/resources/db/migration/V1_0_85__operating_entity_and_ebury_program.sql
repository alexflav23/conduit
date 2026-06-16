-- Seed the real operating org + the actual Ebury hedging position (M12-Treasury). Single operating entity today:
-- Hypervolt UK Ltd (GB, GBP). The facility/policy/contracts are the real figures from the board's Ebury USD FX
-- hedging policy. All inserts are idempotent (guarded), so a fresh DB boots straight into the real position.

-- 1. The operating entity (the whole finance ignition — periods/revenue/GL/IC/hedging — hangs off this).
INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
SELECT 'Hypervolt UK Ltd', 'GB', 'GBP', 'operating'
WHERE NOT EXISTS (SELECT 1 FROM entity WHERE name = 'Hypervolt UK Ltd');

-- Register it as the GB selling entity.
INSERT INTO selling_entity (jurisdiction, entity_id)
SELECT 'GB', e.id FROM entity e
WHERE e.name = 'Hypervolt UK Ltd' AND NOT EXISTS (SELECT 1 FROM selling_entity WHERE jurisdiction = 'GB');

-- 2. The Ebury GBP/USD facility: £9,000,000, interest-free, 5% margin variation / 5% margin call, opened 12/06/2025.
INSERT INTO hedge_facility (provider_id, entity_id, pair_from, pair_to, credit_limit, limit_currency,
    interest_free, margin_variation_pct, margin_call_pct, opened_on, status, doc_ref)
SELECT p.id, e.id, 'GBP', 'USD', 9000000.00, 'GBP', TRUE, 0.0500, 0.0500, DATE '2025-06-12', 'active',
    'Hypervolt - Ebury USD FX Hedging'
FROM hedge_provider p, entity e
WHERE p.code = 'ebury' AND e.name = 'Hypervolt UK Ltd'
  AND NOT EXISTS (SELECT 1 FROM hedge_facility f WHERE f.entity_id = e.id AND f.provider_id = p.id);

-- 3. The policy: 50% CM payments (45-day terms), 50% CM prepayments, 100% the $1.5m Luxshare deposit.
INSERT INTO hedge_policy (entity_id, exposure_type, hedge_ratio, tenor_months, payment_terms_days, effective_from, note)
SELECT e.id, v.exposure_type, v.ratio, 3, v.terms, DATE '2025-06-13', v.note
FROM entity e, (VALUES
    ('cm_payment',    0.50, 45, '50% hedge of CM (Volex/Luxshare) payments, 45-day terms'),
    ('cm_prepayment', 0.50,  0, '50% hedge of Luxshare prepayments'),
    ('cm_deposit',    1.00,  0, '100% hedge of the $1.5m Luxshare deposit')
) AS v(exposure_type, ratio, terms, note)
WHERE e.name = 'Hypervolt UK Ltd'
ON CONFLICT (entity_id, exposure_type, effective_from) DO NOTHING;

-- 4. Contract 3 (real): forward, notional £3,310,714, executed. Original rate 1.3315, maturity-extended on
--    30/09/2025 to 1.3252 (a 63-pip / 0.47% reduction) and to 30/04/2026, giving a 66% hedge ratio.
INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, notional_used, valid_from, valid_to,
    status, designation, facility_id, provider_id, contract_no, instrument, hedge_ratio, supplier, exposure_type, doc_ref)
SELECT e.id, 'GBP', 'USD', 1.32520000, 3310714.0000, 0, DATE '2025-12-01', DATE '2026-04-30', 'executed', 'economic',
    f.id, p.id, 'Contract 3', 'forward', 0.6600, 'Volex', 'cm_payment',
    'Ebury Contract 3; original 1.3315 extended to 1.3252 (63 pip) on 2025-09-30'
FROM entity e
JOIN hedge_provider p ON p.code = 'ebury'
JOIN hedge_facility f ON f.entity_id = e.id AND f.provider_id = p.id
WHERE e.name = 'Hypervolt UK Ltd'
  AND NOT EXISTS (SELECT 1 FROM fx_hedge h WHERE h.entity_id = e.id AND h.contract_no = 'Contract 3');

-- 5. The May 1 - Sep 30 2026 continuation (proposed, pending approval): indicative 1.3290 vs the 1.2700 IB forecast,
--    50% Volex with the Luxshare transition in Aug 2026. Notional sized from the exposure forecast (next slice).
INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, notional_used, valid_from, valid_to,
    status, designation, facility_id, provider_id, contract_no, instrument, hedge_ratio, supplier, exposure_type, doc_ref)
SELECT e.id, 'GBP', 'USD', 1.32900000, 0, 0, DATE '2026-05-01', DATE '2026-09-30', 'proposed', 'economic',
    f.id, p.id, 'Continuation May-Sep 2026', 'forward', 0.5000, 'Volex', 'cm_payment',
    'Ebury indicative 1.3290 (vs 1.2700 IB forecast); 50% Volex + Luxshare transition Aug 2026'
FROM entity e
JOIN hedge_provider p ON p.code = 'ebury'
JOIN hedge_facility f ON f.entity_id = e.id AND f.provider_id = p.id
WHERE e.name = 'Hypervolt UK Ltd'
  AND NOT EXISTS (SELECT 1 FROM fx_hedge h WHERE h.entity_id = e.id AND h.contract_no = 'Continuation May-Sep 2026');

-- 6. Approvals — the real named sign-offs from the policy. Contract-3 extension: signed by CEO/CTO/CFO/COO
--    (30/09/2025 note). Continuation: pending CEO + CTO (10/04/2026 note).
INSERT INTO hedge_approval (hedge_id, decision, required_role, approver_name, status, signed_at)
SELECT h.id, 'extend', v.role, v.nm, 'signed', TIMESTAMPTZ '2025-09-30 00:00:00+00'
FROM fx_hedge h, (VALUES
    ('ceo','Flavian Alexandru'), ('cto','Benjamin Edwards'), ('cfo','Matthew Halstead'), ('coo','Ross Sheil')
) AS v(role, nm)
WHERE h.contract_no = 'Contract 3'
  AND NOT EXISTS (SELECT 1 FROM hedge_approval a WHERE a.hedge_id = h.id AND a.decision = 'extend' AND a.required_role = v.role);

INSERT INTO hedge_approval (hedge_id, decision, required_role, approver_name, status)
SELECT h.id, 'execute', v.role, v.nm, 'pending'
FROM fx_hedge h, (VALUES ('ceo','Flavian Alexandru'), ('cto','Benjamin Edwards')) AS v(role, nm)
WHERE h.contract_no = 'Continuation May-Sep 2026'
  AND NOT EXISTS (SELECT 1 FROM hedge_approval a WHERE a.hedge_id = h.id AND a.decision = 'execute' AND a.required_role = v.role);
