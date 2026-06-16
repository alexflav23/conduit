package com.hypervolt.conduit.supply

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import scala.math.BigDecimal.RoundingMode.HALF_UP

// Activation analytics for the /activations Analytics subroute: count + MW time series (day/week/month) and the
// headline KPIs, off the serial register (the real activation source — 7.4 kW per charger). Anchored on the data
// frontier (last ingested activation) so the trailing windows reflect real activity, not the feed lag.
object ActivationStatsRepo {

  private val Kw                  = BigDecimal("7.4")
  private def mw(c: Long): Double = (BigDecimal(c) * Kw / 1000).setScale(3, HALF_UP).toDouble

  def series(bucket: String, months: Int): ConnectionIO[Json] = {
    val b   = if (Set("week", "month")(bucket)) bucket else "day"
    val win = months.max(1).min(60)
    (fr"SELECT date_trunc(" ++ Fragment.const(s"'$b'") ++ fr""", s.activated_at)::date, count(*)::bigint
        FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
        WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL
          AND s.activated_at >= (CURRENT_DATE - make_interval(months => $win))
        GROUP BY 1 ORDER BY 1""")
      .query[(LocalDate, Long)]
      .to[List]
      .map(rows =>
        Json.fromValues(rows.map { case (d, c) => Json.obj("period" -> d.toString.asJson, "count" -> c.asJson, "mw" -> mw(c).asJson) })
      )
  }

  def kpis: ConnectionIO[Json] =
    sql"""WITH a AS (
            SELECT s.activated_at FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
            WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL
          ),
          f AS (SELECT max(activated_at)::date d FROM a)
          SELECT count(*)::bigint,
                 count(*) FILTER (WHERE activated_at::date > (SELECT d FROM f) - 7)::bigint,
                 count(*) FILTER (WHERE activated_at::date > (SELECT d FROM f) - 30)::bigint,
                 (SELECT d FROM f)
          FROM a"""
      .query[(Long, Long, Long, LocalDate)]
      .unique
      .map {
        case (total, w, m, frontier) =>
          Json.obj(
            "total"    -> total.asJson,
            "last_7d"  -> w.asJson,
            "last_30d" -> m.asJson,
            "total_mw" -> (BigDecimal(total) * Kw / 1000).setScale(1, HALF_UP).toDouble.asJson,
            "as_of"    -> frontier.toString.asJson
          )
      }
}
