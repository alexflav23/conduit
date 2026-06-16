package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.commission.CommissionAccrualService
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import java.time.Instant
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// M5 — accrues sales commission on order placement. Consumes order.placed off conduit.orders and books the
// provisional PENDING accrual per line via CommissionAccrualService (TigerBeetle two-phase). Order-level
// idempotent so an at-least-once redelivery is a no-op; own subscription, Shared + Earliest, ack-good/nack-bad.
final class CommissionConsumer[F[_]: Async](client: PulsarClient, service: CommissionAccrualService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-commission-accrual-1"

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
    subscribe.use(c => logger.info(s"Commission accrual consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(c.receiveAsync()))
      .flatMap(msg =>
        handle(msg.getValue)
          .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
          .handleErrorWith(t =>
            logger.error(t)("commission accrual failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
          )
      )

  private def handle(env: EventEnvelope): F[Unit] =
    CommissionConsumer.orderOf(env) match {
      case None => Async[F].unit
      case Some(orderId) =>
        service
          .accrueForOrder(orderId, Instant.ofEpochMilli(env.occurred_at))
          .flatMap(n => if (n > 0) logger.info(s"accrued $n commission line(s) for order $orderId") else Async[F].unit)
    }
}

object CommissionConsumer {

  // Pure: an order.placed event → the order id. Unit-testable without Pulsar.
  def orderOf(env: EventEnvelope): Option[UUID] =
    if (env.aggregate_type == "order" && env.event_type == "order.placed")
      scala.util.Try(UUID.fromString(env.aggregate_id)).toOption
    else None
}
