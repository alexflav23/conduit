package com.hypervolt.conduit.event

import cats.effect.Async
import cats.effect.Sync
import cats.syntax.all._
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema

// Transport for the inbox (S1): publishes each durably-landed inbound record to conduit.inbound, keyed by
// source so Pulsar preserves per-source ordering. Single topic, one cached producer — the inbound mirror of
// PulsarEventPublisher. Durability is the PG `ingest_record` row; this is just the wire to the mapping consumer.
trait InboundPublisher[F[_]] {
  def publish(env: InboundEnvelope): F[Unit]
}

final class PulsarInboundPublisher[F[_]: Async](producer: Producer[InboundEnvelope]) extends InboundPublisher[F] {
  def publish(env: InboundEnvelope): F[Unit] =
    Async[F]
      .fromCompletableFuture(
        Sync[F].delay(producer.newMessage().key(env.source).value(env).sendAsync())
      )
      .void
}

// Test/double publisher: captures every published envelope (mirrors InMemoryEventPublisher) — used by the S1
// inbox acceptance suite to assert the relay published the right rows in order.
final class InMemoryInboundPublisher[F[_]](ref: cats.effect.Ref[F, Vector[InboundEnvelope]])
    extends InboundPublisher[F] {
  def publish(env: InboundEnvelope): F[Unit] = ref.update(_ :+ env)
  def published: F[Vector[InboundEnvelope]]  = ref.get
}

object InMemoryInboundPublisher {
  def create[F[_]: cats.effect.Sync]: F[InMemoryInboundPublisher[F]] =
    cats.effect.Ref.of[F, Vector[InboundEnvelope]](Vector.empty).map(new InMemoryInboundPublisher[F](_))
}

object PulsarInboundPublisher {
  val schema: Schema[InboundEnvelope] = AvroPulsarSchema.avroSchema[InboundEnvelope]

  def create[F[_]: Async](client: PulsarClient): F[PulsarInboundPublisher[F]] =
    Sync[F]
      .delay(client.newProducer(schema).topic(InboundEnvelope.topic).create())
      .map(new PulsarInboundPublisher[F](_))
}
