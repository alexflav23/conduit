package com.hypervolt.conduit.event

import io.circe.Json
import java.time.Instant
import java.util.UUID

// The at-rest form of an event awaiting publication (doc 02 §L). Mirrors the event envelope (doc 03 §1);
// `payload` is JSONB at rest and Avro on the wire. `partition_key` is the ordering key (e.g. order_id, serial).
final case class OutboxEvent(
    eventId: UUID,
    eventType: String,
    schemaVersion: Int,
    aggregateType: String,
    aggregateId: UUID,
    partitionKey: String,
    scope: Option[Json],
    correlationId: Option[UUID],
    causationId: Option[UUID],
    payload: Json,
    occurredAt: Instant
)

// A pending row with its monotonic sequence (publication order).
final case class PendingOutbox(seq: Long, event: OutboxEvent)
