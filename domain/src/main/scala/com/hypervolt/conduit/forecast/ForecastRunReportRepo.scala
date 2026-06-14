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

  // The dimensions a delta can browse by, mapped to their (group, label) SQL — whitelisted, never user strings.
  private def axisFrag(axis: String): (Fragment, Fragment) =
    axis match {
      case "market"  => (fr"p.market_id", fr"COALESCE(m.name, p.market_id::text, '(none)')")
      case "channel" => (fr"p.channel_id", fr"COALESCE(p.channel_id::text, '(none)')")
      case _         => (fr"p.segment", fr"COALESCE(p.segment, '(none)')")
    }

  // The browsable run-to-run delta along one axis (segment | channel | market), optionally filtered to one
  // market — so "channel by channel for each market" is group=channel + a market filter. Each cell carries the
  // from/to outturn; RunDiff turns the sums into the total-level error + deltas (the single source of truth).
  def dimensionDelta(
      from: LocalDate,
      to: LocalDate,
      axis: String,
      marketFilter: Option[UUID]
  ): ConnectionIO[List[Json]] = {
    val (grp, label) = axisFrag(axis)
    val marketWhere  = marketFilter.fold(Fragment.empty)(m => fr"AND p.market_id = $m")
    (fr"SELECT " ++ label ++ fr""" AS cell,
            count(*) FILTER (WHERE ps.origin_month = $from),
            COALESCE(sum(ps.forecast_qty) FILTER (WHERE ps.origin_month = $from), 0),
            COALESCE(sum(ps.actual_qty)   FILTER (WHERE ps.origin_month = $from), 0),
            count(*) FILTER (WHERE ps.origin_month = $to),
            COALESCE(sum(ps.forecast_qty) FILTER (WHERE ps.origin_month = $to), 0),
            COALESCE(sum(ps.actual_qty)   FILTER (WHERE ps.origin_month = $to), 0)
          FROM policy_selection ps
          JOIN party p ON p.id = ps.company_id
          LEFT JOIN market m ON m.id = p.market_id
          WHERE ps.origin_month IN ($from, $to) """ ++ marketWhere ++ fr" GROUP BY " ++ grp ++ fr", " ++ label)
      .query[(String, Int, BigDecimal, BigDecimal, Int, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map {
        case (cell, fAcc, fFc, fAc, tAcc, tFc, tAc) =>
          val fErr = RunDiff.totalLevelErrorPct(fFc, fAc)
          val tErr = RunDiff.totalLevelErrorPct(tFc, tAc)
          Json.obj(
            "cell"            -> cell.asJson,
            "from_accounts"   -> fAcc.asJson,
            "to_accounts"     -> tAcc.asJson,
            "from_error_pct"  -> fErr.asJson,
            "to_error_pct"    -> tErr.asJson,
            "error_delta_pct" -> (tErr - fErr).asJson,
            "forecast_delta"  -> (tFc - fFc).asJson,
            "actual_delta"    -> (tAc - fAc).asJson,
            "to_forecast"     -> tFc.asJson,
            "to_actual"       -> tAc.asJson
          )
      })
      .map(_.sortBy(j => -j.hcursor.get[BigDecimal]("error_delta_pct").getOrElse(BigDecimal(0)).abs))
  }

  // The markets present across two origins — populates the delta's market filter.
  def marketsFor(from: LocalDate, to: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT DISTINCT p.market_id, COALESCE(m.name, p.market_id::text)
          FROM policy_selection ps JOIN party p ON p.id = ps.company_id
          LEFT JOIN market m ON m.id = p.market_id
          WHERE ps.origin_month IN ($from, $to) AND p.market_id IS NOT NULL
          ORDER BY 2"""
      .query[(UUID, String)]
      .to[List]
      .map(_.map { case (id, name) => Json.obj("id" -> id.toString.asJson, "name" -> name.asJson) })
}
