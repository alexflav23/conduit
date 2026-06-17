-- Merge with perfect lineage (doc 02). When two source identities resolve to one real entity, the loser party
-- is MERGED into the survivor: its source-links + contacts re-point to the survivor (each tagged with where it
-- came from), the loser is preserved (status='merged', merged_into_party_id set) — never deleted — and an
-- account_merge audit row records from→to + method + confidence. So a merge is fully traceable and reversible.

ALTER TABLE party ADD COLUMN IF NOT EXISTS merged_into_party_id uuid REFERENCES party(id);
ALTER TABLE account_source_link ADD COLUMN IF NOT EXISTS merged_from_party_id uuid;
ALTER TABLE contact ADD COLUMN IF NOT EXISTS merged_from_party_id uuid;

CREATE TABLE IF NOT EXISTS account_merge (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  loser_party_id  uuid NOT NULL,
  winner_party_id uuid NOT NULL REFERENCES party(id),
  method          text NOT NULL,                 -- heuristic | model | manual
  confidence      numeric(4,3),
  reason          text,
  sources_moved   integer NOT NULL DEFAULT 0,
  contacts_moved  integer NOT NULL DEFAULT 0,
  merged_by       text,
  merged_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS account_merge_winner_idx ON account_merge (winner_party_id);
CREATE INDEX IF NOT EXISTS account_merge_loser_idx ON account_merge (loser_party_id);

-- Committed model verdicts (ingest/hubspot/account_match_verdicts.ndjson) — SnapshotLoader stages them here and
-- the apply step merges confidence>=0.9 the same way the heuristic does. Empty until the Bedrock matcher runs.
CREATE TABLE IF NOT EXISTS hubspot_match_verdict (
  hs_company_id        text PRIMARY KEY,
  merge_into_party_id  uuid,
  confidence           numeric(4,3),
  reason               text,
  model                text
);
