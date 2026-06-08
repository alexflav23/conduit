-- M13-VAT.2 — recognition now sources VAT from the tax engine (the authoritative invoice quote) and attributes it
-- to the place-of-supply jurisdiction. The jurisdiction + the immutable tax_quote it was determined from are
-- recorded on the recognition row, so the per-jurisdiction VAT exposure is a reproducible projection over immutable
-- records (doc 16 §7) and reversals/returns net the right jurisdiction.
ALTER TABLE revenue_recognition ADD COLUMN IF NOT EXISTS vat_jurisdiction CHAR(2);
ALTER TABLE revenue_recognition ADD COLUMN IF NOT EXISTS tax_quote_id     UUID;
