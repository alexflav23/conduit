-- S1 shadow-mode inbox (the mirror of outbox_event): evolve the raw landing zone `ingest_record` into a
-- relay-driven inbox so every record a live connector pulls is captured DURABLY before any mapping, then
-- transported to the mapping consumer over Pulsar (conduit.inbound). The hard shadow-mode constraint —
-- inbound data is NEVER lost — is made structural here: a row lands 'received' in one tx, the relay publishes
-- it and marks 'published', the mapping consumer maps it through the same SnapshotLoader handlers as boot and
-- marks 'processed'; an unmappable row goes 'failed' (quarantine) with the raw payload + error retained and is
-- surfaced in the desk — never dropped. A re-pull whose payload drifted resets the row to 'received' so it
-- re-flows (the relay re-publishes, the consumer re-maps the new shape).
ALTER TABLE ingest_record ADD COLUMN seq          BIGSERIAL;
ALTER TABLE ingest_record ADD COLUMN status       TEXT NOT NULL DEFAULT 'received'; -- received | published | processed | failed
ALTER TABLE ingest_record ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE ingest_record ADD COLUMN processed_at TIMESTAMPTZ;
ALTER TABLE ingest_record ADD COLUMN attempts     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingest_record ADD COLUMN last_error   TEXT;

-- the relay's hot path: the unpublished backlog in arrival order (mirror of outbox_event_pending_idx)
CREATE INDEX ingest_record_received_idx ON ingest_record (seq) WHERE status = 'received';
-- the quarantine desk view: rows that failed to map, raw payload retained
CREATE INDEX ingest_record_failed_idx ON ingest_record (source, dataset) WHERE status = 'failed';

-- the dual-run owners can inspect the inbox + its quarantine (same set that sees sync_state)
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'ingest_record', 'view', '{volume}'::text[], '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'ingest_record' AND p.action = 'view');
