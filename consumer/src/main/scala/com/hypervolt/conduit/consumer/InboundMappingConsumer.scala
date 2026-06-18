package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.InboundEnvelope
import com.hypervolt.conduit.ingest.InboxRepo
import com.hypervolt.conduit.ingest.SnapshotLoader
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import doobie.util.transactor.Transactor
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// The mapping leg of the S1 inbox: consumes raw records off conduit.inbound and maps each through the SAME
// SnapshotLoader handler the boot ndjson load uses, committing the mapping + the inbox status flip in one tx.
// Mapping failures (unknown source family, bad payload, handler error) are QUARANTINED — markFailed retains the
// raw row + error and acks (a poison row is never redelivered forever), surfaced in the desk. Only an
// infrastructure failure (markFailed itself can't commit) nacks for retry. Inbound data is never lost.
final class InboundMappingConsumer[F[_]: Async](client: PulsarClient, xa: Transactor[F], loader: SnapshotLoader[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val subscription = "conduit-inbound-mapper-1"
  private val repo         = new InboxRepo[F](xa)

  private def subscribe: Resource[F, Consumer[InboundEnvelope]] =
    Resource.fromAutoCloseable(
      Async[F].blocking(
        client
          .newConsumer(AvroPulsarSchema.avroSchema[InboundEnvelope])
          .topic(InboundEnvelope.topic)
          .subscriptionName(subscription)
          .subscriptionType(SubscriptionType.Shared)
          .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
          .subscribe()
      )
    )

  def runForever: F[Unit] =
    subscribe.use(c =>
      logger.info(s"Inbound mapping consumer subscribed to ${InboundEnvelope.topic}") *> loop(c).foreverM
    )

  private def loop(c: Consumer[InboundEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("inbound mapping infra-failed; nacking for retry") *>
            Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: InboundEnvelope): F[Unit] =
    parse(new String(env.payload, StandardCharsets.UTF_8)) match {
      case Left(pe) =>
        repo.markFailed(env.source, env.dataset, env.source_id, s"payload parse failed: ${pe.getMessage}").void
      case Right(json) =>
        loader
          .mapInbound(env.source, env.dataset, json)(repo.markProcessed(env.source, env.dataset, env.source_id))
          .flatMap {
            case Right(_)  => Async[F].unit
            case Left(err) => repo.markFailed(env.source, env.dataset, env.source_id, err).void
          }
    }
}
