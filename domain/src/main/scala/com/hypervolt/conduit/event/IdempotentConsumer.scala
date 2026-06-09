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

  // Release a single claim so a failed event can be re-processed on replay (doc 19 §C.4.1).
  def release(consumerGroup: String, eventId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM consumer_dedupe WHERE consumer_group = $consumerGroup AND event_id = $eventId".update.run

  // Clear a group's whole dedupe set — the first step of a projection rebuild (doc 19 §C.4.2), so a full replay
  // is NOT deduped away as already-seen.
  def reset(consumerGroup: String): ConnectionIO[Int] =
    sql"DELETE FROM consumer_dedupe WHERE consumer_group = $consumerGroup".update.run
}

final class IdempotentConsumer[F[_]: Async](xa: Transactor[F], consumerGroup: String) {

  // Runs `handler` exactly once per (group, eventId); returns true if this delivery was the first.
  def process(eventId: UUID)(handler: F[Unit]): F[Boolean] =
    DedupeStore.claim(consumerGroup, eventId).transact(xa).flatMap {
      case true  => handler.as(true)
      case false => Async[F].pure(false)
    }

  // As `process`, but a handler FAILURE routes the event to the DLQ (a poison message, doc 19 §C.4.1) instead of
  // losing it or leaving it stuck-claimed: the dedupe claim is released so a fix-then-replay can re-run it.
  // Returns true iff the handler succeeded on this delivery.
  def processOrDlq(eventId: UUID)(handler: F[Unit]): F[Boolean] =
    DedupeStore.claim(consumerGroup, eventId).transact(xa).flatMap {
      case false => Async[F].pure(false) // already handled (deduped)
      case true =>
        handler.attempt.flatMap {
          case Right(_) => Async[F].pure(true)
          case Left(e) =>
            (DedupeStore.release(consumerGroup, eventId) *>
              DlqStore.record(consumerGroup, eventId, Option(e.getMessage).getOrElse(e.toString)))
              .transact(xa)
              .as(false)
        }
    }
}
