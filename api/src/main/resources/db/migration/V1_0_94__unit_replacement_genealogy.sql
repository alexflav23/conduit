-- Unit-replacement genealogy + warranty inheritance (support RMA lifecycle). A replaced unit's successor lives on
-- the ORIGINAL unit's timeline: serial 1235 that replaces 1234 inherits 1234's warranty_end — the warranty clock
-- never resets (decided), transitively to the root. The linkage (which unit replaced which) comes from HubSpot
-- support RMA tickets (rma_ticket), landed raw by the snapshot ingest and resolved to serials by boot ignition
-- (serials exist by then). No fabricated links — genealogy stays empty until real tickets land.

-- The genealogy pointer: the unit THIS serial replaced (NULL = an original, first-life unit).
ALTER TABLE serial_unit ADD COLUMN replaces_serial_unit_id UUID REFERENCES serial_unit(id);
CREATE INDEX serial_unit_replaces_idx ON serial_unit (replaces_serial_unit_id) WHERE replaces_serial_unit_id IS NOT NULL;

-- HubSpot support RMA tickets — the source of "which unit replaced which". Landed by source name; serials resolved
-- to ids by ignition. Idempotent on the HubSpot ticket id.
CREATE TABLE rma_ticket (
    ticket_ref                 TEXT PRIMARY KEY,           -- HubSpot ticket id
    source                     TEXT NOT NULL DEFAULT 'hubspot',
    original_serial            TEXT,                       -- the faulty/returned unit's serial_no
    replacement_serial         TEXT,                       -- the unit shipped to replace it
    original_serial_unit_id    UUID REFERENCES serial_unit(id),
    replacement_serial_unit_id UUID REFERENCES serial_unit(id),
    ticket_type                TEXT,                       -- warranty | rma | goodwill | ...
    reason                     TEXT,
    opened_at                  TIMESTAMPTZ,
    closed_at                  TIMESTAMPTZ,
    status                     TEXT,
    payload                    JSONB NOT NULL DEFAULT '{}',
    ingested_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rma_ticket_serials_idx ON rma_ticket (replacement_serial, original_serial);
