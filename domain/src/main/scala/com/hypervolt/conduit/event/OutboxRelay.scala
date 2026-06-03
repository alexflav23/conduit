package com.hypervolt.conduit.event

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor

// Reads unpublished rows in `seq` order, publishes each (preserving per-partition order), then marks them
// published. At-least-once: if a publish succeeds but the mark fails, the event is re-published and the
// consumer dedupes on event_id (doc 01 §2, doc 03 §3).
final class OutboxRelay[F[_]: Async](xa: Transactor[F], publisher: EventPublisher[F]) {

  def runOnce(batchSize: Int = 100): F[Int] =
    OutboxRepo.fetchPending(batchSize).transact(xa).flatMap { pending =>
      pending.traverse_(p => publisher.publish(p.event)) *>
        NonEmptyList
          .fromList(pending.map(_.event.eventId))
          .fold(Async[F].pure(0))(ids => OutboxRepo.markPublished(ids).transact(xa).as(pending.size))
    }
}
