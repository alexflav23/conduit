package com.hypervolt.conduit.document

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

final case class AttachmentInput(
    direction: String,
    kind: String,
    subjectType: String,
    subjectId: UUID,
    filename: String,
    contentType: String,
    bytes: Array[Byte],
    externalRef: Option[String],
    source: String,
    uploadedBy: Option[UUID],
    dataLayer: Option[String],
    metadata: Json
)

// Associated / inbound documents (doc 25): store what Conduit RECEIVES — a customer PO, the signed supply agreement
// + schedules — on the same WORM store as generated documents, sha256 tamper-evidenced, ASSOCIATED with the subject
// it belongs to. Idempotent: re-uploading the same bytes to the same subject resolves to the existing attachment.
// Emits `document.attached` (no PII in the payload). The PO-vs-resolved reconciliation flags contract drift before
// fulfilment; nothing is silently accepted.
final class AttachmentService[F[_]: Async](xa: Transactor[F], storage: DocumentStorage[F]) {

  def store(in: AttachmentInput): F[UUID] = {
    val sha = sha256(in.bytes)
    AttachmentRepo.existing(in.subjectType, in.subjectId, sha).transact(xa).flatMap {
      case Some(id) => id.pure[F] // same bytes, same subject — idempotent re-upload
      case None =>
        storage
          .put(s"attachments/$sha/${in.filename}", in.bytes, in.contentType)
          .flatMap(uri =>
            (AttachmentRepo.insert(in, uri, sha, in.bytes.length.toLong) <* emitAttached(in)).transact(xa)
          )
    }
  }

  def download(id: UUID): F[Option[(String, String, Array[Byte])]] =
    AttachmentRepo.storageOf(id).transact(xa).flatMap {
      case None                               => none[(String, String, Array[Byte])].pure[F]
      case Some((uri, filename, contentType)) => storage.get(uri).map(bytes => (filename, contentType, bytes).some)
    }

  def listFor(subjectType: String, subjectId: UUID): F[List[Json]] =
    AttachmentRepo.listFor(subjectType, subjectId).transact(xa)

  // doc 25 §4.3 — compare the PO's stated total (metadata.po_total) with Conduit's RESOLVED order total. The order
  // prices from the authorized agreement, never from the PO; a mismatch is contract drift, flagged for review.
  def reconcilePo(attachmentId: UUID, orderId: UUID): F[Either[String, Json]] =
    (AttachmentRepo.poTotal(attachmentId), AttachmentRepo.orderTotal(orderId)).tupled.transact(xa).flatMap {
      case (None, _) => "attachment has no metadata.po_total to reconcile".asLeft[Json].pure[F]
      case (_, None) => "no such order".asLeft[Json].pure[F]
      case (Some(po), Some(resolved)) =>
        val drift  = po - resolved
        val status = if (drift.signum == 0) "match" else "drift"
        val result = Json.obj(
          "status"         -> status.asJson,
          "po_total"       -> po.toString.asJson,
          "resolved_total" -> resolved.toString.asJson,
          "drift"          -> drift.toString.asJson
        )
        AttachmentRepo.recordReconciliation(attachmentId, result).transact(xa).as(result.asRight[String])
    }

  private def emitAttached(in: AttachmentInput): ConnectionIO[Int] =
    OutboxRepo.append(
      OutboxEvent(
        UUID.randomUUID(),
        "document.attached",
        1,
        in.subjectType,
        in.subjectId,
        in.subjectId.toString,
        None,
        None,
        None,
        Json.obj(
          "subject_type" -> in.subjectType.asJson,
          "subject_id"   -> in.subjectId.toString.asJson,
          "kind"         -> in.kind.asJson,
          "external_ref" -> in.externalRef.asJson
        ),
        Instant.now(),
        in.uploadedBy.map(u => s"user:$u").getOrElse("service:documents")
      )
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
}

object AttachmentRepo {

  def existing(subjectType: String, subjectId: UUID, sha: String): ConnectionIO[Option[UUID]] =
    sql"""SELECT id FROM document_attachment
          WHERE subject_type = $subjectType AND subject_id = $subjectId AND content_sha256 = $sha"""
      .query[UUID]
      .option

  def insert(in: AttachmentInput, uri: String, sha: String, size: Long): ConnectionIO[UUID] =
    sql"""INSERT INTO document_attachment
            (direction, kind, subject_type, subject_id, filename, content_type, byte_size, storage_uri,
             content_sha256, external_ref, source, uploaded_by, data_layer, metadata)
          VALUES (${in.direction}, ${in.kind}, ${in.subjectType}, ${in.subjectId}, ${in.filename},
             ${in.contentType}, $size, $uri, $sha, ${in.externalRef}, ${in.source}, ${in.uploadedBy},
             ${in.dataLayer}, ${in.metadata})
          RETURNING id""".query[UUID].unique

  def storageOf(id: UUID): ConnectionIO[Option[(String, String, String)]] =
    sql"SELECT storage_uri, filename, content_type FROM document_attachment WHERE id = $id"
      .query[(String, String, String)]
      .option

  def listFor(subjectType: String, subjectId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT id, direction, kind, filename, content_type, byte_size, content_sha256, external_ref, source,
                 received_at, data_layer, metadata
          FROM document_attachment WHERE subject_type = $subjectType AND subject_id = $subjectId
          ORDER BY received_at DESC"""
      .query[
        (UUID, String, String, String, String, Long, String, Option[String], String, Instant, Option[String], Json)
      ]
      .to[List]
      .map(_.map {
        case (id, dir, kind, fn, ct, size, sha, ref, src, at, layer, meta) =>
          Json.obj(
            "id"             -> id.toString.asJson,
            "direction"      -> dir.asJson,
            "kind"           -> kind.asJson,
            "filename"       -> fn.asJson,
            "content_type"   -> ct.asJson,
            "byte_size"      -> size.asJson,
            "content_sha256" -> sha.asJson,
            "external_ref"   -> ref.asJson,
            "source"         -> src.asJson,
            "received_at"    -> at.toString.asJson,
            "data_layer"     -> layer.asJson,
            "metadata"       -> meta
          )
      })

  def poTotal(attachmentId: UUID): ConnectionIO[Option[BigDecimal]] =
    sql"SELECT (metadata->>'po_total')::numeric FROM document_attachment WHERE id = $attachmentId"
      .query[Option[BigDecimal]]
      .option
      .map(_.flatten)

  def orderTotal(orderId: UUID): ConnectionIO[Option[BigDecimal]] =
    sql"""SELECT total_inc_vat FROM "order" WHERE id = $orderId""".query[BigDecimal].option

  def recordReconciliation(attachmentId: UUID, result: Json): ConnectionIO[Int] =
    sql"""UPDATE document_attachment
          SET metadata = metadata || ${Json.obj("po_reconciliation" -> result)}
          WHERE id = $attachmentId""".update.run
}
