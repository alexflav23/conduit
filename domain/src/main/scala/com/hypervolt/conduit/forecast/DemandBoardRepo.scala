package com.hypervolt.conduit.forecast

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// The demand board (doc 12 / spec/ui H6Q): the forecast rolled up by revenue segment, each row showing the
// quarterly unit shape, the 6-month trend (QoQ delta + sparkline), shipped + attainment, and REVENUE. Revenue is
// units × the segment's realized net ASP from order history — so the per-customer-type tier pricing (Installers
// ~£256 vs Energy ~£476) flows through, not a flat price. Accounts are the base unit: the market P50 monthly
// forecast is allocated by each account's activation share, accounts roll up into their segment, and each segment
// carries its contributing accounts for expansion.
object DemandBoardRepo {

  private final case class Acct(company: UUID, name: String, segment: String, activations: Long, shipped: Long)

  // Activations are all-time (the allocation weight); shipped is scoped to the forecast year (via the dispatch
  // date) so attainment = shipped-in-period ÷ forecast-for-period reads as on-track, not all-time over 100%.
  private def accounts(year: Int): ConnectionIO[List[Acct]] = {
    val from = java.time.LocalDate.of(year, 1, 1)
    val to   = java.time.LocalDate.of(year + 1, 1, 1)
    sql"""SELECT s.company_id, p.display_name, COALESCE(p.sector, p.segment, 'unclassified'),
                 count(*) FILTER (WHERE s.status = 'activated'),
                 count(*) FILTER (WHERE d.date >= $from AND d.date < $to)
          FROM serial_unit s JOIN party p ON p.id = s.company_id
          LEFT JOIN dispatch d ON d.id = s.dispatch_id
          WHERE s.company_id IS NOT NULL
          GROUP BY s.company_id, p.display_name, COALESCE(p.sector, p.segment, 'unclassified')"""
      .query[(UUID, String, String, Long, Long)]
      .to[List]
      .map(_.map { case (c, n, seg, a, sh) => Acct(c, n, seg, a, sh) })
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

  private val SegLabel = Map(
    "installers"    -> "Installers",
    "energy"        -> "Energy",
    "wholesale"     -> "Wholesale",
    "online_retail" -> "Online retail",
    "unclassified"  -> "Unclassified"
  )

  private def quarterOf(month: String): String = f"Q${(month.substring(5).toInt + 2) / 3}%d ${month.substring(2, 4)}"

  // A single board row (segment or account): the monthly unit series allocated to it, plus shipped + ASP.
  // `fx` converts revenue from GBP into the presentation currency (1 for GBP).
  private def rowJson(label: String, key: String, monthly: List[(String, BigDecimal)], shipped: Long, asp: BigDecimal, fx: BigDecimal, contributors: Option[List[Json]]): Json = {
    val forecast = monthly.map(_._2).sum
    val quarters = monthly.groupBy { case (m, _) => quarterOf(m) }.view.mapValues(_.map(_._2).sum).toList.sortBy(_._1)
    val spark    = monthly.map(_._2.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)
    val qoq =
      if (quarters.length >= 2 && quarters(quarters.length - 2)._2 > 0)
        ((quarters.last._2 - quarters(quarters.length - 2)._2) / quarters(quarters.length - 2)._2 * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP)
      else BigDecimal(0)
    val revenue = (forecast * asp * fx).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    val fc      = forecast.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong
    Json.obj(
      "key"        -> key.asJson,
      "label"      -> label.asJson,
      "quarters"   -> Json.fromValues(quarters.map { case (q, u) => Json.obj("q" -> q.asJson, "units" -> u.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong.asJson) }),
      "forecast"   -> fc.asJson,
      "shipped"    -> shipped.asJson,
      "attainment" -> (if (fc > 0) BigDecimal(shipped) / fc else BigDecimal(0)).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble.asJson,
      "trend"      -> Json.obj("qoq_pct" -> qoq.toInt.asJson, "spark" -> spark.asJson),
      "revenue"    -> revenue.toString.asJson,
      "contributors" -> contributors.fold(Json.Null)(Json.fromValues)
    )
  }

  // Latest spot rate to convert GBP into the presentation currency; 1 for GBP, None if the pair isn't seeded.
  private def fxRate(quote: String): ConnectionIO[Option[BigDecimal]] =
    if (quote == "GBP") Option(BigDecimal(1)).pure[ConnectionIO]
    else sql"SELECT rate FROM exchange_rate WHERE base = 'GBP' AND quote = $quote ORDER BY as_of DESC LIMIT 1".query[BigDecimal].option

  def board(market: UUID, scenario: UUID, contributorsPerSegment: Int, currency: String): ConnectionIO[Json] =
    marketMonthly(market, scenario).flatMap { mkt =>
      val year = mkt.headOption.map(_._1.take(4).toInt).getOrElse(2026)
      (accounts(year), aspBySegment, fxRate(currency)).mapN { (accts, asp, rateOpt) =>
      // Fall back to GBP if the requested pair isn't seeded, so revenue is never silently mis-scaled.
      val fx       = rateOpt.getOrElse(BigDecimal(1))
      val ccy      = if (rateOpt.isDefined) currency else "GBP"
      val totalAct = math.max(accts.map(_.activations).sum, 1L)
      val months   = mkt.map(_._1)
      def aspOf(seg: String): BigDecimal = asp.getOrElse(seg, asp.values.headOption.getOrElse(BigDecimal(0)))
      // Each account's monthly units = market month total × its activation share.
      def monthlyFor(share: BigDecimal): List[(String, BigDecimal)] = mkt.map { case (m, fc) => m -> fc * share }

      val bySegment = accts.groupBy(_.segment)
      val segments = bySegment.toList.map {
        case (seg, members) =>
          val segShare   = BigDecimal(members.map(_.activations).sum) / totalAct
          val segMonthly = monthlyFor(segShare)
          val segShipped = members.map(_.shipped).sum
          val contribs = members.sortBy(-_.activations).take(contributorsPerSegment).map { a =>
            rowJson(a.name, a.company.toString, monthlyFor(BigDecimal(a.activations) / totalAct), a.shipped, aspOf(seg), fx, None)
          }
          (members.map(_.activations).sum, rowJson(SegLabel.getOrElse(seg, seg), seg, segMonthly, segShipped, aspOf(seg), fx, Some(contribs)))
      }.sortBy(-_._1).map(_._2)

      // Grand total across the whole market (every account), independent of the per-segment contributor cap.
      val totalShipped = accts.map(_.shipped).sum
      val totalForecast = mkt.map(_._2).sum
      val totalRevenue = (bySegment.toList.map {
        case (seg, members) => monthlyFor(BigDecimal(members.map(_.activations).sum) / totalAct).map(_._2).sum * aspOf(seg)
      }.sum * fx).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      val totalQuarters = mkt.groupBy { case (m, _) => quarterOf(m) }.view.mapValues(_.map(_._2).sum).toList.sortBy(_._1)

      Json.obj(
        "months"   -> months.asJson,
        "currency" -> ccy.asJson,
        "fx_rate"  -> fx.toString.asJson,
        "segments" -> Json.fromValues(segments),
        "total" -> Json.obj(
          "forecast"   -> totalForecast.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong.asJson,
          "shipped"    -> totalShipped.asJson,
          "attainment" -> (if (totalForecast > 0) BigDecimal(totalShipped) / totalForecast else BigDecimal(0)).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble.asJson,
          "revenue"    -> totalRevenue.toString.asJson,
          "quarters"   -> Json.fromValues(totalQuarters.map { case (q, u) => Json.obj("q" -> q.asJson, "units" -> u.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong.asJson) })
        )
      )
      }
    }
}
