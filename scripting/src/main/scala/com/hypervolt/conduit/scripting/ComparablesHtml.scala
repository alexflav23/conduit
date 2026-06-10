package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.forecast.PolicyRepo
import com.hypervolt.conduit.forecast.PolicySelector
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// THE COMPARABLES (the user's bar): one table per quarter, Q2'25 → Q3'26 — per channel: actual, the policy
// the censored evidence selected, its forecast, the error — units and £ (realized net price per account).
// Open/future quarters carry the nowcast/forward and the TAM-seasonal band instead of an error.
object ComparablesHtml extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val backtests = List(
    ("Q2'25", LocalDate.of(2025, 4, 1)),
    ("Q3'25", LocalDate.of(2025, 7, 1)),
    ("Q4'25", LocalDate.of(2025, 10, 1)),
    ("Q1'26", LocalDate.of(2026, 1, 1))
  )

  private def sectorOf(name: String): String = {
    val n = name.toLowerCase
    if (
      List("octopus", "e.on", "eon energy", "edf", "ovo", "british gas", "scottish power", "good energy", "shell")
        .exists(n.contains)
    ) "Energy"
    else if (
      List(
        "yesss",
        "rexel",
        "cef",
        "city electrical",
        "medlock",
        "kelvelec",
        "edmundson",
        "denmans",
        "wolseley",
        "electric center",
        "stearn"
      ).exists(n.contains)
    ) "Wholesale/Distribution"
    else if (List("smart home charge", "ev store", "evec", "amazon").exists(n.contains)) "Online Retail"
    else "Installers"
  }

  private val accounts: ConnectionIO[List[(UUID, String)]] =
    sql"SELECT id, replace(display_name, 'MRP: ', '') FROM party WHERE display_name LIKE 'MRP: %'"
      .query[(UUID, String)]
      .to[List]

  private val prices: ConnectionIO[Map[UUID, BigDecimal]] =
    sql"""SELECT o.sold_to_party_id, SUM(ol.unit_price_ex_vat * ol.qty) / SUM(ol.qty)
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE o.order_no LIKE 'MRP-%' AND ol.unit_price_ex_vat > 0 AND ol.qty > 0
            AND pv.product_class = 'charger' AND o.created_at >= '2025-01-01'
          GROUP BY 1""".query[(UUID, BigDecimal)].to[List].map(_.toMap)

  private def scored(company: UUID, origin: LocalDate): ConnectionIO[Map[String, (BigDecimal, BigDecimal)]] =
    sql"""SELECT model_key, SUM(forecast_qty), SUM(actual_qty)
          FROM model_accuracy WHERE company_id = $company AND origin_month = $origin GROUP BY model_key"""
      .query[(String, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map { case (k, f, a) => k -> ((f, a)) }.toMap)

  private def fwd(company: UUID, from: LocalDate, until: LocalDate): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(qty), 0) FROM forecast_entry
          WHERE company_id = $company AND source = 'model' AND superseded_by IS NULL
            AND period_month >= $from AND period_month < $until""".query[BigDecimal].unique

  private case class Cell(sector: String, policy: String, f: BigDecimal, a: BigDecimal, price: BigDecimal)

  override def run: IO[Unit] =
    for {
      accts  <- accounts.transact(xa)
      priceM <- prices.transact(xa)
      median = {
        val ps = priceM.values.toList.sorted
        if (ps.isEmpty) BigDecimal(600) else ps(ps.size / 2)
      }
      priceOf = (id: UUID) => priceM.getOrElse(id, median)
      cells <- backtests.traverse {
        case (label, o) =>
          accts
            .traverse {
              case (id, name) =>
                (PolicyRepo.evidence(id, o).transact(xa), scored(id, o).transact(xa)).mapN { (ev, rows) =>
                  rows.values.headOption.map {
                    case (_, actual) =>
                      val policy = PolicySelector.select(ev)
                      val f = policy.weights.toList
                        .map { case (k, w) => rows.get(k).map(_._1).getOrElse(BigDecimal(0)) * w }
                        .foldLeft(BigDecimal(0))(_ + _)
                      Cell(sectorOf(name), policy.key, f, actual, priceOf(id))
                  }
                }
            }
            .map(rs => label -> rs.flatten)
      }
      q2actual <- accts.traverse {
        case (id, name) =>
          sql"""SELECT COALESCE(count(*), 0)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
                WHERE su.company_id = $id AND COALESCE(d.delivered_at, d.date::timestamptz) >= '2026-04-01'"""
            .query[BigDecimal]
            .unique
            .transact(xa)
            .map(u => (sectorOf(name), u, priceOf(id)))
      }
      junC <- accts.traverse {
        case (id, name) =>
          fwd(id, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))
            .transact(xa)
            .map(u => (sectorOf(name), u, priceOf(id)))
      }
      q3C <- accts.traverse {
        case (id, name) =>
          fwd(id, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1))
            .transact(xa)
            .map(u => (sectorOf(name), u, priceOf(id)))
      }
      // the uncovered tail: units dispatched this quarter to accounts OUTSIDE the covered MRP party set
      // (new accounts, unattributed serials) — invisible in the covered rows, so stated, never hidden
      allQ2 <-
        sql"""SELECT COUNT(*)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
              WHERE COALESCE(d.delivered_at, d.date::timestamptz) >= '2026-04-01'"""
          .query[BigDecimal]
          .unique
          .transact(xa)
      d2c <-
        sql"""SELECT period_month, value FROM exogenous_series
                   WHERE series_key = 'stripe_d2c_gross_gbp' ORDER BY period_month"""
          .query[(LocalDate, BigDecimal)]
          .to[List]
          .transact(xa)
      refund <- sql"""SELECT COALESCE(AVG(value), 3.6) FROM exogenous_series
                   WHERE series_key = 'stripe_d2c_refund_rate'""".query[BigDecimal].unique.transact(xa)
    } yield {
      val sectors                    = List("Wholesale/Distribution", "Energy", "Installers", "Online Retail")
      def gbp(x: BigDecimal): String = "£" + (x / 1000).setScale(1, RoundingMode.HALF_UP) + "k"
      def u(x: BigDecimal): String   = x.setScale(0, RoundingMode.HALF_UP).toString

      def dominantPolicy(rs: List[Cell]): String =
        rs.groupBy(_.policy).view.mapValues(_.map(_.a).sum).toList.sortBy(-_._2).headOption.map(_._1).getOrElse("—")

      def errCls(e: BigDecimal): String = if (e <= 20) "good" else if (e <= 50) "mid" else "bad"

      val quarterTables = cells.map {
        case (label, rs) =>
          val rows = sectors.map { sec =>
            val xs = rs.filter(_.sector == sec)
            val fU = xs.map(_.f).foldLeft(BigDecimal(0))(_ + _)
            val aU = xs.map(_.a).foldLeft(BigDecimal(0))(_ + _)
            val fM = xs.map(c => c.f * c.price).foldLeft(BigDecimal(0))(_ + _)
            val aM = xs.map(c => c.a * c.price).foldLeft(BigDecimal(0))(_ + _)
            val e  = if (aU > 0) ((fU - aU).abs / aU * 100).setScale(1, RoundingMode.HALF_UP) else BigDecimal(0)
            s"<tr><td>$sec</td><td>${dominantPolicy(xs)}</td><td class='num'>${u(aU)}</td><td class='num'>${u(fU)}</td>" +
              s"<td class='num'>${gbp(aM)}</td><td class='num'>${gbp(fM)}</td><td class='${errCls(e)}'>$e%</td></tr>"
          }
          val fT = rs.map(_.f).foldLeft(BigDecimal(0))(_ + _)
          val aT = rs.map(_.a).foldLeft(BigDecimal(0))(_ + _)
          val fM = rs.map(c => c.f * c.price).foldLeft(BigDecimal(0))(_ + _)
          val aM = rs.map(c => c.a * c.price).foldLeft(BigDecimal(0))(_ + _)
          val eT = if (aT > 0) ((fT - aT).abs / aT * 100).setScale(1, RoundingMode.HALF_UP) else BigDecimal(0)
          s"""<h2>$label — backtest (selected censored at quarter start)</h2>
<table><tr><th>channel</th><th>policy</th><th>actual u</th><th>forecast u</th><th>actual £</th><th>forecast £</th><th>err</th></tr>
${rows.mkString("\n")}
<tr class='tot'><td>TOTAL</td><td></td><td class='num'>${u(aT)}</td><td class='num'>${u(fT)}</td><td class='num'>${gbp(
            aM
          )}</td><td class='num'>${gbp(fM)}</td><td class='${errCls(eT)}'>$eT%</td></tr></table>"""
      }

      val q2Rows = sectors.map { sec =>
        val a  = q2actual.filter(_._1 == sec)
        val j  = junC.filter(_._1 == sec)
        val aU = a.map(_._2).sum
        val jU = j.map(_._2).sum
        val aM = a.map(r => r._2 * r._3).sum
        val jM = j.map(r => r._2 * r._3).sum
        s"<tr><td>$sec</td><td class='num'>${u(aU)}</td><td class='num'>${u(jU)}</td><td class='num'>${u(aU + jU)}</td>" +
          s"<td class='num'>${gbp(aM + jM)}</td></tr>"
      }
      val q2aT = q2actual.map(_._2).sum; val q2jT = junC.map(_._2).sum
      val q2mT = q2actual.map(r => r._2 * r._3).sum + junC.map(r => r._2 * r._3).sum
      // tail scales the June model by its share of actuals-to-date; priced at the covered median
      val tailA    = (allQ2 - q2aT).max(BigDecimal(0))
      val tailJ    = if (q2aT > 0) (q2jT * tailA / q2aT).setScale(0, RoundingMode.HALF_UP) else BigDecimal(0)
      val tailM    = (tailA + tailJ) * median
      val allInU   = q2aT + q2jT + tailA + tailJ
      val allInGbp = q2mT + tailM

      val q3Rows = sectors.map { sec =>
        val xs = q3C.filter(_._1 == sec)
        val uU = xs.map(_._2).sum
        val mM = xs.map(r => r._2 * r._3).sum
        s"<tr><td>$sec</td><td class='num'>${u(uU)}</td><td class='num'>${gbp(mM)}</td></tr>"
      }
      val q3uT = q3C.map(_._2).sum
      val q3mT = q3C.map(r => r._2 * r._3).sum

      val net      = (BigDecimal(100) - refund) / 100
      val trailing = d2c.takeRight(12).map(_._2)
      val mayLevel = trailing.lastOption.getOrElse(BigDecimal(0))
      val cmgr =
        if (trailing.size >= 2 && trailing.head > 0)
          math.pow((trailing.last / trailing.head).toDouble, 1.0 / (trailing.size - 1))
        else 1.0
      val d2cQ2 = (d2c.filter(r => r._1.getYear == 2026 && r._1.getMonthValue >= 4).map(_._2).sum + mayLevel) * net
      val d2cQ3 =
        (2 to 4).map(i => mayLevel * BigDecimal(math.pow(cmgr, i.toDouble))).foldLeft(BigDecimal(0))(_ + _) * net

      val html = s"""<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,Helvetica,sans-serif;background:#101014;color:#e8e8ee;margin:28px;max-width:1180px}
h1{font-size:22px}h1 b{color:#962DFF}h2{font-size:15px;color:#b9a7e8;border-bottom:1px solid #2a2a35;padding-bottom:6px;margin-top:30px}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
td,th{padding:6px 12px;border-bottom:1px solid #23232c;text-align:right;font-size:13px}
td:first-child,th:first-child{text-align:left}td:nth-child(2),th:nth-child(2){text-align:left}
.num{font-weight:600}.good{color:#6ee7a0;font-weight:700}.mid{color:#e7c76e;font-weight:700}.bad{color:#e76e6e;font-weight:700}
tr.tot td{background:#1d1430;color:#d9c5ff;font-weight:700}
.note{color:#8a8aa0;font-size:12px;margin:6px 0}.kpi{display:inline-block;background:#17171f;border:1px solid #2a2a35;border-radius:8px;padding:12px 22px;margin-right:14px}
.kpi b{display:block;font-size:24px;color:#962DFF}.kpi span{font-size:11px;color:#9a9ab0}</style></head><body>
<h1><b>Conduit</b> — channel comparables, Q2'25 → Q3'26 <span style="font-size:12px;color:#8a8aa0">serial-attributed dispatch basis · realized tier prices · depletion live · ${LocalDate
        .now()}</span></h1>
<p class="note">Backtests: the policy is selected on evidence strictly BEFORE each quarter, then scored against what happened. Money = units × each account's realized net unit price (embeds its tier). Q2'25 coverage is partial (the serialized record reaches full adoption Q4'24→Q1'25), flagged not hidden.</p>
${quarterTables.mkString("\n")}

<h2>Q2'26 — NOWCAST (open quarter: Apr+May actual + June model)</h2>
<table><tr><th>channel</th><th>actual-to-date u</th><th>June model u</th><th>projected u</th><th>projected £</th></tr>
${q2Rows.mkString("\n")}
<tr class='tot'><td>TOTAL B2B</td><td class='num'>${u(q2aT)}</td><td class='num'>${u(q2jT)}</td><td class='num'>${u(
        q2aT + q2jT
      )}</td><td class='num'>${gbp(q2mT)}</td></tr>
<tr><td>Uncovered tail (accounts outside the 651 covered — new/unattributed)</td><td class='num'>${u(
        tailA
      )}</td><td class='num'>${u(tailJ)}</td><td class='num'>${u(tailA + tailJ)}</td><td class='num'>${gbp(
        tailM
      )}</td></tr>
<tr class='tot'><td>ALL-IN B2B</td><td class='num'>${u(q2aT + tailA)}</td><td class='num'>${u(
        q2jT + tailJ
      )}</td><td class='num'>${u(allInU)}</td><td class='num'>${gbp(allInGbp)}</td></tr>
<tr><td>D2C (Stripe, net of ${refund.setScale(
        1,
        RoundingMode.HALF_UP
      )}% refunds)</td><td></td><td></td><td></td><td class='num'>${gbp(
        d2cQ2
      )}</td></tr></table>

<h2>Q3'26 — FORWARD (selected censored at today; no actuals yet)</h2>
<table><tr><th>channel</th><th>forecast u</th><th>forecast £</th></tr>
${q3Rows.mkString("\n")}
<tr class='tot'><td>TOTAL B2B (floor)</td><td class='num'>${u(q3uT)}</td><td class='num'>${gbp(q3mT)}</td></tr>
<tr><td>D2C (own curve, CMGR +${(BigDecimal(cmgr) * 100 - 100)
        .setScale(1, RoundingMode.HALF_UP)}%/mo)</td><td></td><td class='num'>${gbp(
        d2cQ3
      )}</td></tr></table>
<p class="note">TAM-seasonal band (H6Q SMMT, six-year profile — the market does 56% of its year in H2): Q3'26 ${u(
        q3uT
      )}u floor → ${u(q3uT * BigDecimal("25.93") / BigDecimal("21.84"))}u seasonal · Q4'26 seasonal ${u(
        (q2aT + q2jT) * BigDecimal("30.34") / BigDecimal("21.84")
      )}u. The truth trades inside the band; depletion narrows it as activation evidence deepens.</p>
</body></html>"""
      Files.write(Paths.get("/tmp/conduit-comparables.html"), html.getBytes(StandardCharsets.UTF_8))
      println("written /tmp/conduit-comparables.html")
    }
}
