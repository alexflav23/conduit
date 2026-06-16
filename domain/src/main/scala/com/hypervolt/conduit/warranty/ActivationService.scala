package com.hypervolt.conduit.warranty

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

sealed abstract class ActivationOutcome(val name: String)
object ActivationOutcome {
  case object Activated        extends ActivationOutcome("activated")
  case object AlreadyActivated extends ActivationOutcome("already_activated")
  case object IgnoredV2        extends ActivationOutcome("ignored_v2")
  case object NoSerialUnit     extends ActivationOutcome("no_serial_unit")
}

private final case class SerialRow(
    id: UUID,
    generation: String,
    familyId: UUID,
    lotBatchId: Option[UUID],
    entityId: Option[UUID]
)

// Activation ingest (doc 04 §Serial): idempotent, first-write-wins per serial; V2 ignored; the warranty
// clock starts at activation and a provision opens at the unit's specific batch cost. The Pulsar consumer
// on `athena-placement-versioned` calls onActivation; replaying the topic is safe (first-write-wins).
final class ActivationService[F[_]: Async](xa: Transactor[F]) {

  def onActivation(
      serialNo: String,
      placementId: UUID,
      version: Int,
      activatedAt: Instant,
      companyId: Option[UUID]
  ): F[ActivationOutcome] =
    lookupSerial(serialNo)
      .flatMap {
        case None                              => (ActivationOutcome.NoSerialUnit: ActivationOutcome).pure[ConnectionIO]
        case Some(su) if su.generation != "v3" => (ActivationOutcome.IgnoredV2: ActivationOutcome).pure[ConnectionIO]
        case Some(su) =>
          insertActivationFirstWriteWins(serialNo, placementId, version, activatedAt).flatMap { firstTime =>
            if (!firstTime) (ActivationOutcome.AlreadyActivated: ActivationOutcome).pure[ConnectionIO]
            else activate(su, serialNo, placementId, version, activatedAt, companyId)
          }
      }
      .transact(xa)

  private def activate(
      su: SerialRow,
      serialNo: String,
      placementId: UUID,
      version: Int,
      activatedAt: Instant,
      companyId: Option[UUID]
  ): ConnectionIO[ActivationOutcome] = {
    val start = activatedAt.atZone(ZoneOffset.UTC).toLocalDate
    for {
      months <- WarrantyProvisioning.legalMonths(su.familyId)
      extra  <- WarrantyProvisioning.extensionMonths(su.id)
      fresh = start.plusMonths((months + extra).toLong)
      // A replacement unit inherits the original's warranty_end — the clock never resets (the original is already
      // root-propagated, so its end IS the root's). A first-life unit gets the freshly-computed term.
      inherited <-
        sql"SELECT o.warranty_end FROM serial_unit r JOIN serial_unit o ON o.id = r.replaces_serial_unit_id WHERE r.id = ${su.id}"
          .query[LocalDate]
          .option
      end = inherited.getOrElse(fresh)
      _ <- sql"""UPDATE serial_unit SET status = 'activated', company_id = COALESCE($companyId, company_id),
                activated_at = $activatedAt, warranty_end = $end WHERE id = ${su.id}""".update.run
      _ <- WarrantyProvisioning.open(su.id, su.entityId, su.lotBatchId, su.familyId, start, end)
      _ <- OutboxRepo.append(
        event(
          serialNo,
          "activation.recorded",
          Json.obj(
            "placement_id" -> placementId.toString.asJson,
            "version"      -> version.asJson,
            "is_first"     -> true.asJson
          )
        )
      )
      _ <- OutboxRepo.append(
        event(
          serialNo,
          "warranty.provision.accrued",
          Json.obj("warranty_start" -> start.toString.asJson, "warranty_end" -> end.toString.asJson)
        )
      )
    } yield ActivationOutcome.Activated
  }

  private def lookupSerial(serialNo: String): ConnectionIO[Option[SerialRow]] =
    sql"""SELECT s.id, s.generation, pv.family_id, s.lot_batch_id, s.entity_id
          FROM serial_unit s JOIN product_variant pv ON pv.id = s.product_variant_id
          WHERE s.serial_no = $serialNo"""
      .query[(UUID, String, UUID, Option[UUID], Option[UUID])]
      .option
      .map(_.map { case (id, gen, fam, batch, ent) => SerialRow(id, gen, fam, batch, ent) })

  private def insertActivationFirstWriteWins(
      serialNo: String,
      placementId: UUID,
      version: Int,
      activatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""INSERT INTO activation (serial, placement_id, placement_version, activated_at)
          VALUES ($serialNo, $placementId, $version, $activatedAt)
          ON CONFLICT (serial) DO NOTHING""".update.run.map(_ == 1)

  private def event(serial: String, eventType: String, payload: Json): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      eventType,
      1,
      "serial",
      UUID.randomUUID(),
      serial,
      None,
      None,
      None,
      payload,
      Instant.now()
    )
}
