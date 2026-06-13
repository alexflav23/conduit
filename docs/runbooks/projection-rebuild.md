# Runbook — Projection rebuild & outbox-relay recovery

Fires on: **`ConduitOutboxBacklog`** (`outbox_unpublished_count > 0` sustained) — the relay is lagging/stuck — or
used deliberately to **rebuild a read-projection** that drifted or was corrupted. Invariant defended:
**CTRL-OUTBOX-DRAINED** (the relay publishes every committed event in `partition_key` order, promptly).

## Model
- Business row + outbox row commit in **one Postgres transaction**; the relay fiber (in the consumer) publishes
  unpublished rows in `seq` order and stamps `published_at`. At-least-once; every consumer idempotent on `event_id`.
- A **projection** (any read model derived from events) is a pure fold over the immutable `outbox_event` log.
  `ReplayService.rebuild(consumerGroup, aggregateType)(handler)` and `replayFrom(aggregateType, fromSeq, handler)`
  re-run the *same handler* over the log — same code path as live, no second write path — so a rebuild is
  byte-identical to having processed the events live (this is what `ReproSuite`/`CTRL-REPRO` proves).

## A. Relay backlog (events committed but not publishing)
1. **Confirm it's the relay, not a dead consumer:** check `ConduitConsumerDown` first. If the consumer is up but
   the backlog grows, the relay fiber is wedged.
   ```sql
   SELECT count(*) FROM outbox_event WHERE published_at IS NULL;
   SELECT min(seq), max(seq), now() - min(occurred_at) AS oldest_age
   FROM outbox_event WHERE published_at IS NULL;
   ```
2. **Check Pulsar reachability** from the consumer host (`pulsar-admin ... topics list public/default`) and that the
   `conduit.*` topics exist — a missing topic (e.g. a newly-added aggregate not provisioned) stalls the relay. If so,
   run `scripts/provision-pulsar.sh` (it is idempotent) and the relay drains on the next tick.
3. **Restart the consumer** if the fiber is wedged: the per-consumer `Supervised` backoff should recover it, but a
   bounce forces a clean reconnect. The relay resumes from the first `published_at IS NULL` row — no events lost,
   none double-counted (consumers dedupe on `event_id`).

## B. Rebuild a drifted/corrupted projection
Driven by **`scripting/ReplayCli rebuild <consumer-group> [aggregate-type]`** (same env config as above), which
re-emits the filtered log so the projection's consumer rebuilds from it.
1. **Quiesce** the projection's live consumer (so live + rebuild don't race), or rebuild into a shadow table and swap.
2. **Truncate** the projection's own tables (NOT `outbox_event` — that is the immutable source of truth).
3. `sbt "scripting/runMain com.hypervolt.conduit.scripting.ReplayCli rebuild <projection-group> <aggregate-type>"` —
   folds the whole log (filtered to the aggregate) through the projection's handler.
4. **Resume** the live consumer; it continues from where the rebuild left off (dedupe on `event_id` makes the overlap safe).

## Verify
- `outbox_unpublished_count` → 0 and `ConduitOutboxBacklog` clears.
- Re-perform the projection's reconciliation control (e.g. `gl_vs_tb` for the ledger mirror, or the relevant
  `CTRL-*`) — it must tie out. A rebuilt projection that re-derives the same control result is the proof the
  rebuild was faithful (CTRL-REPRO).
- Spot-check a known figure end-to-end via the Proof Center / lineage explorer.
