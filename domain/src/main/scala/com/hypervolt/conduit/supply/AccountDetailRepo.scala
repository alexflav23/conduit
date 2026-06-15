package com.hypervolt.conduit.supply

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Per-account drill (ghost-busters parity): the deliveries MRPeasy shipped to one account (each a conduit-native
// tranche, dated + depletion-scored from the serial register), the activation rate over time at three grains,
// and the depletion/runway curve from the depletion_snapshot backtest. All read-only off ingested data.
object AccountDetailRepo {

  // Each dispatch to this account = one delivery/tranche: units shipped vs activated → depletion %.
  def deliveries(company: UUID, limit: Int): ConnectionIO[List[Json]] =
    sql"""SELECT d.dispatch_no, d.date, d.delivered_at, d.status,
                 count(s.id) AS shipped,
                 count(s.id) FILTER (WHERE s.status = 'activated') AS activated
          FROM dispatch d
          JOIN "order" o ON o.id = d.order_id
          LEFT JOIN serial_unit s ON s.dispatch_id = d.id
          WHERE o.sold_to_party_id = $company
          GROUP BY d.id, d.dispatch_no, d.date, d.delivered_at, d.status
          ORDER BY d.date DESC NULLS LAST
          LIMIT $limit"""
      .query[(String, Option[Instant], Option[Instant], String, Int, Int)]
      .to[List]
      .map(_.map {
        case (no, date, delivered, status, shipped, activated) =>
          Json.obj(
            "dispatch_no"   -> no.asJson,
            "date"          -> date.map(_.toString).asJson,
            "delivered_at"  -> delivered.map(_.toString).asJson,
            "status"        -> status.asJson,
            "shipped"       -> shipped.asJson,
            "activated"     -> activated.asJson,
            "depletion_pct" -> (if (shipped > 0) BigDecimal(activated * 100) / shipped else BigDecimal(0)).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble.asJson
          )
      })

  private def activationsAt(company: UUID, grain: String): ConnectionIO[List[Json]] =
    (fr"""SELECT date_trunc($grain, activated_at)::date AS period, count(*)
          FROM serial_unit
          WHERE company_id = $company AND activated_at IS NOT NULL
          GROUP BY 1 ORDER BY 1""")
      .query[(LocalDate, Int)]
      .to[List]
      .map(_.map { case (p, n) => Json.obj("period" -> p.toString.asJson, "activated" -> n.asJson) })

  // Activation rate over time at three grains — the desk picks one to plot.
  def activationSeries(company: UUID): ConnectionIO[Json] =
    (activationsAt(company, "day"), activationsAt(company, "week"), activationsAt(company, "month")).mapN { (d, w, m) =>
      Json.obj("daily" -> Json.fromValues(d), "weekly" -> Json.fromValues(w), "monthly" -> Json.fromValues(m))
    }

  // Account-level depletion/runway curve: shelf stock + run-rate over the backtest months (summed across variants).
  def depletionSeries(company: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT origin_month, sum(shelf_stock), sum(velocity_3m), avg(runway_days)
          FROM depletion_snapshot
          WHERE company_id = $company
          GROUP BY origin_month ORDER BY origin_month"""
      .query[(LocalDate, BigDecimal, BigDecimal, Option[BigDecimal])]
      .to[List]
      .map(_.map {
        case (month, shelf, velocity, runway) =>
          Json.obj(
            "month"       -> month.toString.asJson,
            "shelf_stock" -> shelf.toDouble.asJson,
            "velocity_3m" -> velocity.toDouble.asJson,
            "runway_days" -> runway.map(_.toDouble).asJson
          )
      })

  // Account headline straight off the serial register: shipped (all serials), activated, on-shelf = the difference.
  def summary(company: UUID): ConnectionIO[Json] =
    sql"""SELECT p.display_name, COALESCE(p.sector, p.segment),
                 count(s.id) AS shipped,
                 count(s.id) FILTER (WHERE s.status = 'activated') AS activated
          FROM party p LEFT JOIN serial_unit s ON s.company_id = p.id
          WHERE p.id = $company
          GROUP BY p.display_name, COALESCE(p.sector, p.segment)"""
      .query[(String, Option[String], Int, Int)]
      .option
      .map(_.fold(Json.Null) {
        case (name, sector, shipped, activated) =>
          Json.obj(
            "name"      -> name.asJson,
            "sector"    -> sector.asJson,
            "shipped"   -> shipped.asJson,
            "activated" -> activated.asJson,
            "on_shelf"  -> (shipped - activated).asJson
          )
      })

  private def weeklyActivations(company: UUID): ConnectionIO[List[(LocalDate, Int)]] =
    sql"""SELECT date_trunc('week', activated_at)::date, count(*)::int
          FROM serial_unit
          WHERE company_id = $company AND status = 'activated' AND activated_at IS NOT NULL
          GROUP BY 1 ORDER BY 1"""
      .query[(LocalDate, Int)]
      .to[List]

  // Serials shipped per week (dispatch date) — the (+) side of the actual on-shelf reconstruction.
  private def weeklyShipped(company: UUID): ConnectionIO[List[(LocalDate, Int)]] =
    sql"""SELECT date_trunc('week', d.date)::date, count(*)::int
          FROM serial_unit s JOIN dispatch d ON d.id = s.dispatch_id
          WHERE s.company_id = $company AND d.date IS NOT NULL
          GROUP BY 1 ORDER BY 1"""
      .query[(LocalDate, Int)]
      .to[List]

  private def onShelf(company: UUID): ConnectionIO[Int] =
    sql"SELECT count(*)::int FROM serial_unit WHERE company_id = $company AND status = 'dispatched'".query[Int].unique

  def detail(company: UUID, deliveryLimit: Int, anchor: LocalDate): ConnectionIO[Json] =
    (summary(company), deliveries(company, deliveryLimit), activationSeries(company), depletionSeries(company),
     weeklyActivations(company), weeklyShipped(company), onShelf(company)).mapN { (sum, dels, acts, depl, weekly, shipped, shelf) =>
      Json.obj(
        "summary"     -> sum,
        "deliveries"  -> Json.fromValues(dels),
        "activations" -> acts,
        "depletion"   -> Json.fromValues(depl),
        "forecast"    -> DepletionForecast.project(weekly, shipped, shelf, anchor)
      )
    }
}
