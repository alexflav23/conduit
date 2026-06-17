-- Serial → owner genealogy (doc 07 M7 + doc 02). The placement registry (Athena DynamoDB) + prod Keycloak resolve
-- each charger's end-owner email; ingest/placements/serial_owner.ndjson stages it here, and the correlation
-- materialises an INDIVIDUAL master account per owner email (reconciled with any HubSpot contact of the same
-- email) and stamps serial_unit.owner_party_id — so every activated charger traces to the person who owns it.
CREATE TABLE IF NOT EXISTS placement_owner_raw (
  serial           text PRIMARY KEY,
  device_id        text,
  placement_id     text,
  keycloak_user_id text,
  owner_email      text,
  owner_name       text,
  placement_name   text,
  country          text
);
CREATE INDEX IF NOT EXISTS placement_owner_email_idx ON placement_owner_raw (lower(owner_email));

ALTER TABLE serial_unit ADD COLUMN IF NOT EXISTS owner_party_id uuid REFERENCES party(id);
CREATE INDEX IF NOT EXISTS serial_unit_owner_idx ON serial_unit (owner_party_id);

-- one individual master account per owner email (the golden record for a consumer who owns a charger)
CREATE UNIQUE INDEX IF NOT EXISTS party_owner_email_key
  ON party (lower(external_refs->>'owner_email')) WHERE external_refs ? 'owner_email';
