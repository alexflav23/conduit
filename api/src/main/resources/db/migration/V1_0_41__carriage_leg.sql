-- M13-VAT.2b — outbound carriage as a first-class recognised cost leg (doc 04 §Ledger). A cancellation recalls
-- revenue, VAT, COGS *and* shipping — so shipping must be a ledger leg that reverses like the rest. This proves the
-- per-event reversal model: adding a cost category is a new account code + a recorded leg, and the void path
-- reverses it automatically (no special-casing). Outbound carriage cost incurred per dispatch:
--   recognition  DR CARRIAGE_EXPENSE:<entity>  CR CARRIAGE_ACCRUAL:<entity>   (owed to the carrier)
--   reversal     DR CARRIAGE_ACCRUAL           CR CARRIAGE_EXPENSE            (nets to zero)
ALTER TABLE dispatch            ADD COLUMN IF NOT EXISTS shipping_cost NUMERIC(18,4) NOT NULL DEFAULT 0;
ALTER TABLE revenue_recognition ADD COLUMN IF NOT EXISTS shipping_cost        NUMERIC(18,4) NOT NULL DEFAULT 0;
ALTER TABLE revenue_recognition ADD COLUMN IF NOT EXISTS carriage_transfer_id NUMERIC(39,0);
