package com.hypervolt.conduit.event

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all._
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema

// The real publisher: maps each OutboxEvent to its Avro envelope, keys the message by partition_key
// (so Pulsar preserves per-aggregate ordering), and sends to the per-aggregate topic. Producers are
// created lazily and cached per topic.
final class PulsarEventPublisher[F[_]: Async](
    client: PulsarClient,
    cache: Ref[F, Map[String, Producer[EventEnvelope]]]
) extends EventPublisher[F] {

  def publish(event: OutboxEvent): F[Unit] =
    producerFor(Topics.forAggregate(event.aggregateType)).flatMap { producer =>
      Async[F]
        .fromCompletableFuture(
          Sync[F]
            .delay(producer.newMessage().key(event.partitionKey).value(EventEnvelope.fromOutbox(event)).sendAsync())
        )
        .void
    }

  private def producerFor(topic: String): F[Producer[EventEnvelope]] =
    cache.get.map(_.get(topic)).flatMap {
      case Some(producer) => Async[F].pure(producer)
      case None =>
        Sync[F]
          .delay(client.newProducer(PulsarEventPublisher.envelopeSchema).topic(topic).create())
          .flatTap(p => cache.update(_.updated(topic, p)))
    }
}

object PulsarEventPublisher {
  val envelopeSchema: Schema[EventEnvelope] = AvroPulsarSchema.avroSchema[EventEnvelope]

  def create[F[_]: Async](client: PulsarClient): F[PulsarEventPublisher[F]] =
    Ref.of[F, Map[String, Producer[EventEnvelope]]](Map.empty).map(new PulsarEventPublisher[F](client, _))
}
