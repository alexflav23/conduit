package com.hypervolt.conduit.ingest

import io.circe.Json

// The single source abstraction (spec doc 33 §2). Every source — pull (Xero/HubSpot/MRPeasy/Athena) or push
// (Stripe/webhooks, which normalise to the same IngestRecord) — speaks this shape, so a webhook is a latency
// optimisation over the poll, never a second code path.

// An opaque resume point: a source timestamp, last id, or page token. The runner never interprets it.
final case class SyncCursor(value: String)

// One pulled record, before normalisation. `sourceId` is the natural key the dedupe ledger (migration_record)
// keys on; `payload` is the raw source row, retained for re-performance (doc 14 §5).
final case class IngestRecord(dataset: String, sourceId: String, payload: Json)

// A batch from one pullSince: the records, the cursor to resume from next time (None = leave the cursor where it
// is), and whether the source is fully drained (false ⇒ more pages remain; the scheduler pulls again promptly).
final case class IngestBatch(records: List[IngestRecord], nextCursor: Option[SyncCursor], complete: Boolean)

object IngestBatch {
  val empty: IngestBatch = IngestBatch(Nil, None, complete = true)
}

trait IngestConnector[F[_]] {
  def source: String
  def datasets: List[String]
  // Pull everything after `cursor` (None = from the beginning — the historical backfill). At-least-once: the
  // runner only advances the cursor after the batch's writes commit, so redelivery is expected and handlers
  // dedupe on sourceId.
  def pullSince(dataset: String, cursor: Option[SyncCursor]): F[IngestBatch]
}
