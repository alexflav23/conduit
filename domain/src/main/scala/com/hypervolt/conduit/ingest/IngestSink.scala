package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.security.MessageDigest

// The landing the IngestRunner writes each pulled record into (spec doc 33 §2) — the raw source-of-truth ledger
// `ingest_record`. Idempotent on (source, dataset, source_id): a re-pull of the same row is a no-op; a re-pull
// whose payload changed flips `drifted` (the spec/18 §4.3 post-ingest edit) and refreshes the row. This is the
// handler the runner calls; the DualRunReconciler aggregates the source side from here.
final class IngestSink[F[_]: Async](xa: Transactor[F]) {

  // The runner's write function: F[Either[String, Unit]] (Left would hold the cursor; the upsert only fails on a
  // genuine DB error, which surfaces as the runner's pull/write failure).
  def write(source: String)(record: IngestRecord): F[Either[String, Unit]] = {
    val canonical = record.payload.noSpaces
    val hash      = IngestSink.md5(canonical)
    sql"""INSERT INTO ingest_record (source, dataset, source_id, payload, source_hash)
          VALUES ($source, ${record.dataset}, ${record.sourceId}, $canonical::jsonb, $hash)
          ON CONFLICT (source, dataset, source_id) DO UPDATE SET
            drifted     = (ingest_record.source_hash <> EXCLUDED.source_hash),
            payload     = EXCLUDED.payload,
            source_hash = EXCLUDED.source_hash,
            last_seen   = now(),
            -- a drifted re-pull re-enters the inbox so the relay re-publishes and the consumer re-maps the new
            -- shape; an unchanged re-pull is left exactly as-is (no needless reprocessing). Never silently drop.
            status       = CASE WHEN ingest_record.source_hash <> EXCLUDED.source_hash THEN 'received' ELSE ingest_record.status END,
            published_at = CASE WHEN ingest_record.source_hash <> EXCLUDED.source_hash THEN NULL ELSE ingest_record.published_at END,
            processed_at = CASE WHEN ingest_record.source_hash <> EXCLUDED.source_hash THEN NULL ELSE ingest_record.processed_at END""".update.run
      .transact(xa)
      .attempt
      .map(_.leftMap(_.getMessage).map(_ => ()))
  }
}

object IngestSink {
  private[ingest] def md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
}
