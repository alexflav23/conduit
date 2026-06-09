package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pricing.RebateAccrualService
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import java.time.Instant
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// doc 24 §5.2 — the continuous rebate accrual. Consumes order-economics events off conduit.orders and re-runs the
// expected-rebate true-up for the buyer's retrospective agreements (RebateAccrualService). The true-up is a
// state-keyed projection so an at-least-once redelivery is a no-op. Own subscription, Shared + Earliest,
// ack-good/nack-bad — the house consumer shape.
final class RebateAccrualConsumer[F[_]: Async](client: PulsarClient, service: RebateAccrualService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-rebate-accrual-1"

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
    subscribe.use(c => logger.info(s"Rebate accrual consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(c.receiveAsync()))
      .flatMap(msg =>
        handle(msg.getValue)
          .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
          .handleErrorWith(t =>
            logger.error(t)("rebate accrual failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
          )
      )

  private def handle(env: EventEnvelope): F[Unit] =
    RebateAccrualConsumer.orderOf(env) match {
      case None => Async[F].unit
      case Some(orderId) =>
        service
          .accrueForOrder(orderId, Instant.ofEpochMilli(env.occurred_at))
          .flatMap(n =>
            if (n > 0) logger.info(s"trued up $n retrospective agreement(s) for order $orderId") else Async[F].unit
          )
    }
}

object RebateAccrualConsumer {

  // The order-economics events that can move a rebate position (doc 24 §5.2).
  private val triggers = Set("order.placed", "order.amended", "order.cancelled", "invoice.voided")

  // Pure: a triggering order event → the order id. Unit-testable without Pulsar.
  def orderOf(env: EventEnvelope): Option[UUID] =
    if (env.aggregate_type == "order" && triggers.contains(env.event_type))
      scala.util.Try(UUID.fromString(env.aggregate_id)).toOption
    else None
}
