-- M-IC-FX slice 1 (spec doc 28 §5.1): one moment fixes everything. The dispatch instant already binds the
-- catalogue version and the batch genealogy; it now also binds the BOOKED SPOT RATE into the principal's
-- functional currency. The IC pair stays transaction-currency on the ledger (one TB ledger per currency);
-- the functional measure is a stamped FACT on the match — the substrate remeasurement (§5.3) and settlement
-- (§5.4) compute from. Same-currency hops stamp the identity rate; a cross-currency hop with no rate row
-- FAILS CLOSED at recognition, exactly like an unpriced hop.
ALTER TABLE ic_match ADD COLUMN booked_rate               NUMERIC(18,8);
ALTER TABLE ic_match ADD COLUMN rate_source               TEXT;
ALTER TABLE ic_match ADD COLUMN principal_functional_ccy  CHAR(3);
ALTER TABLE ic_match ADD COLUMN transfer_total_functional NUMERIC(18,4);
