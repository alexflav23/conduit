package com.hypervolt.conduit.privacy

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
import java.util.UUID

// The governed DSAR workflow (doc 19 §B.3.3). A right-to-erasure request is maker-checker: the requester logs it, an
// authorised Data-Protection approver decides — requester ≠ approver. Approval performs the crypto-shred and emits
// `pii.shredded` (carrying ONLY the subject id + the fact of erasure — never PII), which projection builders and
// external adapters consume to replace the subject's PII with the `«erased»` tombstone and issue downstream deletes.
final class DsarService[F[_]: Async](xa: Transactor[F], vault: PiiVault[F]) {

  def requestErasure(subject: UUID, reason: String, requestedBy: UUID): F[UUID] =
    sql"""INSERT INTO dsar_request (subject_id, kind, status, reason, requested_by)
          VALUES ($subject, 'erasure', 'pending', $reason, $requestedBy) RETURNING id""".query[UUID].unique.transact(xa)

  def approveErasure(id: UUID, approver: UUID): F[Either[String, Unit]] =
    load(id).transact(xa).flatMap {
      case None                                           => "no such DSAR request".asLeft[Unit].pure[F]
      case Some((_, _, status, _)) if status != "pending" => s"request is $status, not pending".asLeft[Unit].pure[F]
      case Some((_, _, _, Some(requester))) if requester == approver =>
        "the requester cannot approve their own erasure (segregation of duties)".asLeft[Unit].pure[F]
      case Some((subject, _, _, _)) =>
        vault.shred(subject, approver) *> complete(id, approver, subject).transact(xa).as(().asRight[String])
    }

  private def load(id: UUID): ConnectionIO[Option[(UUID, String, String, Option[UUID])]] =
    sql"SELECT subject_id, kind, status, requested_by FROM dsar_request WHERE id=$id"
      .query[(UUID, String, String, Option[UUID])]
      .option

  private def complete(id: UUID, approver: UUID, subject: UUID): ConnectionIO[Int] =
    sql"UPDATE dsar_request SET status='completed', approved_by=$approver, decided_at=now() WHERE id=$id".update.run
      .flatMap(n =>
        OutboxRepo
          .append(
            OutboxEvent(
              UUID.randomUUID(),
              "pii.shredded",
              1,
              "party",
              subject,
              subject.toString,
              None,
              None,
              None,
              Json.obj("subject_id" -> subject.toString.asJson, "erased" -> true.asJson), // NO PII — only the fact
              Instant.now(),
              "service:dsar"
            )
          )
          .as(n)
      )
}
