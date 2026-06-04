package com.hypervolt.conduit.migration

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import java.util.UUID

// The provenance spine (doc 18 §3.3). Pure ConnectionIO so a business-row write, the outbox append and the
// migration_record insert commit in ONE Postgres transaction (doc 18 §3.1) — that atomicity is what makes the
// backfill crash-safe and the provenance lossless.
object MigrationRepo {

  // Idempotency layer 1: has this exact source row already been migrated? (dedupe on (source, entity_type, source_id)).
  def existing(source: String, entityType: String, sourceId: String): ConnectionIO[Option[UUID]] =
    sql"""SELECT conduit_id FROM migration_record
          WHERE source = $source AND entity_type = $entityType AND source_id = $sourceId"""
      .query[UUID]
      .option

  def record(
      source: String,
      entityType: String,
      sourceId: String,
      conduitId: UUID,
      batchId: UUID,
      payload: Json,
      eventId: Option[UUID],
      phase: Int,
      caveats: List[String]
  ): ConnectionIO[Int] = {
    val hash = MigIds.sourceHash(payload.noSpaces)
    sql"""INSERT INTO migration_record
            (source, entity_type, source_id, conduit_id, batch_id, source_payload, source_hash, event_id, phase, caveats)
          VALUES ($source, $entityType, $sourceId, $conduitId, $batchId, $payload, $hash, $eventId, $phase, $caveats)""".update.run
  }

  // Stamp where this row posted money (re-performable: ledger figure -> transfer -> migration_record -> source row).
  def setTransfer(source: String, entityType: String, sourceId: String, transferId: BigInt): ConnectionIO[Int] =
    sql"""UPDATE migration_record SET tb_transfer_id = ${transferId.bigInteger.toString}::numeric
          WHERE source = $source AND entity_type = $entityType AND source_id = $sourceId""".update.run

  // Maker-checker reconciliation sign-off (doc 18 §6) — reconciled_by must differ from the row's loader actor.
  def markReconciled(batchId: UUID, reconciledBy: UUID): ConnectionIO[Int] =
    sql"""UPDATE migration_record SET status = 'reconciled', reconciled = true, reconciled_at = now(), reconciled_by = $reconciledBy
          WHERE batch_id = $batchId AND status = 'loaded'""".update.run

  def startBatch(label: String, source: String, startedBy: UUID): ConnectionIO[UUID] =
    sql"""INSERT INTO migration_batch (label, source, status, started_by)
          VALUES ($label, $source, 'running', $startedBy) RETURNING id""".query[UUID].unique

  def countByPhase(batchId: UUID): ConnectionIO[List[(Int, String, Long)]] =
    sql"""SELECT phase, status, count(*) FROM migration_record WHERE batch_id = $batchId
          GROUP BY phase, status ORDER BY phase""".query[(Int, String, Long)].to[List]

  // Drift detection (doc 18 §4.3): a source row edited after we migrated it. Re-hash and compare.
  def driftedSourceIds(
      source: String,
      entityType: String,
      currentHashes: List[(String, String)]
  ): ConnectionIO[List[String]] =
    currentHashes
      .traverse {
        case (sourceId, hash) =>
          sql"""SELECT source_id FROM migration_record
            WHERE source = $source AND entity_type = $entityType AND source_id = $sourceId AND source_hash <> $hash"""
            .query[String]
            .option
      }
      .map(_.flatten)
}
