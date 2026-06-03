package com.hypervolt.conduit.event

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all._

// Where the relay publishes to. The Pulsar implementation lands in M1.4; the in-memory one backs tests.
trait EventPublisher[F[_]] {
  def publish(event: OutboxEvent): F[Unit]
}

final class InMemoryEventPublisher[F[_]](ref: Ref[F, Vector[OutboxEvent]]) extends EventPublisher[F] {
  def publish(event: OutboxEvent): F[Unit] = ref.update(_ :+ event)
  def published: F[Vector[OutboxEvent]]    = ref.get
}

object InMemoryEventPublisher {
  def create[F[_]: Sync]: F[InMemoryEventPublisher[F]] =
    Ref.of[F, Vector[OutboxEvent]](Vector.empty).map(new InMemoryEventPublisher[F](_))
}
