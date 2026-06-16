package com.hypervolt.conduit.supply

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import scala.math.BigDecimal.RoundingMode.HALF_UP

// Capacity-connected view (doc 08 fleet analytics): how much EV-charging capacity Hypervolt is actually bringing
// online over time. Every activated charger is a UK domestic single-phase 32 A install = 7.4 kW connected — the
// honest per-unit constant for the Home 3 / Home 3 Pro range (no three-phase units in the activated register), not
// a fabricated figure. The headline metric is the smoothed daily run-rate: a trailing mean of daily MW over the
// last `smooth` calendar days, averaged over WORKING DAYS only (weekends excluded — installs are a weekday
// activity, and weekend near-zeros otherwise drag the mean down ~⅓), alongside the cumulative MW online (the whole
// fleet, counting every activation regardless of weekday, seeded with the activations that predate the window).
object ActivationCapacityRepo {

  // kW per activated charger: single-phase 32 A domestic install. Documented constant — every activated SKU in the
  // serial register is a Hypervolt domestic charger.
  private val KwPerUnit = BigDecimal("7.4")

  private def baseline(months: Int): ConnectionIO[Long] =
    sql"""SELECT count(*)::bigint
          FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
          WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL
            AND (s.activated_at AT TIME ZONE 'UTC')::date < (CURRENT_DATE - make_interval(months => $months))::date"""
      .query[Long]
      .unique

  // End the series at the data frontier (the last ingested activation), not CURRENT_DATE — the placement feed lags a
  // few days, and charting un-ingested empty days would drag the trailing mean into a false cliff.
  private def dailySeries(months: Int): ConnectionIO[List[(LocalDate, Long)]] =
    sql"""WITH frontier AS (
            SELECT max((s.activated_at AT TIME ZONE 'UTC')::date) AS f
            FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
            WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL
          ),
          cal AS (
            SELECT generate_series((CURRENT_DATE - make_interval(months => $months))::date, (SELECT f FROM frontier), '1 day')::date AS d
          ),
          daily AS (
            SELECT (s.activated_at AT TIME ZONE 'UTC')::date AS d, count(*)::bigint AS u
            FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
            WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL
            GROUP BY 1
          )
          SELECT c.d, COALESCE(daily.u, 0)::bigint
          FROM cal c LEFT JOIN daily ON daily.d = c.d
          ORDER BY c.d"""
      .query[(LocalDate, Long)]
      .to[List]

  private def mw(units: BigDecimal): Double = (units * KwPerUnit / 1000).setScale(3, HALF_UP).toDouble

  private def isWeekday(d: LocalDate): Boolean = d.getDayOfWeek.getValue <= 5 // Mon..Fri = 1..5

  def capacity(windowMonths: Int, smoothingDays: Int): ConnectionIO[Json] = {
    val months = windowMonths.max(1).min(60)
    val smooth = smoothingDays.max(1).min(120)
    baseline(months).flatMap(base0 =>
      dailySeries(months).map { rows =>
        val units  = rows.map(_._2).toVector
        val prefix = units.scanLeft(0L)(_ + _) // prefix(i) = Σ first i days — drives cumulative MW (all days)
        // Weekends drag the run-rate down — installs are a working-day activity — so the trailing mean is computed
        // over BUSINESS DAYS only: weekend units are excluded from both the sum and the day count. Cumulative MW
        // still counts every activation, whatever weekday it fell on.
        val bizUnitPre  = rows.map { case (d, u) => if (isWeekday(d)) u else 0L }.scanLeft(0L)(_ + _).toVector
        val bizCountPre = rows.map { case (d, _) => if (isWeekday(d)) 1L else 0L }.scanLeft(0L)(_ + _).toVector
        val trailingMean: Int => BigDecimal = { i =>
          val lo = math.max(0, i + 1 - smooth)
          val ct = bizCountPre(i + 1) - bizCountPre(lo)
          if (ct == 0L) BigDecimal(0) else BigDecimal(bizUnitPre(i + 1) - bizUnitPre(lo)) / ct
        }
        val points = rows.zipWithIndex.map {
          case ((d, u), i) =>
            Json.obj(
              "date"         -> d.toString.asJson,
              "daily_units"  -> u.asJson,
              "daily_mw"     -> mw(BigDecimal(u)).asJson,
              "avg_daily_mw" -> mw(trailingMean(i)).asJson,
              "cumulative_mw" -> (BigDecimal(base0 + prefix(i + 1)) * KwPerUnit / 1000)
                .setScale(1, HALF_UP)
                .toDouble
                .asJson
            )
        }
        val lastMean   = if (units.isEmpty) BigDecimal(0) else trailingMean(units.size - 1)
        val totalUnits = base0 + units.sum
        Json.obj(
          "kw_per_unit"    -> KwPerUnit.toDouble.asJson,
          "window_months"  -> months.asJson,
          "smoothing_days" -> smooth.asJson,
          "as_of"          -> rows.lastOption.map(_._1.toString).getOrElse("").asJson,
          "headline" -> Json.obj(
            "total_units"          -> totalUnits.asJson,
            "total_mw"             -> (BigDecimal(totalUnits) * KwPerUnit / 1000).setScale(1, HALF_UP).toDouble.asJson,
            "current_avg_daily_mw" -> mw(lastMean).asJson,
            "in_window_units"      -> units.sum.asJson
          ),
          "points" -> Json.fromValues(points)
        )
      }
    )
  }
}
