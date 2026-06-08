package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Closes the document loop (doc 17): on `order.invoiced`, render + persist the legal invoice PDF via the
// DocumentService (Apache FOP → S3 WORM store). Its own subscription on conduit.orders, Shared + Earliest,
// ack-good/nack-bad. generateInvoice is idempotent on a deterministic document id, so at-least-once redelivery
// mints no second invoice or number. Runs in the consumer process (the API never renders).
final class DocumentGenerationConsumer[F[_]: Async](client: PulsarClient, service: DocumentService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-document-generator-1"

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
    subscribe.use(c => logger.info(s"Document generation consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("document generation failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: EventEnvelope): F[Unit] =
    DocumentGenerationConsumer.orderInvoiceId(env) match {
      case None if env.event_type == "order.invoiced" =>
        logger.warn(s"order.invoiced ${env.event_id} had no order_invoice_id — skipping document generation")
      case None => Async[F].unit // not an invoice event
      case Some(invId) =>
        service.generateInvoice(invId).flatMap {
          case Right(r) => logger.info(s"generated invoice document ${r.formattedNumber} for $invId")
          case Left(m)  => Async[F].raiseError(new RuntimeException(s"document generation failed for $invId: $m"))
        }
    }
}

object DocumentGenerationConsumer {
  // Pure: filter order.invoiced + extract the order_invoice_id (unit-testable without Pulsar).
  def orderInvoiceId(env: EventEnvelope): Option[UUID] =
    if (env.event_type != "order.invoiced") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption
        .flatMap(_.hcursor.get[String]("order_invoice_id").toOption)
        .flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
}
