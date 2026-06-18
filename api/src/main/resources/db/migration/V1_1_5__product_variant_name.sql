-- C5/preview: product_variant had no human name — line items rendered the synthetic family "MRPeasy import".
-- Add a real product name (the MRPeasy item title) so documents, the catalogue and the CRM read meaningfully.
-- Backfilled by IgnitionService.backfillProductNames from ingest/mrpeasy/items.ndjson (sku -> code title match).
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS name text;

-- Staging for the MRPeasy article catalogue (ingest/mrpeasy/items.ndjson): code -> real title.
CREATE TABLE IF NOT EXISTS mrpeasy_item_raw (
  code  text PRIMARY KEY,
  title text NOT NULL,
  grp   text
);
