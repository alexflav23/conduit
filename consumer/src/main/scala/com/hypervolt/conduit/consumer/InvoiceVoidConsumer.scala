package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import com.hypervolt.conduit.revenue.InvoiceVoidProcessor
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Performs the invoice invalidation the API requested (doc 13 §void). The API can't touch TigerBeetle, so it emits
// invoice.void_requested; this consumer (which owns the ledger) reverses the recognition and, for a refund, returns
// the cash. Own subscription on conduit.orders, Shared + Earliest, ack-good/nack-bad. Idempotent downstream.
final class InvoiceVoidConsumer[F[_]: Async](client: PulsarClient, processor: InvoiceVoidProcessor[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.orders"
  private val subscription = "conduit-invoice-voider-1"

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
    subscribe.use(c => logger.info(s"Invoice void consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("invoice void failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: EventEnvelope): F[Unit] =
    InvoiceVoidConsumer.voidRequested(env) match {
      case None                                          => Async[F].unit // not a void request
      case Some((invId, invoiceNo, kind, reason, actor)) =>
        // the void request is the cause of the reversal — thread its event id for the causal chain
        val causedBy = scala.util.Try(UUID.fromString(env.event_id)).toOption
        processor.process(invId, invoiceNo, kind, reason, actor, causedBy).flatMap {
          case Right(_) => logger.info(s"voided invoice $invoiceNo ($kind)")
          case Left(m)  => Async[F].raiseError(new RuntimeException(s"void failed for $invoiceNo: $m"))
        }
    }
}

object InvoiceVoidConsumer {
  // Pure: invoice.void_requested → (orderInvoiceId, invoiceNo, kind, reason, actor). Unit-testable without Pulsar.
  def voidRequested(env: EventEnvelope): Option[(UUID, String, String, String, String)] =
    if (env.event_type != "invoice.void_requested") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption.flatMap { j =>
        val c = j.hcursor
        (
          c.get[String]("order_invoice_id").toOption.flatMap(s => scala.util.Try(UUID.fromString(s)).toOption),
          c.get[String]("invoice_no").toOption
        ).mapN { (id, no) =>
          (
            id,
            no,
            c.get[String]("kind").toOption.getOrElse(""),
            c.get[String]("reason").toOption.getOrElse(""),
            c.get[String]("requested_by").toOption.getOrElse("system")
          )
        }
      }
}
