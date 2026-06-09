package com.hypervolt.conduit.event

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// The dead-letter store (doc 19 §C.4.1). A poison message is recorded here (not lost); a fix-then-replay drains it.
object DlqStore {
  def record(consumerGroup: String, eventId: UUID, reason: String): ConnectionIO[Int] =
    sql"""INSERT INTO outbox_dlq (consumer_group, event_id, reason) VALUES ($consumerGroup, $eventId, $reason)
          ON CONFLICT (consumer_group, event_id)
          DO UPDATE SET attempts = outbox_dlq.attempts + 1, reason = EXCLUDED.reason, failed_at = now()""".update.run

  def open(consumerGroup: String): ConnectionIO[List[UUID]] =
    sql"SELECT event_id FROM outbox_dlq WHERE consumer_group = $consumerGroup AND replayed_at IS NULL ORDER BY failed_at"
      .query[UUID]
      .to[List]

  def markReplayed(consumerGroup: String, eventId: UUID): ConnectionIO[Int] =
    sql"UPDATE outbox_dlq SET replayed_at = now() WHERE consumer_group = $consumerGroup AND event_id = $eventId".update.run

  def depth: ConnectionIO[Long] =
    sql"SELECT count(*) FROM outbox_dlq WHERE replayed_at IS NULL".query[Long].unique
}

// Replay & projection-rebuild over the immutable outbox log (doc 19 §C.4.2). The log is the truth; projections are
// derived and rebuildable by replaying it through the SAME handler the consumer uses — there is no second write
// path. DLQ-replay re-runs only the parked poison messages. Both are safe under at-least-once because consumers
// dedupe on event_id (IdempotentConsumer).
final class ReplayService[F[_]: Async](xa: Transactor[F]) {

  private val batch = 500

  // Rebuild a projection: clear the group's dedupe so the full replay isn't deduped away, then re-apply EVERY event
  // (optionally scoped to one aggregate type) oldest-first through the consumer's handler. Returns the count applied.
  def rebuild(consumerGroup: String, aggregateType: Option[String])(handler: OutboxEvent => F[Unit]): F[Int] =
    DedupeStore.reset(consumerGroup).transact(xa) *> replayFrom(aggregateType, 0L, handler)

  // Replay the log from a sequence cursor through `handler` (does not touch dedupe — caller decides). Streams in
  // batches so a long history doesn't load at once.
  def replayFrom(aggregateType: Option[String], fromSeq: Long, handler: OutboxEvent => F[Unit]): F[Int] =
    OutboxRepo.fetchFrom(aggregateType, fromSeq, batch).transact(xa).flatMap {
      case Nil => Async[F].pure(0)
      case page =>
        page.traverse_(p => handler(p.event)) *>
          replayFrom(aggregateType, page.last.seq, handler).map(_ + page.size)
    }

  // DLQ-replay (doc 19 §C.4.1): re-run the handler for each parked poison message; on success mark it replayed and
  // drain it. Scoped to the consumer group. Returns the count drained.
  def replayDlq(consumerGroup: String)(handler: OutboxEvent => F[Unit]): F[Int] =
    DlqStore.open(consumerGroup).transact(xa).flatMap { ids =>
      ids.foldLeftM(0) { (drained, eventId) =>
        OutboxRepo.fetchOne(eventId).transact(xa).flatMap {
          case None => Async[F].pure(drained) // event vanished — nothing to replay
          case Some(p) =>
            handler(p.event).attempt.flatMap {
              case Right(_) =>
                (DedupeStore.claim(consumerGroup, eventId) *> DlqStore.markReplayed(consumerGroup, eventId))
                  .transact(xa)
                  .as(drained + 1)
              case Left(_) => Async[F].pure(drained) // still failing — leave parked
            }
        }
      }
    }
}

// Ops completeness/health reads (doc 19 §C.2/§C.3 SLOs): the values the DLQ-depth / outbox-drained alarms watch.
object CompletenessRepo {
  def dlqDepth: ConnectionIO[Long] = DlqStore.depth

  def unpublishedOlderThan(minutes: Int): ConnectionIO[Long] =
    sql"SELECT count(*) FROM outbox_event WHERE status = 'pending' AND created_at < now() - make_interval(mins => $minutes)"
      .query[Long]
      .unique
}
