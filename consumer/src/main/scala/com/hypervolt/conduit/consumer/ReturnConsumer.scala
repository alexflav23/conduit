package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import com.hypervolt.conduit.returns.ReturnService
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Performs the money-posting RMA transitions the API requested (doc 09). The API can't touch TigerBeetle, so it
// emits return.disposition_requested / return.refund_requested; this consumer (which owns the ledger) runs
// ReturnService.disposition/refund — the inventory reversal at batch cost, the credit note + AR/VAT reversal,
// and the commission claw. Own subscription on conduit.returns, Shared + Earliest, ack-good/nack-bad.
// ReturnService is idempotent on the deterministic transfer ids, so redelivery is a no-op.
final class ReturnConsumer[F[_]: Async](client: PulsarClient, svc: ReturnService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.returns"
  private val subscription = "conduit-return-effector-1"

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
    subscribe.use(c => logger.info(s"Return consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("return effect failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  private def fail(rmaId: UUID, what: String): Either[String, Unit] => F[Unit] = {
    case Right(_) => logger.info(s"$what effected for rma $rmaId")
    case Left(m)  => Async[F].raiseError(new RuntimeException(s"$what failed for rma $rmaId: $m"))
  }

  private def handle(env: EventEnvelope): F[Unit] =
    ReturnConsumer.dispositionRequested(env) match {
      case Some((rmaId, lineId, choice, loc, actor)) =>
        svc.disposition(rmaId, lineId, choice, loc, actor).flatMap(fail(rmaId, s"disposition:$choice"))
      case None =>
        ReturnConsumer.refundRequested(env) match {
          case Some((rmaId, method, _)) => svc.refund(rmaId, method).flatMap(fail(rmaId, s"refund:$method"))
          case None                     => Async[F].unit // not a return command
        }
    }
}

object ReturnConsumer {
  private def uuid(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption
  private def payloadOf(env: EventEnvelope) = parse(new String(env.payload, StandardCharsets.UTF_8)).toOption

  // Pure: return.disposition_requested → (rmaId, rmaLineId, disposition, locationId?, actor). No Pulsar needed.
  def dispositionRequested(env: EventEnvelope): Option[(UUID, UUID, String, Option[UUID], UUID)] =
    if (env.event_type != "return.disposition_requested") None
    else
      (uuid(env.aggregate_id), payloadOf(env)).mapN { (rmaId, j) =>
        val c = j.hcursor
        (
          c.get[String]("rma_line_id").toOption.flatMap(uuid),
          c.get[String]("disposition").toOption,
          c.get[String]("actor").toOption.flatMap(uuid)
        ).mapN { (lineId, choice, actor) =>
          (rmaId, lineId, choice, c.get[String]("location_id").toOption.flatMap(uuid), actor)
        }
      }.flatten

  // Pure: return.refund_requested → (rmaId, refundMethod, actor).
  def refundRequested(env: EventEnvelope): Option[(UUID, String, UUID)] =
    if (env.event_type != "return.refund_requested") None
    else
      (uuid(env.aggregate_id), payloadOf(env)).mapN { (rmaId, j) =>
        val c = j.hcursor
        (c.get[String]("refund_method").toOption, c.get[String]("actor").toOption.flatMap(uuid)).mapN {
          (method, actor) => (rmaId, method, actor)
        }
      }.flatten
}
