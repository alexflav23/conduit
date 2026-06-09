package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.privacy.PiiTombstoneService
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// doc 19 §B.3.3 step 5 — tombstone propagation. Consumes `pii.shredded` off conduit.crm and overwrites the subject's
// served PII columns with the `«erased»` tombstone (the DEK is already destroyed, so the vault ciphertext is gone;
// this closes the denormalised read-model columns). Own subscription on conduit.crm, Shared + Earliest, ack-good/
// nack-bad; the propagate is idempotent so an at-least-once redelivery is a no-op.
final class PiiShreddedConsumer[F[_]: Async](client: PulsarClient, service: PiiTombstoneService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.crm"
  private val subscription = "conduit-pii-tombstone-1"

  private def subscribe: Resource[F, Consumer[EventEnvelope]] =
    Resource.fromAutoCloseable(
      Async[F].blocking(
        client
          .newConsumer(AvroPulsarSchema.avroSchema[EventEnvelope])
          .topic(topic)
          .subscriptionName(subscription)
          .subscriptionType(SubscriptionType.Shared)
          .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
          .subscribe()
      )
    )

  def runForever: F[Unit] =
    subscribe.use(c => logger.info(s"PII tombstone consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(c.receiveAsync()))
      .flatMap(msg =>
        handle(msg.getValue)
          .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
          .handleErrorWith(t =>
            logger.error(t)("PII tombstone failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
          )
      )

  private def handle(env: EventEnvelope): F[Unit] =
    PiiTombstoneService.shreddedSubject(env) match {
      case None    => Async[F].unit
      case Some(s) => service.propagate(s).flatMap(n => logger.info(s"tombstoned $n PII rows for shredded subject $s"))
    }
}
