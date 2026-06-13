-- M-Ingest slice 1 (spec doc 33): the per-(source,dataset) sync cursor + run telemetry the IngestScheduler
-- reads and advances. One row per logical stream; the cursor (opaque — timestamp / id / page-token) only
-- advances after a batch commits, so a crash mid-batch re-pulls rather than skips. The desk renders this as the
-- sync-health board. Record-level dedupe stays in migration_record (V1_0_14) — this table is the cursor, not the
-- ledger. view:sync_state for finance/auditor/admin (the dual-run owners).
CREATE TABLE sync_state (
    source               TEXT NOT NULL,                 -- xero | hubspot | mrpeasy | athena | stripe
    dataset              TEXT NOT NULL,                 -- the logical stream within the source
    cursor               TEXT,                          -- resume point; NULL = cold (full backfill)
    last_run_at          TIMESTAMPTZ,
    last_status          TEXT,                          -- ok | error
    records_seen         BIGINT NOT NULL DEFAULT 0,     -- cumulative pulled
    records_written      BIGINT NOT NULL DEFAULT 0,     -- cumulative landed (non-dedup)
    consecutive_failures INTEGER NOT NULL DEFAULT 0,    -- backoff + the ingest_consecutive_failures gauge
    last_error           TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, dataset)
);

INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'sync_state', 'view', '{volume}'::text[], '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'sync_state' AND p.action = 'view');
