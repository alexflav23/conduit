package com.hypervolt.conduit.forecast

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Read model for forecast-run tracking (doc 26 §7): the immutable, idempotent record of every forecast origin
// (policy_selection = the tournament's champion per account; forecast_run + model_accuracy = the basis it was
// chosen on). Surfaces the timeline, a comprehensive per-run report, and the raw selections a diff is computed
// over (RunDiff). Everything is reproducible from the stored facts — nothing is recomputed here.
object ForecastRunReportRepo {

  // The run timeline: one row per origin, with its headline stats + how many model runs fed it.
  def origins: ConnectionIO[List[Json]] =
    sql"""SELECT to_char(ps.origin_month, 'YYYY-MM') AS origin,
                 count(*)                              AS accounts,
                 COALESCE(sum(ps.forecast_qty), 0)     AS forecast_units,
                 COALESCE(sum(ps.actual_qty), 0)       AS actual_units,
                 round(abs(sum(ps.forecast_qty) - sum(ps.actual_qty))
                   / GREATEST(sum(ps.actual_qty), 1) * 100, 1) AS total_level_error_pct,
                 max(ps.selected_at)                   AS last_selected_at,
                 (SELECT count(*) FROM forecast_run fr WHERE fr.origin_month = ps.origin_month) AS model_runs
          FROM policy_selection ps
          GROUP BY ps.origin_month
          ORDER BY ps.origin_month DESC"""
      .query[(String, Int, BigDecimal, BigDecimal, BigDecimal, Instant, Int)]
      .to[List]
      .map(_.map {
        case (origin, accounts, fc, ac, err, at, runs) =>
          Json.obj(
            "origin"                -> origin.asJson,
            "accounts"              -> accounts.asJson,
            "forecast_units"        -> fc.asJson,
            "actual_units"          -> ac.asJson,
            "total_level_error_pct" -> err.asJson,
            "last_selected_at"      -> at.toString.asJson,
            "model_runs"            -> runs.asJson
          )
      })

  // The champion selection rows for one origin — the substrate RunDiff.stats / RunDiff.diff operate on.
  def selections(origin: LocalDate): ConnectionIO[List[RunDiff.SelRow]] =
    sql"""SELECT company_id, policy_key, forecast_qty, actual_qty
          FROM policy_selection WHERE origin_month = $origin"""
      .query[(UUID, String, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map { case (c, k, f, a) => RunDiff.SelRow(c, k, f, a) })

  // Per-segment outturn for an origin (the live channel_comparables view).
  def segments(origin: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT segment, accounts, forecast_units, actual_units,
                 round(total_level_error * 100, 1)
          FROM channel_comparables WHERE origin_month = $origin ORDER BY segment"""
      .query[(String, Int, BigDecimal, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map {
        case (seg, n, fc, ac, err) =>
          Json.obj(
            "segment"               -> seg.asJson,
            "accounts"              -> n.asJson,
            "forecast_units"        -> fc.asJson,
            "actual_units"          -> ac.asJson,
            "total_level_error_pct" -> err.asJson
          )
      })

  // The model runs that fed an origin — the provenance/basis (model, version, the pinning data SHA + params).
  def provenance(origin: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT model_key, model_version, purpose, COALESCE(data_sha, ''), COALESCE(params_hash, ''), created_at
          FROM forecast_run WHERE origin_month = $origin ORDER BY created_at, model_key"""
      .query[(String, Int, String, String, String, Instant)]
      .to[List]
      .map(_.map {
        case (key, ver, purpose, sha, params, at) =>
          Json.obj(
            "model_key"     -> key.asJson,
            "model_version" -> ver.asJson,
            "purpose"       -> purpose.asJson,
            "data_sha"      -> (if (sha.isEmpty) Json.Null else sha.asJson),
            "params_hash"   -> (if (params.isEmpty) Json.Null else params.asJson),
            "created_at"    -> at.toString.asJson
          )
      })

  // The scored error per model at an origin — WHY a champion was chosen (lowest mean abs error wins the bake-off).
  def modelAccuracy(origin: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT model_key, count(*) AS scored,
                 round(avg(abs_error), 2) AS mean_abs_error,
                 round(sum(abs_error), 2) AS total_abs_error
          FROM model_accuracy WHERE origin_month = $origin
          GROUP BY model_key ORDER BY avg(abs_error)"""
      .query[(String, Int, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map {
        case (key, scored, mean, tot) =>
          Json.obj(
            "model_key"       -> key.asJson,
            "scored"          -> scored.asJson,
            "mean_abs_error"  -> mean.asJson,
            "total_abs_error" -> tot.asJson,
            "structural"      -> RunDiff.isStructural(key).asJson
          )
      })
}
