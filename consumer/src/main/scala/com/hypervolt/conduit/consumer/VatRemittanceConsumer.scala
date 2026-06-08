package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import com.hypervolt.conduit.tax.VatRemittanceService
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Performs the VAT remittance the API requested (doc 16 §1.3). The API can't touch TigerBeetle, so it emits
// tax.vat.remit_requested; this consumer (which owns the ledger) posts DR VAT:<entity> / CR BANK:<entity>, depleting
// the accrued exposure. The remittance id is derived from the request event id, so an at-least-once redelivery is a
// no-op. Own subscription on conduit.tax, Shared + Earliest, ack-good/nack-bad.
final class VatRemittanceConsumer[F[_]: Async](client: PulsarClient, service: VatRemittanceService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.tax"
  private val subscription = "conduit-vat-remitter-1"

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
    subscribe.use(c => logger.info(s"VAT remittance consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("VAT remittance failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def handle(env: EventEnvelope): F[Unit] =
    VatRemittanceConsumer.requested(env) match {
      case None    => Async[F].unit
      case Some(r) =>
        // deterministic remittance id from the request event id → at-least-once redelivery is a no-op
        val id = UUID.nameUUIDFromBytes(s"vat-remit:${env.event_id}".getBytes(StandardCharsets.UTF_8))
        service
          .remitWithId(id, r.entity, r.jurisdiction, r.period, r.amount, r.currency, r.reference, r.actor)
          .flatMap {
            case Right(_) => logger.info(s"remitted ${r.amount} ${r.currency} VAT for ${r.entity}/${r.jurisdiction}")
            case Left(m)  => Async[F].raiseError(new RuntimeException(s"VAT remittance failed: $m"))
          }
    }
}

final case class RemitRequest(
    entity: UUID,
    jurisdiction: String,
    period: String,
    amount: BigDecimal,
    currency: String,
    reference: Option[String],
    actor: String
)

object VatRemittanceConsumer {
  // Pure: tax.vat.remit_requested → the remittance params. Unit-testable without Pulsar.
  def requested(env: EventEnvelope): Option[RemitRequest] =
    if (env.event_type != "tax.vat.remit_requested") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption.flatMap { j =>
        val c = j.hcursor
        (
          c.get[String]("entity_id").toOption.flatMap(s => scala.util.Try(UUID.fromString(s)).toOption),
          c.get[String]("jurisdiction").toOption,
          c.get[String]("period_key").toOption,
          c.get[BigDecimal]("amount").toOption,
          c.get[String]("currency").toOption
        ).mapN { (e, jur, period, amount, ccy) =>
          RemitRequest(
            e,
            jur,
            period,
            amount,
            ccy,
            c.get[String]("reference").toOption,
            c.get[String]("actor").toOption.getOrElse("system")
          )
        }
      }
}
