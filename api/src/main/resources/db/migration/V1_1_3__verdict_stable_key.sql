-- Make model verdicts portable across machines: key the merge target on the survivor's stable MRPeasy NAME, not
-- an ephemeral party UUID (gen_random_uuid differs per boot). The committed account_match_verdicts.ndjson now
-- carries merge_into_name; the apply step resolves it to the current party id by display_name. So a fresh machine
-- reconstructs the exact merges from git with no API call.
ALTER TABLE hubspot_match_verdict ADD COLUMN IF NOT EXISTS merge_into_name text;
