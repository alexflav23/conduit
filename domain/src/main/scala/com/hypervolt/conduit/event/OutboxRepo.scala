package com.hypervolt.conduit.event

import cats.data.NonEmptyList
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import java.time.Instant
import java.util.UUID

// Pure doobie (ConnectionIO) so `append` composes into the same transaction as the business write —
// that atomic commit is what makes the outbox lossless.
object OutboxRepo {

  def append(e: OutboxEvent): ConnectionIO[Int] =
    sql"""INSERT INTO outbox_event
            (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key,
             scope, correlation_id, causation_id, payload, occurred_at, origin)
          VALUES (${e.eventId}, ${e.eventType}, ${e.schemaVersion}, ${e.aggregateType}, ${e.aggregateId},
             ${e.partitionKey}, ${e.scope}, ${e.correlationId}, ${e.causationId}, ${e.payload}, ${e.occurredAt}, ${e.origin})
       """.update.run

  private type Row =
    (Long, UUID, String, Int, String, UUID, String, Option[Json], Option[UUID], Option[UUID], Json, Instant, String)

  def fetchPending(limit: Int): ConnectionIO[List[PendingOutbox]] =
    sql"""SELECT seq, event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key,
                 scope, correlation_id, causation_id, payload, occurred_at, origin
          FROM outbox_event WHERE status = 'pending' ORDER BY seq ASC LIMIT $limit"""
      .query[Row]
      .to[List]
      .map(_.map {
        case (seq, id, et, sv, at, ai, pk, sc, corr, caus, pl, occ, origin) =>
          PendingOutbox(seq, OutboxEvent(id, et, sv, at, ai, pk, sc, corr, caus, pl, occ, origin))
      })

  def markPublished(ids: NonEmptyList[UUID]): ConnectionIO[Int] =
    (fr"UPDATE outbox_event SET status = 'published', published_at = now() WHERE" ++
      Fragments.in(fr"event_id", ids)).update.run
}
