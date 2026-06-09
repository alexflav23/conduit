-- M-Forecast (doc 26 §4a): the deal-lifecycle snapshot behind the order-book structural model.
-- Loaded idempotently from ingest/hubspot/deals_lifecycle.ndjson by the SnapshotLoader; closed_at is
-- meaningful ONLY when is_closed (HubSpot stamps an *expected* close date on open deals).
CREATE TABLE deal_snapshot (
    deal_id        TEXT PRIMARY KEY,
    pipeline       TEXT NOT NULL,
    created_at     DATE NOT NULL,
    closed_at      DATE NULL,
    is_won         BOOLEAN NOT NULL DEFAULT false,
    is_closed      BOOLEAN NOT NULL DEFAULT false,
    amount         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    payment_method TEXT NULL
);

CREATE INDEX deal_snapshot_pipeline_created_idx ON deal_snapshot (pipeline, created_at);
