package com.hypervolt.conduit.migration

import com.hypervolt.conduit.ledger.TbIds
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

// Deterministic identities for the backfill (doc 18 §3.1). The SAME source row always maps to the SAME
// Conduit UUID, the SAME event_id and the SAME TigerBeetle transfer id — so re-running the backfill is a
// no-op at every layer (migration_record dedupe, consumer event_id dedupe, ledger transfer-exists). Ids are
// derived by name-UUID over a canonical key, never randomly, which is what makes a crashed batch resumable.
object MigIds {

  private def key(source: String, entityType: String, sourceId: String, suffix: String): String =
    s"$source|$entityType|$sourceId|$suffix"

  private def nameUuid(s: String): UUID =
    UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8))

  // The Conduit identity a source row collapses to (idempotency layer 3).
  def conduitId(source: String, entityType: String, sourceId: String): UUID =
    nameUuid(key(source, entityType, sourceId, "conduit"))

  // The emitted domain event's id (idempotency layer 2 — every consumer dedupes on event_id, doc 03 §3).
  def eventId(source: String, entityType: String, sourceId: String): UUID =
    nameUuid(key(source, entityType, sourceId, "evt"))

  // The opening transfer's TigerBeetle id (idempotency via transfer-exists, doc 04 §Ledger). `leg`
  // distinguishes multiple postings off one source row (e.g. INV + VAT clearing).
  def transferId(source: String, entityType: String, sourceId: String, leg: Int): BigInt =
    TbIds.transferId(nameUuid(key(source, entityType, sourceId, "transfer")), leg)

  // SHA-256 of the canonical source payload — detects source drift between a dry run and the real run
  // (a hazard with a live MRPeasy), forcing re-review rather than a silent re-import (doc 18 §3.3).
  def sourceHash(payload: String): String = {
    val d = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
    d.map(b => f"$b%02x").mkString
  }
}
