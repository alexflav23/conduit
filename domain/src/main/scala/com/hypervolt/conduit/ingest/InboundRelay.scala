package com.hypervolt.conduit.ingest

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.InboundEnvelope
import com.hypervolt.conduit.event.InboundPublisher
import doobie.util.transactor.Transactor
import java.nio.charset.StandardCharsets

// The inbound mirror of OutboxRelay (S1): reads the durably-landed 'received' rows from `ingest_record` in seq
// order, publishes each to conduit.inbound, then marks them 'published'. At-least-once — if a publish succeeds
// but the mark fails, the row is re-published and the mapping consumer dedupes (handlers upsert on the natural
// key). The PG row is the durable record; this is transport.
final class InboundRelay[F[_]: Async](xa: Transactor[F], publisher: InboundPublisher[F]) {

  private val repo = new InboxRepo[F](xa)

  def runOnce(batchSize: Int = 200): F[Int] =
    repo.fetchReceived(batchSize).flatMap { pending =>
      pending.traverse_(r =>
        publisher.publish(
          InboundEnvelope(
            r.source,
            r.dataset,
            r.sourceId,
            r.sourceHash,
            r.payload.noSpaces.getBytes(StandardCharsets.UTF_8)
          )
        )
      ) *>
        NonEmptyList
          .fromList(pending.map(r => (r.source, r.dataset, r.sourceId)))
          .fold(Async[F].pure(0))(keys => repo.markPublished(keys).as(pending.size))
    }
}
