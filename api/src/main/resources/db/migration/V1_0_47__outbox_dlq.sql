-- M-NFR.2 (doc 19 §C.4, §C.3) — the dead-letter + completeness substrate for the ops/DR posture. The outbox_event
-- log is already the durable truth (doc 01 §3a); this adds (a) a DLQ for poison messages a consumer can't process,
-- so they are parked not lost, and (b) two re-performable completeness controls. Replay/projection-rebuild read the
-- outbox_event log directly (OutboxRepo.fetchFrom) — no new store of truth.

CREATE TABLE outbox_dlq (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_group TEXT NOT NULL,
    event_id       UUID NOT NULL REFERENCES outbox_event(event_id),
    reason         TEXT,
    attempts       INTEGER NOT NULL DEFAULT 1,
    failed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at    TIMESTAMPTZ,                       -- set when a fix-then-replay drains it (kept, not deleted — audit)
    UNIQUE (consumer_group, event_id)
);
CREATE INDEX outbox_dlq_open_idx ON outbox_dlq (consumer_group) WHERE replayed_at IS NULL;

-- Completeness / ops controls (doc 14 §4, re-performable evidence_query → violation count; 0 = pass).
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-DLQ-EMPTY', 'No undrained dead-letters',
   'Every poison message has been fixed and replayed; no event is parked unprocessed.',
   '{completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM outbox_dlq WHERE replayed_at IS NULL'),
  ('CTRL-OUTBOX-DRAINED', 'Outbox relay is draining',
   'No outbox event is stuck unpublished beyond the relay SLO — the spine is not silently stalling.',
   '{completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM outbox_event WHERE status = ''pending'' AND created_at < now() - interval ''5 minutes''')
ON CONFLICT (code) DO NOTHING;
