package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.accounting.InvoiceDispatcher
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

// Subscribes to conduit.orders and, on `order.invoiced`, hands the event to the InvoiceDispatcher (which pushes
// to the configured accounting consumer — Xero). Shared subscription, earliest position, ack-good/nack-bad.
// The dispatcher dedupes on event_id, so at-least-once redelivery is safe.
final class XeroInvoiceConsumer[F[_]: Async](client: PulsarClient, dispatcher: InvoiceDispatcher[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-xero-invoicer-1"

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
    subscribe.use(c => logger.info(s"Xero invoice consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("Xero invoice handling failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: EventEnvelope): F[Unit] =
    env.event_type match {
      case "order.invoiced" =>
        XeroInvoiceConsumer.extractInvoiceNo(env) match {
          case None        => logger.warn(s"order.invoiced ${env.event_id} had no invoice_no — skipping")
          case Some(invNo) => dispatcher.handle(UUID.fromString(env.event_id), invNo).void
        }
      case "invoice.voided" =>
        XeroInvoiceConsumer.extractVoid(env) match {
          case None                  => logger.warn(s"invoice.voided ${env.event_id} had no invoice_no — skipping")
          case Some((invNo, reason)) => dispatcher.handleVoid(UUID.fromString(env.event_id), invNo, reason).void
        }
      case _ => Async[F].unit // not an invoice event
    }
}

object XeroInvoiceConsumer {
  // Pure: the event-type filter + invoice_no extraction from the JSON payload (unit-testable without Pulsar).
  def extractInvoiceNo(env: EventEnvelope): Option[String] =
    if (env.event_type != "order.invoiced") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption
        .flatMap(_.hcursor.get[String]("invoice_no").toOption)
        .filter(_.nonEmpty)

  // Pure: invoice.voided → (invoice_no, reason) so the ERP invoice is voided downstream.
  def extractVoid(env: EventEnvelope): Option[(String, String)] =
    if (env.event_type != "invoice.voided") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption.flatMap { j =>
        val c = j.hcursor
        c.get[String]("invoice_no")
          .toOption
          .filter(_.nonEmpty)
          .map(no => (no, c.get[String]("reason").toOption.getOrElse("")))
      }
}
