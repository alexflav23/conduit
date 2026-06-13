# Runbook — Dead-letter triage & replay

Fires on: **`ConduitDLQNotEmpty`** (`dlq_depth > 0`). Invariant defended: **CTRL-DLQ-EMPTY** — a healthy system
dead-letters nothing. A DLQ entry means a consumer failed an event repeatedly (after the redelivery backoff) and
parked it in `outbox_dlq` rather than blocking the subscription.

## Model (doc 19 §C.4)
- Consumers are idempotent on `event_id` via `IdempotentConsumer.processOrDlq(eventId)(handler)`: success marks the
  event done for the consumer group; repeated failure records it in `outbox_dlq` (the poison-message sink).
- The **outbox is the immutable log**. Replay re-runs the *same handler* over the original event — there is no
  second write path, so a replayed event posts exactly what a first-time delivery would (idempotent on the
  deterministic TB transfer id, so a partially-applied event is safe to replay).
- `ReplayService` (`domain/.../event/ReplayService.scala`) is the operator surface: `dlqDepth`,
  `replayDlq(consumerGroup)(handler)`. `IdempotentConsumer.release(group, eventId)` / `reset(group)` clear dedupe state.

## Triage
1. **See what's parked and why:**
   ```sql
   SELECT consumer_group, event_id, reason, count(*) OVER () AS total
   FROM outbox_dlq ORDER BY recorded_at DESC LIMIT 50;
   ```
   `reason` carries the failure (the exception message). Group by `consumer_group` to see which consumer is failing.
2. **Read the offending event** from the immutable log:
   ```sql
   SELECT event_type, aggregate_type, aggregate_id, payload, occurred_at
   FROM outbox_event WHERE event_id = '<id>';
   ```
3. **Classify the cause:**
   - *Transient* (DB blip, TB unreachable, downstream timeout): no code change — just replay.
   - *Poison/data* (a genuine bug, a malformed payload, a missing reference row): **fix forward first** (patch the
     handler or correct the referenced data), deploy, then replay. Never replay a known-bad event into the ledger.

## Replay
The operator entrypoint is **`scripting/ReplayCli`** (reads `CONDUIT_JDBC_URL`/`_DB_USER`/`_DB_PASSWORD`,
`PULSAR_SERVICE_URL` from the env). Replay re-emits the events to their topics so the live, idempotent-on-
`event_id` consumers reprocess them — the same handler, no second write path.

1. Ensure the fix (if any) is deployed.
2. Check the depth: `sbt "scripting/runMain com.hypervolt.conduit.scripting.ReplayCli dlq-depth"`.
3. Replay the failing group:
   `sbt "scripting/runMain com.hypervolt.conduit.scripting.ReplayCli replay-dlq <consumer-group>"` — it re-feeds
   each DLQ'd event, and on success removes it from `outbox_dlq` and marks it replayed.
4. If a consumer marked the event done-but-failed mid-way, `IdempotentConsumer.release("<group>", "<event_id>")`
   first so the handler re-runs (the TB legs are idempotent on transfer id, so this is safe).

## Verify
- `SELECT count(*) FROM outbox_dlq;` → 0, and the `ConduitDLQNotEmpty` alert clears.
- Re-run the relevant control (e.g. the ledger mirror `gl_vs_tb`, or `CTRL-LINEAGE-CLOSURE`) to confirm the replayed
  event left the books consistent — re-perform it from the Auditability/Proof surface.
- Confirm no new DLQ rows accrued (the root cause is actually fixed, not just drained).
