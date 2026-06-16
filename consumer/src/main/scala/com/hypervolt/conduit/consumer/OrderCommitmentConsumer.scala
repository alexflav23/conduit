package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.order.OrderCommitmentService
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// M4 — records the sales backlog on order placement. Consumes order.placed off conduit.orders and writes the
// committed obligation via OrderCommitmentService (no GL — ASC 606 books at dispatch). Idempotent on order_id, so
// replaying the historical order book to rebuild the baseline is safe. Own subscription, Shared + Earliest.
final class OrderCommitmentConsumer[F[_]: Async](client: PulsarClient, service: OrderCommitmentService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-order-commitment-1"

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
    subscribe.use(c => logger.info(s"Order-commitment consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(c.receiveAsync()))
      .flatMap(msg =>
        handle(msg.getValue)
          .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
          .handleErrorWith(t =>
            logger.error(t)("order-commitment failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
          )
      )

  private def handle(env: EventEnvelope): F[Unit] =
    OrderCommitmentConsumer.orderOf(env) match {
      case None          => Async[F].unit
      case Some(orderId) => service.record(orderId).void
    }
}

object OrderCommitmentConsumer {

  def orderOf(env: EventEnvelope): Option[UUID] =
    if (env.aggregate_type == "order" && env.event_type == "order.placed")
      scala.util.Try(UUID.fromString(env.aggregate_id)).toOption
    else None
}
