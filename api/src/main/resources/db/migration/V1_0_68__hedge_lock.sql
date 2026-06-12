-- M-IC-FX slice 2b (spec doc 28 §5.4b): hedges as rate-locks on the IC hop. Treasury negotiates them at
-- fiscal-period start with 6–12 month validities; the booking honors them (hedge -> spot -> fail-closed)
-- and the drawdown IS the live exposure — booked with the match, released by returns and voids.
--
-- ic_drawdown tracks the IC-booked live exposure SEPARATELY from notional_used (which M12 movements also
-- consume), so the lock control can assert exact equality rather than an inequality ceiling.
ALTER TABLE fx_hedge ADD COLUMN ic_drawdown NUMERIC(18,4) NOT NULL DEFAULT 0;

INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-HEDGE-LOCK', 'Hedge drawdown equals live hedged exposure',
   'Per hedge, the IC drawdown equals exactly the sum of live (unreversed, net of returns) hedge-booked match exposure — booking, returns and voids move capacity in lockstep with the exposure they cover.',
   '{accuracy,completeness,rights_obligations}', 'detective', 'continuous', true,
   'SELECT count(*) FROM fx_hedge h
    WHERE h.ic_drawdown <> COALESCE((SELECT SUM(m.uplift_total - m.returned_uplift) FROM ic_match m
                                     WHERE m.rate_source = ''hedge:'' || h.id::text
                                       AND m.reversed_at IS NULL), 0)')
ON CONFLICT (code) DO NOTHING;
