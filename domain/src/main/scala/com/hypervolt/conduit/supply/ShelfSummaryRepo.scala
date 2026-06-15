package com.hypervolt.conduit.supply

import cats.syntax.all._
import doobie._
import doobie.implicits._
import io.circe.Json
import io.circe.syntax._

// Ghost-fleet summary (spec/ui Shelf life): every dispatched-but-not-activated serial is a "ghost". This is the
// fleet headline — ghosts, the capital tied up, how much has gone stale, the median time-to-activate — plus the
// shelf-age distribution, all measured off the serial register's dispatch + activation dates.
object ShelfSummaryRepo {

  private def kpis: ConnectionIO[(Long, Long, Long, BigDecimal, Option[BigDecimal])] =
    sql"""WITH onshelf AS (
            SELECT (CURRENT_DATE - d.date::date) AS age_days
            FROM serial_unit s JOIN dispatch d ON d.id = s.dispatch_id
            WHERE s.status = 'dispatched'
          ),
          asp AS (SELECT COALESCE(SUM(unit_price_ex_vat * qty) / NULLIF(SUM(qty), 0), 0) AS v FROM order_line WHERE unit_price_ex_vat > 0),
          tta AS (
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY (s.activated_at::date - d.date::date)) AS m
            FROM serial_unit s JOIN dispatch d ON d.id = s.dispatch_id
            WHERE s.status = 'activated' AND s.activated_at IS NOT NULL
          )
          SELECT count(*),
                 count(*) FILTER (WHERE age_days > 90),
                 count(*) FILTER (WHERE age_days > 180),
                 (SELECT v FROM asp),
                 (SELECT m FROM tta)
          FROM onshelf"""
      .query[(Long, Long, Long, BigDecimal, Option[BigDecimal])]
      .unique

  private def ageBuckets: ConnectionIO[Map[String, Long]] =
    sql"""SELECT CASE
                   WHEN age <= 30 THEN '0-30'
                   WHEN age <= 60 THEN '31-60'
                   WHEN age <= 90 THEN '61-90'
                   WHEN age <= 180 THEN '91-180'
                   ELSE '180+'
                 END AS bucket, count(*)
          FROM (SELECT (CURRENT_DATE - d.date::date) AS age
                FROM serial_unit s JOIN dispatch d ON d.id = s.dispatch_id
                WHERE s.status = 'dispatched') x
          GROUP BY 1"""
      .query[(String, Long)]
      .to[List]
      .map(_.toMap)

  private val BucketOrder = List("0-30", "31-60", "61-90", "91-180", "180+")

  // Capital-tied-up converts through Conduit's FX mechanism (Consolidation.resolveRate); identity ⇒ GBP, never a
  // fabricated rate.
  def summary(currency: String, asOf: java.time.LocalDate): ConnectionIO[Json] =
    (kpis, ageBuckets, com.hypervolt.conduit.gl.ConsolidationRepo.resolveRate("GBP", currency, asOf)).mapN {
      case ((ghosts, stale90, stale180, asp, ttaDays), buckets, (rate, rateSource, _, _)) =>
        val resolved = rateSource != "identity"
        val fx       = if (resolved) rate else BigDecimal(1)
        val ccy      = if (resolved || currency == "GBP") currency else "GBP"
        Json.obj(
          "ghosts"            -> ghosts.asJson,
          "ghost_value"       -> (BigDecimal(ghosts) * asp * fx).setScale(0, BigDecimal.RoundingMode.HALF_UP).toString.asJson,
          "currency"          -> ccy.asJson,
          "stale_90"          -> stale90.asJson,
          "stale_180"         -> stale180.asJson,
          "median_tta_weeks"  -> ttaDays.map(d => (d / 7).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble).asJson,
          "age_distribution"  -> Json.fromValues(BucketOrder.map(b => Json.obj("bucket" -> b.asJson, "count" -> buckets.getOrElse(b, 0L).asJson)))
        )
    }
}
