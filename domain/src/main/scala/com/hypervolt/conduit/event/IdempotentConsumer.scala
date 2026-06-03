package com.hypervolt.conduit.event

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// Consumer idempotency (doc 03 §3): every consumer dedupes on event_id. `claim` is a first-write-wins
// insert, so a redelivered event is a no-op for the handler — replaying twice yields identical state.
object DedupeStore {
  def claim(consumerGroup: String, eventId: UUID): ConnectionIO[Boolean] =
    sql"""INSERT INTO consumer_dedupe (consumer_group, event_id) VALUES ($consumerGroup, $eventId)
          ON CONFLICT DO NOTHING""".update.run.map(_ == 1)
}

final class IdempotentConsumer[F[_]: Async](xa: Transactor[F], consumerGroup: String) {

  // Runs `handler` exactly once per (group, eventId); returns true if this delivery was the first.
  def process(eventId: UUID)(handler: F[Unit]): F[Boolean] =
    DedupeStore.claim(consumerGroup, eventId).transact(xa).flatMap {
      case true  => handler.as(true)
      case false => Async[F].pure(false)
    }
}
