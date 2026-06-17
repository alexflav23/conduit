-- Attribute deals to the company (installer/wholesaler/retail customer) that placed them. The committed deal
-- scrape carried only pipeline + amount + dates — segment-level, never per-customer. The attributed scrape
-- (scripts/hubspot_deals_scrape.py → ingest/hubspot/deals_attributed.ndjson) pulls each deal's HubSpot company
-- association inline; these columns land it on deal_snapshot so the desk can show a per-company deal/PO book and
-- the forecast can attribute demand to a customer. company_id is the HubSpot company id (stable external ref).
ALTER TABLE deal_snapshot ADD COLUMN IF NOT EXISTS company_id text;
ALTER TABLE deal_snapshot ADD COLUMN IF NOT EXISTS company_name text;
ALTER TABLE deal_snapshot ADD COLUMN IF NOT EXISTS segment text;

CREATE INDEX IF NOT EXISTS deal_snapshot_company_idx ON deal_snapshot (company_id);
CREATE INDEX IF NOT EXISTS deal_snapshot_segment_idx ON deal_snapshot (segment);
