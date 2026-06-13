-- M-Ingest (spec doc 33 §2): the raw landing zone — every source row a connector pulls is recorded here,
-- idempotent on (source, dataset, source_id). Distinct from migration_record (which is the MAPPED source→Conduit
-- ledger with a conduit_id); this is "we have seen this source row", the source-of-truth side the DualRunReconciler
-- aggregates against. A re-pull whose payload hash differs flips `drifted` — the spec/18 §4.3 post-ingest edit.
CREATE TABLE ingest_record (
    source      TEXT NOT NULL,                 -- xero | hubspot | mrpeasy | athena | stripe
    dataset     TEXT NOT NULL,                 -- the logical stream within the source
    source_id   TEXT NOT NULL,                 -- the natural key in the source
    payload     JSONB NOT NULL,                -- the raw source row, retained for re-performance (doc 14 §5)
    source_hash TEXT NOT NULL,                 -- hash of payload; a change on re-pull = source drift
    drifted     BOOLEAN NOT NULL DEFAULT false,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, dataset, source_id)
);
CREATE INDEX ingest_record_drift_idx ON ingest_record (source) WHERE drifted;
