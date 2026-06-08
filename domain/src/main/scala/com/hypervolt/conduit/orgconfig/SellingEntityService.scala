package com.hypervolt.conduit.orgconfig

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private final case class SeDraft(
    jurisdiction: String,
    effectiveFrom: LocalDate,
    status: String,
    proposedBy: Option[UUID]
)

// Governed entity-map changes (which entity books a jurisdiction is a financially material decision): propose a
// draft (maker), a different approver activates (checker — proposer ≠ approver). Activation closes the prior active
// row for the jurisdiction at the new from-date (effective-date supersession, never an edit) and emits an event.
final class SellingEntityService[F[_]: Async](xa: Transactor[F]) {

  def propose(jurisdiction: String, entityId: UUID, effectiveFrom: LocalDate, proposer: UUID): F[UUID] = {
    val id = UUID.randomUUID()
    sql"""INSERT INTO selling_entity (id, jurisdiction, entity_id, effective_from, status, proposed_by)
          VALUES ($id, $jurisdiction, $entityId, $effectiveFrom, 'draft', $proposer)""".update.run
      .transact(xa)
      .as(id)
  }

  def activate(id: UUID, approver: UUID): F[Either[String, Unit]] =
    loadDraft(id)
      .flatMap {
        case None                                       => leftC("selling-entity mapping not found")
        case Some(d) if d.status != "draft"             => leftC(s"mapping is ${d.status}, not draft")
        case Some(d) if d.proposedBy.contains(approver) => leftC("proposer cannot self-approve")
        case Some(d) =>
          (closePrior(id, d) *> markActive(id, approver) *> OutboxRepo.append(changedEvent(id, d, approver)))
            .as(().asRight[String])
      }
      .transact(xa)

  private def loadDraft(id: UUID): ConnectionIO[Option[SeDraft]] =
    sql"SELECT jurisdiction, effective_from, status, proposed_by FROM selling_entity WHERE id = $id"
      .query[SeDraft]
      .option

  private def closePrior(id: UUID, d: SeDraft): ConnectionIO[Int] =
    sql"""UPDATE selling_entity SET effective_to = ${d.effectiveFrom}, status = 'superseded', updated_at = now()
          WHERE id <> $id AND jurisdiction = ${d.jurisdiction} AND status = 'active'
            AND (effective_to IS NULL OR effective_to > ${d.effectiveFrom})""".update.run

  private def markActive(id: UUID, approver: UUID): ConnectionIO[Int] =
    sql"UPDATE selling_entity SET status = 'active', approved_by = $approver, updated_at = now() WHERE id = $id".update.run

  private def changedEvent(id: UUID, d: SeDraft, approver: UUID): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "org.selling_entity.changed",
      1,
      "selling_entity",
      id,
      d.jurisdiction,
      None,
      None,
      None,
      Json.obj(
        "selling_entity_id" -> id.toString.asJson,
        "jurisdiction"      -> d.jurisdiction.asJson,
        "effective_from"    -> d.effectiveFrom.toString.asJson,
        "approved_by"       -> approver.toString.asJson
      ),
      Instant.now(),
      "service:org"
    )

  private def leftC(msg: String): ConnectionIO[Either[String, Unit]] = msg.asLeft[Unit].pure[ConnectionIO]
}
