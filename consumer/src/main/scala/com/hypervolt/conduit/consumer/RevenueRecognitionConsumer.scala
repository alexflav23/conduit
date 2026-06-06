package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// ASC 606 recognition (doc 07 M13): on dispatch (control transfer), recognise revenue + COGS into the immutable
// TigerBeetle ledger at the dispatched units' specific batch cost. RevenueRecognitionService is idempotent
// (UNIQUE(dispatch_id) + deterministic transfer ids), so at-least-once redelivery is a no-op.
final class RevenueRecognitionConsumer[F[_]: Async](client: PulsarClient, service: RevenueRecognitionService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-revenue-recognizer-1"

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
    subscribe.use(c => logger.info(s"Revenue recognition consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("revenue recognition failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: EventEnvelope): F[Unit] =
    RevenueRecognitionConsumer.dispatchId(env) match {
      case None => Async[F].unit // not a dispatch event
      case Some(did) =>
        service.recognize(did).flatMap {
          case Right(_)  => Async[F].unit
          case Left(msg) => Async[F].raiseError(new RuntimeException(s"recognition failed for dispatch $did: $msg"))
        }
    }
}

object RevenueRecognitionConsumer {
  // Pure: filter dispatch.created + extract the dispatch_id (unit-testable without Pulsar).
  def dispatchId(env: EventEnvelope): Option[UUID] =
    if (env.event_type != "dispatch.created") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption
        .flatMap(_.hcursor.get[String]("dispatch_id").toOption)
        .flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
}
