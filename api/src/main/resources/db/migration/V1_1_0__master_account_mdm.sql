-- Master-data layer (doc 02 party unification): one golden-record `party` per real customer, with every source
-- identity (MRPeasy customer, HubSpot company, HubSpot contact) bound to it through account_source_link, and a
-- review queue (account_link_candidate) for the fuzzy MRPeasy↔HubSpot matches a human must confirm. There is no
-- shared key across the systems, so correlation is a careful matching pipeline: deterministic links auto-bind,
-- everything ambiguous becomes a candidate — never a guessed merge.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- A normalized name for matching: lower-cased, MRP: prefix + common suffixes/stopwords stripped, punctuation
-- collapsed to single spaces. Immutable expression → a STORED generated column kept correct automatically.
ALTER TABLE party ADD COLUMN IF NOT EXISTS normalized_name text
  GENERATED ALWAYS AS (
    btrim(regexp_replace(
      regexp_replace(
        regexp_replace(lower(display_name), '^mrp:\s*', '', 'g'),
        '\y(ltd|limited|plc|llp|llc|inc|the|group|holdings|uk)\y', '', 'g'),
      '[^a-z0-9]+', ' ', 'g'))
  ) STORED;

CREATE INDEX IF NOT EXISTS party_normalized_name_trgm ON party USING gin (normalized_name gin_trgm_ops);

-- Every source identity that resolves to a master account. UNIQUE(source_system, source_id) enforces the golden
-- record — one HubSpot company / MRPeasy customer / contact maps to exactly one party.
CREATE TABLE IF NOT EXISTS account_source_link (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  party_id      uuid NOT NULL REFERENCES party(id) ON DELETE CASCADE,
  source_system text NOT NULL,                       -- mrpeasy | hubspot_company | hubspot_contact
  source_id     text NOT NULL,
  source_name   text,
  match_method  text NOT NULL DEFAULT 'exact',       -- seed | exact | fuzzy | manual
  confidence    numeric(4,3) NOT NULL DEFAULT 1.000,
  status        text NOT NULL DEFAULT 'linked',      -- linked | rejected
  linked_by     text,
  linked_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_id)
);
CREATE INDEX IF NOT EXISTS account_source_link_party_idx ON account_source_link (party_id);

-- The careful-correlation review queue: fuzzy company↔party matches a human accepts/rejects (maker-checker). One
-- row per (source, candidate party); accepting promotes it to an account_source_link.
CREATE TABLE IF NOT EXISTS account_link_candidate (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  party_id      uuid NOT NULL REFERENCES party(id) ON DELETE CASCADE,
  source_system text NOT NULL,
  source_id     text NOT NULL,
  source_name   text,
  score         numeric(4,3) NOT NULL,
  status        text NOT NULL DEFAULT 'pending',     -- pending | accepted | rejected
  reviewed_by   text,
  reviewed_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_id, party_id)
);
CREATE INDEX IF NOT EXISTS account_link_candidate_status_idx ON account_link_candidate (status, score DESC);

-- Raw HubSpot contact staging (ingest/hubspot/contacts.ndjson). SnapshotLoader lands the 154k contacts here with
-- no party FK; the correlation step materializes them into `contact` once each contact's company is bound to a
-- master party. Kept as the immutable source so re-correlation never needs another API pull.
CREATE TABLE IF NOT EXISTS hubspot_contact_raw (
  contact_id text PRIMARY KEY,
  email      text,
  first_name text,
  last_name  text,
  phone      text,
  company    text,
  company_id text,
  job_title  text,
  lifecycle  text,
  created    date
);
CREATE INDEX IF NOT EXISTS hubspot_contact_raw_company_idx ON hubspot_contact_raw (company_id);

-- Provenance + idempotency for materialized contacts: the HubSpot contact id, unique so re-correlation is a no-op.
ALTER TABLE contact ADD COLUMN IF NOT EXISTS hs_contact_id text;
CREATE UNIQUE INDEX IF NOT EXISTS contact_hs_contact_id_key ON contact (hs_contact_id) WHERE hs_contact_id IS NOT NULL;

-- Seed MRPeasy provenance: each existing party was minted from an MRPeasy customer NAME ("MRP: <name>") — the
-- only key that system carries. Record it as both an external_ref and a seed source link.
UPDATE party
SET external_refs = jsonb_set(external_refs, '{mrpeasy}', to_jsonb(regexp_replace(display_name, '^MRP:\s*', '')))
WHERE display_name LIKE 'MRP: %' AND NOT (external_refs ? 'mrpeasy');

INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
SELECT id, 'mrpeasy', regexp_replace(display_name, '^MRP:\s*', ''), regexp_replace(display_name, '^MRP:\s*', ''),
       'seed', 1.000, 'linked'
FROM party WHERE display_name LIKE 'MRP: %'
ON CONFLICT (source_system, source_id) DO NOTHING;
