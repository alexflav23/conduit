-- The full HubSpot companies object (ingest/hubspot/companies.ndjson, ~25k). Deals reference only ~3k companies,
-- but contacts span all 25k — so the correlation needs a canonical name for every company_id a contact points at.
-- SnapshotLoader stages here; the correlation step uses it as the company universe (matched to existing parties
-- or minted as new master accounts), so every B2B contact attributes to an account.
CREATE TABLE IF NOT EXISTS hubspot_company_raw (
  company_id text PRIMARY KEY,
  name       text,
  domain     text,
  industry   text,
  country    text
);
