package com.hypervolt.conduit.document

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

final case class AllocatedNumber(numberId: UUID, seq: Long, formatted: String)

// Gapless, immutable, audit-grade numbering (doc 17 §3). The series row is locked FOR UPDATE so concurrent
// finalisers never share or skip a number; the allocation is recorded in document_number (append-only). A void
// keeps its seq (consumed, never reused) — so the sequence is contiguous 1..current_seq with no holes.
object DocumentNumberAllocator {

  // Allocate the next number for (entity, document_type, jurisdiction). Runs inside the finalise transaction.
  def allocate(
      entity: UUID,
      documentType: String,
      jurisdiction: String,
      year: Int
  ): ConnectionIO[Option[AllocatedNumber]] =
    sql"""SELECT id, series_code, format, current_seq FROM document_number_series
          WHERE entity_id = $entity AND document_type = $documentType
            AND (jurisdiction = $jurisdiction OR jurisdiction IS NULL) AND status = 'active'
          ORDER BY (jurisdiction IS NOT NULL) DESC LIMIT 1
          FOR UPDATE"""
      .query[(UUID, String, String, Long)]
      .option
      .flatMap {
        case None => Option.empty[AllocatedNumber].pure[ConnectionIO]
        case Some((seriesId, code, format, cur)) =>
          val seq       = cur + 1
          val formatted = applyFormat(format, code, seq, year)
          for {
            _  <- sql"UPDATE document_number_series SET current_seq = $seq WHERE id = $seriesId".update.run
            id <- sql"""INSERT INTO document_number (series_id, seq, formatted_number, status)
                        VALUES ($seriesId, $seq, $formatted, 'allocated') RETURNING id""".query[UUID].unique
          } yield Some(AllocatedNumber(id, seq, formatted))
      }

  def markIssued(numberId: UUID, documentId: UUID): ConnectionIO[Int] =
    sql"UPDATE document_number SET status = 'issued', document_id = $documentId WHERE id = $numberId".update.run

  // Void consumes the number (no reuse, no gap): the seq stays, only the status flips.
  def void(numberId: UUID, reason: String): ConnectionIO[Int] =
    sql"UPDATE document_number SET status = 'voided', voided_reason = $reason WHERE id = $numberId".update.run

  // {series}-{yyyy}-{seq:06d} → HV-UK-INV-2026-000001. The format is data (a different legal scheme is config).
  def applyFormat(format: String, series: String, seq: Long, year: Int): String =
    """\{seq:0(\d+)d\}""".r
      .replaceAllIn(
        format.replace("{series}", series).replace("{yyyy}", year.toString),
        m => ("%0" + m.group(1) + "d").format(seq)
      )
      .replace("{seq}", seq.toString)
}
