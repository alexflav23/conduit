package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.forecast.RunwayService
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import com.hypervolt.conduit.warranty.ActivationOutcome
import com.hypervolt.conduit.warranty.ActivationService
import com.sksamuel.avro4s.Decoder
import com.sksamuel.avro4s.Encoder
import com.sksamuel.avro4s.SchemaFor
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.util.Try

// The known UFE record on `athena-placement-versioned` (CLAUDE.md §3) — every activation is a real charger going
// live. Conduit subscribes with its OWN subscription (conduit-placement-versioned-subscription-1).
final case class AthenaPlacementVersionedRecord(device: Option[String], placementId: String, version: Int)
object AthenaPlacementVersionedRecord {
  implicit val schemaFor: SchemaFor[AthenaPlacementVersionedRecord] = SchemaFor.gen[AthenaPlacementVersionedRecord]
  implicit val encoder: Encoder[AthenaPlacementVersionedRecord]     = Encoder.gen[AthenaPlacementVersionedRecord]
  implicit val decoder: Decoder[AthenaPlacementVersionedRecord]     = Decoder.gen[AthenaPlacementVersionedRecord]
}

// M8's missing wire + doc 26 §6's streaming layer in one consumer: each placement event runs the existing
// idempotent ActivationService (first-write-wins, v3-only, warranty clock) and then refreshes the account's
// runway projection (shelf − 1, velocity, runway; emits forecast.account.runway at the reorder point). Both legs
// are idempotent/recomputed-from-the-log, so at-least-once redelivery is a no-op.
final class PlacementConsumer[F[_]: Async](
    client: PulsarClient,
    xa: Transactor[F],
    activations: ActivationService[F],
    runway: RunwayService[F]
) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "athena-placement-versioned"
  private val subscription = "conduit-placement-versioned-subscription-1"

  private def subscribe: Resource[F, Consumer[AthenaPlacementVersionedRecord]] =
    Resource.fromAutoCloseable(
      Async[F].blocking(
        client
          .newConsumer(AvroPulsarSchema.avroSchema[AthenaPlacementVersionedRecord])
          .topic(topic)
          .subscriptionName(subscription)
          .subscriptionType(SubscriptionType.Shared)
          .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
          .subscribe()
      )
    )

  def runForever: F[Unit] =
    subscribe.use(c => logger.info(s"Placement consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[AthenaPlacementVersionedRecord]): F[Unit] =
    Async[F]
      .fromCompletableFuture(Async[F].delay(c.receiveAsync()))
      .flatMap(msg =>
        handle(msg.getValue)
          .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
          .handleErrorWith(t =>
            logger.error(t)("placement handling failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
          )
      )

  def handle(rec: AthenaPlacementVersionedRecord): F[Unit] =
    PlacementConsumer.activation(rec) match {
      case None => Async[F].unit
      case Some((serial, placementId)) =>
        Async[F].realTimeInstant.flatMap { now =>
          activations.onActivation(serial, placementId, rec.version, now, None).flatMap {
            case ActivationOutcome.Activated => refreshRunway(serial, now)
            case _                           => Async[F].unit // already-activated / v2 / unknown: nothing moved
          }
        }
    }

  private def refreshRunway(serial: String, now: Instant): F[Unit] =
    sql"SELECT company_id, product_variant_id FROM serial_unit WHERE serial_no = $serial"
      .query[(Option[UUID], UUID)]
      .option
      .transact(xa)
      .flatMap {
        case Some((Some(company), variant)) => runway.refresh(company, variant, now).void
        case _                              => Async[F].unit // unattributed serials have no account runway
      }
}

object PlacementConsumer {
  // Pure: a placement record → (serial, placement UUID). Unit-testable without Pulsar.
  def activation(rec: AthenaPlacementVersionedRecord): Option[(String, UUID)] =
    (rec.device.filter(_.nonEmpty), Try(UUID.fromString(rec.placementId)).toOption).tupled
}
