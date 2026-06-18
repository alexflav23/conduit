package com.hypervolt.conduit.ingest

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json

// The inbox side of the durable landing (S1) — the queries the InboundRelay and the InboundMappingConsumer run
// against `ingest_record`. Mirror of OutboxRepo: fetch the 'received' backlog in seq order, mark 'published'
// after transport, mark 'processed' after the mapping commits, mark 'failed' (quarantine, error retained) when
// a row can't be mapped. Idempotent on the inbox key (source, dataset, source_id).
final case class InboundRow(source: String, dataset: String, sourceId: String, sourceHash: String, payload: Json)

final class InboxRepo[F[_]: Async](xa: Transactor[F]) {

  def fetchReceived(limit: Int): F[List[InboundRow]] =
    sql"""SELECT source, dataset, source_id, source_hash, payload
          FROM ingest_record WHERE status = 'received' ORDER BY seq ASC LIMIT $limit"""
      .query[(String, String, String, String, Json)]
      .to[List]
      .map(_.map { case (s, d, id, h, p) => InboundRow(s, d, id, h, p) })
      .transact(xa)

  def markPublished(keys: NonEmptyList[(String, String, String)]): F[Int] =
    keys.toList
      .traverse(k =>
        sql"""UPDATE ingest_record SET status = 'published', published_at = now()
              WHERE source = ${k._1} AND dataset = ${k._2} AND source_id = ${k._3} AND status = 'received'""".update.run
      )
      .map(_.sum)
      .transact(xa)

  // The mapping consumer commits the handler write AND the status flip in one tx (the write itself is a
  // ConnectionIO the consumer threads in), so a row is never marked processed without its mapping landing.
  def markProcessed(source: String, dataset: String, sourceId: String): ConnectionIO[Int] =
    sql"""UPDATE ingest_record SET status = 'processed', processed_at = now(), attempts = attempts + 1, last_error = NULL
          WHERE source = $source AND dataset = $dataset AND source_id = $sourceId""".update.run

  def markFailed(source: String, dataset: String, sourceId: String, error: String): F[Int] =
    sql"""UPDATE ingest_record SET status = 'failed', attempts = attempts + 1, last_error = $error
          WHERE source = $source AND dataset = $dataset AND source_id = $sourceId""".update.run.transact(xa)

  // The quarantine desk view (spec doc 33 §7): rows that failed to map, raw payload + error retained, never lost.
  def quarantine(limit: Int, offset: Int): F[List[Json]] =
    sql"""SELECT source, dataset, source_id, attempts, last_error, payload, first_seen, last_seen
          FROM ingest_record WHERE status = 'failed' ORDER BY last_seen DESC LIMIT $limit OFFSET $offset"""
      .query[(String, String, String, Int, Option[String], Json, java.time.Instant, java.time.Instant)]
      .to[List]
      .map(_.map {
        case (s, d, id, att, err, p, first, last) =>
          Json.obj(
            "source"     -> Json.fromString(s),
            "dataset"    -> Json.fromString(d),
            "source_id"  -> Json.fromString(id),
            "attempts"   -> Json.fromInt(att),
            "last_error" -> err.fold(Json.Null)(Json.fromString),
            "payload"    -> p,
            "first_seen" -> Json.fromString(first.toString),
            "last_seen"  -> Json.fromString(last.toString)
          )
      })
      .transact(xa)

  // Operator action: re-queue a quarantined (or any) row for another pass once the mapping bug is fixed.
  def requeue(source: String, dataset: String, sourceId: String): F[Int] =
    sql"""UPDATE ingest_record SET status = 'received', published_at = NULL, processed_at = NULL, last_error = NULL
          WHERE source = $source AND dataset = $dataset AND source_id = $sourceId""".update.run.transact(xa)

  // Inbox health counts for the desk board.
  def statusCounts: F[List[Json]] =
    sql"""SELECT source, status, count(*) FROM ingest_record GROUP BY source, status ORDER BY source, status"""
      .query[(String, String, Long)]
      .to[List]
      .map(_.map {
        case (s, st, n) =>
          Json.obj("source" -> Json.fromString(s), "status" -> Json.fromString(st), "count" -> Json.fromLong(n))
      })
      .transact(xa)
}
