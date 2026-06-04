-- M10 part 2 — Migration & Cutover (doc 18). The provenance spine for bringing Conduit live as the system
-- of record from MRPeasy / Ghost Busters / Athena. `migration_record` is a first-class, replayable, audited
-- subsystem (doc 18 §3): every migrated figure traces back through transfer -> event -> source_payload -> the
-- legacy row, and re-running the backfill is a no-op (dedupe on (source, entity_type, source_id)).

-- The base table (doc 02 §L) merged with the §3.3 extension columns in one CREATE.
CREATE TABLE migration_record (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    source          TEXT          NOT NULL,                       -- mrpeasy | ghostbusters | athena
    entity_type     TEXT          NOT NULL,                       -- the Conduit table this row became
    source_id       TEXT          NOT NULL,                       -- the natural key in the source
    conduit_id      UUID          NOT NULL,                       -- the created/derived Conduit UUID (deterministic)
    batch_id        UUID          NOT NULL,                       -- the backfill run (correlation_id)
    source_payload  JSONB         NOT NULL,                       -- the raw source row, retained for re-performance (doc 14 §5)
    source_hash     TEXT          NOT NULL,                       -- hash of source_payload; detects source drift on re-run
    event_id        UUID          NULL,                           -- the emitted event (deterministic), for replay
    tb_transfer_id  NUMERIC(39,0) NULL,                           -- the opening transfer, where this row posted money
    phase           INTEGER       NOT NULL,                       -- §3.2 dependency phase
    status          TEXT          NOT NULL DEFAULT 'loaded',      -- loaded | reconciled | exception | superseded
    caveats         TEXT[]        NOT NULL DEFAULT '{}',          -- {landed_cost_partial, lot_inferred, synthetic_opening, fuzzy_merge}
    reconciled      BOOLEAN       NOT NULL DEFAULT false,
    reconciled_at   TIMESTAMPTZ   NULL,
    reconciled_by   UUID          NULL,                           -- -> app_user (sign-off, maker != checker)
    migrated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The dedupe key (doc 18 §3.1, idempotency layer 1): a row is skipped if already migrated.
CREATE UNIQUE INDEX uq_migration_source ON migration_record (source, entity_type, source_id);
CREATE INDEX ix_migration_batch   ON migration_record (batch_id, phase);
CREATE INDEX ix_migration_status  ON migration_record (status) WHERE status <> 'reconciled';
CREATE INDEX ix_migration_caveats ON migration_record USING GIN (caveats);

-- The migration run header (the `conduit-migrate` batch, doc 18 §3.4). One per `run`/`cutover`; maker-checker,
-- audited via audit_log. Gates G1..G6 evidence accrues against it before cutover may flip SoR.
CREATE TABLE migration_batch (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    label       TEXT        NOT NULL,
    source      TEXT        NOT NULL,                              -- mrpeasy | ghostbusters | athena | all
    status      TEXT        NOT NULL DEFAULT 'planning',          -- planning | running | dual_run | frozen | cutover | rolled_back
    started_by  UUID        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    frozen_at   TIMESTAMPTZ NULL,                                  -- source-freeze instant (read-only window begins)
    cutover_at  TIMESTAMPTZ NULL,                                  -- the instant Conduit became SoR
    gates       JSONB       NOT NULL DEFAULT '{}'                  -- {G1..G6: {green, evidence_ref, signed_off_by, at}}
);
