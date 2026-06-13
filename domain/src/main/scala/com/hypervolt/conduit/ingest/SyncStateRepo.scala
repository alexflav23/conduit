package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._

// The sync cursor + run telemetry per (source, dataset) — spec doc 33 §3. The cursor advances only via
// recordSuccess (after a batch's writes commit); recordFailure leaves it untouched so the next run re-pulls.
final class SyncStateRepo[F[_]: Async](xa: Transactor[F]) {

  // The sync-health board (spec doc 33 §7): one row per (source, dataset) with cursor + lag + last status, for
  // the desk + the dual-run owners. lag_seconds = how stale the last successful run is (now - last_run_at).
  def all: F[List[Json]] =
    sql"""SELECT source, dataset, cursor, last_status,
            EXTRACT(EPOCH FROM (now() - last_run_at))::bigint, records_seen, records_written,
            consecutive_failures, last_error
          FROM sync_state ORDER BY source, dataset"""
      .query[(String, String, Option[String], Option[String], Option[Long], Long, Long, Int, Option[String])]
      .to[List]
      .map(_.map {
        case (src, ds, cur, st, lag, seen, written, fails, err) =>
          Json.obj(
            "source"               -> src.asJson,
            "dataset"              -> ds.asJson,
            "cursor"               -> cur.asJson,
            "last_status"          -> st.asJson,
            "lag_seconds"          -> lag.asJson,
            "records_seen"         -> seen.asJson,
            "records_written"      -> written.asJson,
            "consecutive_failures" -> fails.asJson,
            "last_error"           -> err.asJson
          )
      })
      .transact(xa)

  def cursor(source: String, dataset: String): F[Option[SyncCursor]] =
    sql"SELECT cursor FROM sync_state WHERE source = $source AND dataset = $dataset"
      .query[Option[String]]
      .option
      .map(_.flatten.map(SyncCursor.apply))
      .transact(xa)

  def recordSuccess(source: String, dataset: String, next: Option[SyncCursor], seen: Long, written: Long): F[Unit] =
    sql"""INSERT INTO sync_state (source, dataset, cursor, last_run_at, last_status, records_seen, records_written,
            consecutive_failures, last_error, updated_at)
          VALUES ($source, $dataset, ${next.map(_.value)}, now(), 'ok', $seen, $written, 0, NULL, now())
          ON CONFLICT (source, dataset) DO UPDATE SET
            cursor               = COALESCE(EXCLUDED.cursor, sync_state.cursor),
            last_run_at          = now(),
            last_status          = 'ok',
            records_seen         = sync_state.records_seen + $seen,
            records_written      = sync_state.records_written + $written,
            consecutive_failures = 0,
            last_error           = NULL,
            updated_at           = now()""".update.run.transact(xa).void

  def recordFailure(source: String, dataset: String, error: String): F[Unit] =
    sql"""INSERT INTO sync_state (source, dataset, last_run_at, last_status, consecutive_failures, last_error, updated_at)
          VALUES ($source, $dataset, now(), 'error', 1, $error, now())
          ON CONFLICT (source, dataset) DO UPDATE SET
            last_run_at          = now(),
            last_status          = 'error',
            consecutive_failures = sync_state.consecutive_failures + 1,
            last_error           = $error,
            updated_at           = now()""".update.run.transact(xa).void
}
