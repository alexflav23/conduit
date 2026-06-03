-- Transactional outbox + event registry + audit (doc 02 §L, doc 03).
-- A business write and its outbox row commit in one transaction, so an event can never be lost or
-- emitted for an uncommitted change. The relay publishes pending rows in `seq` order (which preserves
-- per-`partition_key` order). `seq` also gives the gapless per-stream sequence the controls rely on.

CREATE TABLE outbox_event (
    event_id       UUID PRIMARY KEY,
    seq            BIGSERIAL UNIQUE,
    event_type     TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    partition_key  TEXT NOT NULL,
    scope          JSONB,
    correlation_id UUID,
    causation_id   UUID,
    payload        JSONB NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ,
    status         TEXT NOT NULL DEFAULT 'pending',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_event_pending_idx ON outbox_event (seq) WHERE status = 'pending';
CREATE INDEX outbox_event_aggregate_idx ON outbox_event (aggregate_type, aggregate_id);

-- Registry mirror; CI enforces BACKWARD compatibility against this (doc 03 §2).
CREATE TABLE event_schema (
    event_type    TEXT NOT NULL,
    version       INTEGER NOT NULL,
    encoding      TEXT NOT NULL DEFAULT 'avro',
    definition    JSONB NOT NULL,
    compatibility TEXT NOT NULL DEFAULT 'backward',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_type, version)
);

-- Replay control + consumer idempotency (dedupe on event_id, doc 03 §3).
CREATE TABLE consumer_checkpoint (
    consumer_group TEXT NOT NULL,
    partition      TEXT NOT NULL,
    last_event_id  UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, partition)
);

CREATE TABLE consumer_dedupe (
    consumer_group TEXT NOT NULL,
    event_id       UUID NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- Append-only projection of staff actions + field-level before/after; Admin cannot edit (doc 05 §5).
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type   TEXT NOT NULL,
    entity_id     UUID,
    action        TEXT NOT NULL,
    before        JSONB,
    after         JSONB,
    actor_user_id UUID,
    event_id      UUID,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at DESC);
