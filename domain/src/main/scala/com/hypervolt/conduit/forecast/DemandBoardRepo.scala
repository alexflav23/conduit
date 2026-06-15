package com.hypervolt.conduit.forecast

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

// The demand board (doc 12 / spec/ui H6Q): the forecast rolled up by revenue segment — quarterly unit shape, the
// 6-month trend, REVENUE at each segment's net tier price, and TIME-AWARE attainment. Attainment compares actual
// shipments (real-world EV installs) to forecast, so it only exists up to "now": prior quarters show actual
// attainment; the quarter in progress shows quarter-to-date plus a forecast end-of-quarter (run-rate to the full
// quarter); future quarters show none. The year total is a pro-rata run-rate projection ("if we keep this pace").
// Accounts are the base unit; segments roll up and expand to their accounts.
object DemandBoardRepo {

  private final case class Acct(company: UUID, name: String, segment: String, activations: Long)

  private def accounts: ConnectionIO[List[Acct]] =
    sql"""SELECT s.company_id, p.display_name, COALESCE(p.sector, p.segment, 'unclassified'),
                 count(*) FILTER (WHERE s.status = 'activated')
          FROM serial_unit s JOIN party p ON p.id = s.company_id
          WHERE s.company_id IS NOT NULL
          GROUP BY s.company_id, p.display_name, COALESCE(p.sector, p.segment, 'unclassified')"""
      .query[(UUID, String, String, Long)]
      .to[List]
      .map(_.map { case (c, n, seg, a) => Acct(c, n, seg, a) })

  // Shipments per account per calendar quarter of the forecast year (from the dispatch date) — the actuals that
  // attainment is measured against.
  private def shippedByQuarter(year: Int): ConnectionIO[Map[UUID, Map[Int, Long]]] = {
    val from = LocalDate.of(year, 1, 1)
    val to   = LocalDate.of(year + 1, 1, 1)
    sql"""SELECT s.company_id, EXTRACT(QUARTER FROM d.date)::int, count(*)
          FROM serial_unit s JOIN dispatch d ON d.id = s.dispatch_id
          WHERE s.company_id IS NOT NULL AND d.date >= $from AND d.date < $to
          GROUP BY 1, 2"""
      .query[(UUID, Int, Long)]
      .to[List]
      .map(_.groupBy(_._1).view.mapValues(_.map(r => r._2 -> r._3).toMap).toMap)
  }

  private def marketMonthly(market: UUID, scenario: UUID): ConnectionIO[List[(String, BigDecimal)]] =
    sql"""SELECT to_char(period_month, 'YYYY-MM'), SUM(forecast_qty)::numeric
          FROM pipeline_coverage
          WHERE market_id = $market AND scenario_id = $scenario AND level = 'market' AND product_variant_id IS NOT NULL
          GROUP BY 1 ORDER BY 1"""
      .query[(String, BigDecimal)]
      .to[List]

  private def aspBySegment: ConnectionIO[Map[String, BigDecimal]] =
    sql"""SELECT COALESCE(p.sector, p.segment, 'unclassified'),
                 SUM(ol.unit_price_ex_vat * ol.qty) / NULLIF(SUM(ol.qty), 0)
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id JOIN party p ON p.id = o.sold_to_party_id
          WHERE ol.unit_price_ex_vat > 0
          GROUP BY 1"""
      .query[(String, Option[BigDecimal])]
      .to[List]
      .map(_.collect { case (s, Some(a)) => s -> a }.toMap)

  private def fxRate(quote: String): ConnectionIO[Option[BigDecimal]] =
    if (quote == "GBP") Option(BigDecimal(1)).pure[ConnectionIO]
    else sql"SELECT rate FROM exchange_rate WHERE base = 'GBP' AND quote = $quote ORDER BY as_of DESC LIMIT 1".query[BigDecimal].option

  private val SegLabel = Map(
    "installers"    -> "Installers",
    "energy"        -> "Energy",
    "wholesale"     -> "Wholesale",
    "online_retail" -> "Online retail",
    "unclassified"  -> "Unclassified"
  )

  private def qNum(month: String): Int = (month.substring(5).toInt + 2) / 3

  // The "now" anchor for attainment: which quarter is in progress and how far through the quarter/year we are.
  private final case class QCtx(forecastYear: Int, nowYear: Int, curQ: Int, fracQ: Double, fracYear: Double) {
    def state(q: Int): String =
      if (forecastYear < nowYear) "prior"
      else if (forecastYear > nowYear) "future"
      else if (q < curQ) "prior"
      else if (q == curQ) "current"
      else "future"
    def elapsed: Boolean = forecastYear < nowYear // the whole forecast year is already in the past
  }
  private def qctx(forecastYear: Int, now: LocalDate): QCtx = {
    val ny     = now.getYear
    val curQ   = (now.getMonthValue + 2) / 3
    val qStart = LocalDate.of(ny, (curQ - 1) * 3 + 1, 1)
    val qEnd   = qStart.plusMonths(3)
    val fracQ  = math.min(1.0, math.max(0.02, ChronoUnit.DAYS.between(qStart, now).toDouble / ChronoUnit.DAYS.between(qStart, qEnd).toDouble))
    val yStart = LocalDate.of(ny, 1, 1)
    val fracY  = math.min(1.0, math.max(0.02, ChronoUnit.DAYS.between(yStart, now).toDouble / 365.0))
    QCtx(forecastYear, ny, curQ, fracQ, fracY)
  }

  private def attn(shipped: BigDecimal, forecast: BigDecimal): Json =
    (if (forecast > 0) (shipped / forecast).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble else 0.0).asJson

  private def rowJson(label: String, key: String, monthly: List[(String, BigDecimal)], shippedQ: Map[Int, Long],
                      revenue: BigDecimal, qx: QCtx, contributors: Option[List[Json]]): Json = {
    val byQ      = monthly.groupBy { case (m, _) => qNum(m) }.view.mapValues(_.map(_._2).sum).toMap
    val yy       = monthly.headOption.map(_._1.substring(2, 4)).getOrElse("")
    val forecast = monthly.map(_._2).sum
    val spark    = monthly.map(_._2.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)
    val sortedQ  = byQ.keys.toList.sorted
    val qoq =
      if (sortedQ.length >= 2 && byQ(sortedQ(sortedQ.length - 2)) > 0)
        ((byQ(sortedQ.last) - byQ(sortedQ(sortedQ.length - 2))) / byQ(sortedQ(sortedQ.length - 2)) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP)
      else BigDecimal(0)

    val quarters = sortedQ.map { q =>
      val fcq = byQ(q)
      val shq = BigDecimal(shippedQ.getOrElse(q, 0L))
      val st  = qx.state(q)
      val base = Json.obj(
        "q"       -> s"Q$q $yy".asJson,
        "units"   -> fcq.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong.asJson,
        "shipped" -> shq.toLong.asJson,
        "state"   -> st.asJson
      )
      st match {
        case "prior"   => base.deepMerge(Json.obj("attainment" -> attn(shq, fcq)))
        case "current" => base.deepMerge(Json.obj("attainment" -> attn(shq, fcq), "eoq" -> attn(shq / qx.fracQ, fcq)))
        case _         => base
      }
    }

    // Year pro-rata: completed year ⇒ actual; future year ⇒ none; current ⇒ run-rate of YTD shipments to full year.
    val totalShipped = shippedQ.values.sum
    val ytdShipped   = (1 to qx.curQ).map(q => shippedQ.getOrElse(q, 0L)).sum
    val forecastAttainment: Json =
      if (qx.forecastYear < qx.nowYear) attn(BigDecimal(totalShipped), forecast)
      else if (qx.forecastYear > qx.nowYear) Json.Null
      else attn(BigDecimal(ytdShipped) / qx.fracYear, forecast)

    Json.obj(
      "key"          -> key.asJson,
      "label"        -> label.asJson,
      "quarters"     -> Json.fromValues(quarters),
      "forecast"     -> forecast.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong.asJson,
      "shipped"      -> totalShipped.asJson,
      "forecast_attainment" -> forecastAttainment,
      "trend"        -> Json.obj("qoq_pct" -> qoq.toInt.asJson, "spark" -> spark.asJson),
      "revenue"      -> revenue.setScale(2, BigDecimal.RoundingMode.HALF_UP).toString.asJson,
      "contributors" -> contributors.fold(Json.Null)(Json.fromValues)
    )
  }

  def board(market: UUID, scenario: UUID, contributorsPerSegment: Int, currency: String, now: LocalDate): ConnectionIO[Json] =
    marketMonthly(market, scenario).flatMap { mkt =>
      val year = mkt.headOption.map(_._1.take(4).toInt).getOrElse(now.getYear)
      val qx   = qctx(year, now)
      (accounts, shippedByQuarter(year), aspBySegment, fxRate(currency)).mapN { (accts, shipQ, asp, rateOpt) =>
        val fx       = rateOpt.getOrElse(BigDecimal(1))
        val ccy      = if (rateOpt.isDefined) currency else "GBP"
        val totalAct = math.max(accts.map(_.activations).sum, 1L)
        val months   = mkt.map(_._1)
        def aspOf(seg: String): BigDecimal               = asp.getOrElse(seg, asp.values.headOption.getOrElse(BigDecimal(0)))
        def monthlyFor(share: BigDecimal): List[(String, BigDecimal)] = mkt.map { case (m, fc) => m -> fc * share }
        def shipOf(a: Acct): Map[Int, Long]              = shipQ.getOrElse(a.company, Map.empty)

        def revenueOf(monthly: List[(String, BigDecimal)], seg: String): BigDecimal = monthly.map(_._2).sum * aspOf(seg) * fx

        val bySegment = accts.groupBy(_.segment)
        val segments = bySegment.toList.map {
          case (seg, members) =>
            val segMonthly = monthlyFor(BigDecimal(members.map(_.activations).sum) / totalAct)
            val segShipQ   = members.foldLeft(Map.empty[Int, Long]) { (acc, a) =>
              shipOf(a).foldLeft(acc) { case (m, (q, n)) => m.updated(q, m.getOrElse(q, 0L) + n) }
            }
            val contribs = members.sortBy(-_.activations).take(contributorsPerSegment).map { a =>
              val m = monthlyFor(BigDecimal(a.activations) / totalAct)
              rowJson(a.name, a.company.toString, m, shipOf(a), revenueOf(m, seg), qx, None)
            }
            (members.map(_.activations).sum, rowJson(SegLabel.getOrElse(seg, seg), seg, segMonthly, segShipQ, revenueOf(segMonthly, seg), qx, Some(contribs)))
        }.sortBy(-_._1).map(_._2)

        val totalShipQ = shipQ.values.foldLeft(Map.empty[Int, Long]) { (acc, m) =>
          m.foldLeft(acc) { case (a, (q, n)) => a.updated(q, a.getOrElse(q, 0L) + n) }
        }
        val totalRevenue = bySegment.toList.map { case (seg, members) =>
          revenueOf(monthlyFor(BigDecimal(members.map(_.activations).sum) / totalAct), seg)
        }.sum
        Json.obj(
          "months"   -> months.asJson,
          "currency" -> ccy.asJson,
          "fx_rate"  -> fx.toString.asJson,
          "as_of"    -> now.toString.asJson,
          "segments" -> Json.fromValues(segments),
          "total"    -> rowJson("Total", "__total__", mkt, totalShipQ, totalRevenue, qx, None)
        )
      }
    }
}
