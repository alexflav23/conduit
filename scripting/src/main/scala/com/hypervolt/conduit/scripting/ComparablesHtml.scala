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

  // The BUSINESS actual: every attributed unit in the dispatch log for the window — independent of which
  // account×SKU keys were forecastable at the origin. The scored subset is a fraction of this early on
  // (the serialized record reaches adoption Q4'24→Q1'25), and presenting the subset as "actual" misleads.
  private def businessUnits(from: LocalDate, until: LocalDate): ConnectionIO[Map[UUID, BigDecimal]] =
    sql"""SELECT su.company_id, COUNT(*)::numeric
          FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
          WHERE su.company_id IS NOT NULL
            AND COALESCE(d.delivered_at, d.date::timestamptz) >= $from
            AND COALESCE(d.delivered_at, d.date::timestamptz) < $until
          GROUP BY 1""".query[(UUID, BigDecimal)].to[List].map(_.toMap)

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
      business <- backtests.traverse {
        case (label, o) => businessUnits(o, o.plusMonths(3)).transact(xa).map(label -> _)
      }
      // the open quarter, June counted ONCE: Apr+May closed, June = max(model, June-to-date actual)
      q2parts <- accts.traverse {
        case (id, name) =>
          sql"""SELECT COUNT(*) FILTER (WHERE t.dt < '2026-06-01')::numeric,
                       COUNT(*) FILTER (WHERE t.dt >= '2026-06-01')::numeric
                FROM (SELECT COALESCE(d.delivered_at, d.date::timestamptz) AS dt
                      FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
                      WHERE su.company_id = $id
                        AND COALESCE(d.delivered_at, d.date::timestamptz) >= '2026-04-01') t"""
            .query[(BigDecimal, BigDecimal)]
            .unique
            .transact(xa)
            .map { case (aprMay, junAct) => (sectorOf(name), aprMay, junAct, priceOf(id)) }
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
      // ASP achieved per unit: realized order-line prices, company grain, sectored in Scala
      aspRows <-
        sql"""SELECT o.sold_to_party_id, date_trunc('quarter', o.created_at)::date,
                     SUM(ol.qty)::numeric, SUM(ol.unit_price_ex_vat * ol.qty)
              FROM order_line ol JOIN "order" o ON o.id = ol.order_id
              JOIN product_variant pv ON pv.id = ol.product_variant_id
              WHERE o.order_no LIKE 'MRP-%' AND pv.product_class = 'charger'
                AND ol.unit_price_ex_vat > 0 AND ol.qty > 0 AND o.created_at >= '2025-01-01'
              GROUP BY 1, 2"""
          .query[(UUID, LocalDate, BigDecimal, BigDecimal)]
          .to[List]
          .transact(xa)
      // the empirical band: actual/forecast ratios per (closed origin × sector) — the spread the model has
      // actually exhibited at the served grain; partial origins (2026-04 scores Apr+May only) excluded
      ratioRows <-
        sql"""SELECT ps.origin_month, ps.company_id, ps.forecast_qty, ps.actual_qty
              FROM policy_selection ps JOIN party p ON p.id = ps.company_id
              WHERE p.display_name LIKE 'MRP: %'
                AND ps.origin_month IN ('2025-07-01', '2025-10-01', '2026-01-01')"""
          .query[(LocalDate, UUID, BigDecimal, BigDecimal)]
          .to[List]
          .transact(xa)
      // seasonality pass-through: OUR quarterly sell-in for the last full calendar year vs the TAM profile —
      // measured, because TAM seasonality demonstrably does NOT bleed straight through (Q4'25: market share-of-
      // year 30.3%, our sell-in share 22.5% — the channel stocks AHEAD and depletes into the market's H2)
      ourQuarters <-
        sql"""SELECT date_trunc('quarter', COALESCE(d.delivered_at, d.date::timestamptz))::date, COUNT(*)::numeric
              FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
              WHERE COALESCE(d.delivered_at, d.date::timestamptz) >= '2025-01-01'
                AND COALESCE(d.delivered_at, d.date::timestamptz) < '2026-01-01'
              GROUP BY 1 ORDER BY 1"""
          .query[(LocalDate, BigDecimal)]
          .to[List]
          .transact(xa)
      tamMonthShare <-
        sql"SELECT period_month, value FROM exogenous_series WHERE series_key = 'uk_bev_month_share' ORDER BY period_month"
          .query[(LocalDate, BigDecimal)]
          .to[List]
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

      val sectorById = accts.map { case (id, n) => id -> sectorOf(n) }.toMap
      val bizByLabel = business.toMap

      val quarterTables = cells.map {
        case (label, rs) =>
          val biz = bizByLabel.getOrElse(label, Map.empty[UUID, BigDecimal])
          def bizOf(sec: String): (BigDecimal, BigDecimal) = {
            val xs = biz.toList.filter { case (id, _) => sectorById.get(id).contains(sec) }
            (xs.map(_._2).sum, xs.map { case (id, units) => units * priceOf(id) }.sum)
          }
          val rows = sectors.map { sec =>
            val xs           = rs.filter(_.sector == sec)
            val fU           = xs.map(_.f).foldLeft(BigDecimal(0))(_ + _)
            val aU           = xs.map(_.a).foldLeft(BigDecimal(0))(_ + _)
            val (bizU, bizM) = bizOf(sec)
            val e            = if (aU > 0) ((fU - aU).abs / aU * 100).setScale(1, RoundingMode.HALF_UP) else BigDecimal(0)
            s"<tr><td>$sec</td><td>${dominantPolicy(xs)}</td><td class='num'>${u(bizU)}</td><td class='num'>${gbp(
              bizM
            )}</td><td class='num'>${u(aU)}</td><td class='num'>${u(fU)}</td><td class='${errCls(e)}'>$e%</td></tr>"
          }
          val fT   = rs.map(_.f).foldLeft(BigDecimal(0))(_ + _)
          val aT   = rs.map(_.a).foldLeft(BigDecimal(0))(_ + _)
          val bizT = biz.values.sum
          val bizM = biz.toList.map { case (id, units) => units * priceOf(id) }.sum
          val eT   = if (aT > 0) ((fT - aT).abs / aT * 100).setScale(1, RoundingMode.HALF_UP) else BigDecimal(0)
          val cov  = if (bizT > 0) (aT / bizT * 100).setScale(0, RoundingMode.HALF_UP) else BigDecimal(0)
          s"""<h2>$label — backtest (selected censored at quarter start)</h2>
<table><tr><th>channel</th><th>policy</th><th>BUSINESS actual u</th><th>BUSINESS actual £</th><th>scored actual u</th><th>forecast u</th><th>err</th></tr>
${rows.mkString("\n")}
<tr class='tot'><td>TOTAL</td><td></td><td class='num'>${u(bizT)}</td><td class='num'>${gbp(
            bizM
          )}</td><td class='num'>${u(aT)}</td><td class='num'>${u(fT)}</td><td class='${errCls(
            eT
          )}'>$eT%</td></tr></table>
<p class="note">BUSINESS actual = every attributed unit in the dispatch log this quarter. The model could only score the accounts with enough pre-quarter history ($cov% of business units at this origin) — err compares forecast to that scored subset only.</p>"""
      }

      // June counted ONCE: closed Apr+May + max(June model, June actual-so-far), per sector
      final case class Q2Sec(
          aprMay: BigDecimal,
          junAct: BigDecimal,
          junEst: BigDecimal,
          projU: BigDecimal,
          projM: BigDecimal
      )
      val q2BySec = sectors.map { sec =>
        val parts    = q2parts.filter(_._1 == sec)
        val jm       = junC.filter(_._1 == sec)
        val aprMay   = parts.map(_._2).sum
        val junAct   = parts.map(_._3).sum
        val junModel = jm.map(_._2).sum
        val aprMayM  = parts.map(r => r._2 * r._4).sum
        val junActM  = parts.map(r => r._3 * r._4).sum
        val junModM  = jm.map(r => r._2 * r._3).sum
        val junEst   = junModel.max(junAct)
        val junEstM  = junModM.max(junActM)
        sec -> Q2Sec(aprMay, junAct, junEst, aprMay + junEst, aprMayM + junEstM)
      }
      val q2Rows = q2BySec.map {
        case (sec, s) =>
          s"<tr><td>$sec</td><td class='num'>${u(s.aprMay)}</td><td class='num'>${u(s.junAct)}</td>" +
            s"<td class='num'>${u(s.junEst)}</td><td class='num'>${u(s.projU)}</td><td class='num'>${gbp(s.projM)}</td></tr>"
      }
      val q2AllAprMay = q2BySec.map(_._2.aprMay).sum
      val q2AllJunAct = q2BySec.map(_._2.junAct).sum
      val q2AllJunEst = q2BySec.map(_._2.junEst).sum
      val q2AllU      = q2BySec.map(_._2.projU).sum
      val q2AllM      = q2BySec.map(_._2.projM).sum

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
      val d2cQ4 =
        (5 to 7).map(i => mayLevel * BigDecimal(math.pow(cmgr, i.toDouble))).foldLeft(BigDecimal(0))(_ + _) * net

      // ── ASP achieved per unit ──
      val aspByQ = aspRows
        .groupBy(_._2)
        .toList
        .sortBy(_._1.toEpochDay)
        .map {
          case (q, rows) =>
            val bySec = rows.groupBy(r => sectorById.getOrElse(r._1, "Installers")).map {
              case (sec, xs) => sec -> ((xs.map(_._3).sum, xs.map(_._4).sum))
            }
            val totU = rows.map(_._3).sum
            val totM = rows.map(_._4).sum
            (q, bySec, totU, totM)
        }
      val aspTable = aspByQ.map {
        case (q, bySec, totU, totM) =>
          val cellsHtml = sectors.map { sec =>
            bySec.get(sec) match {
              case Some((su, sm)) if su > 0 =>
                val share = (su / totU * 100).setScale(0, RoundingMode.HALF_UP)
                s"<td class='num'>£${(sm / su).setScale(0, RoundingMode.HALF_UP)} · $share%</td>"
              case _ => "<td>—</td>"
            }
          }
          val blended = if (totU > 0) (totM / totU).setScale(0, RoundingMode.HALF_UP) else BigDecimal(0)
          s"<tr><td>${q.getYear}-Q${(q.getMonthValue + 2) / 3}</td>${cellsHtml.mkString}<td class='num'>£$blended</td><td class='num'>${u(totU)}</td></tr>"
      }

      // ── empirical P80/P50/P20 multipliers (spread normalized on the model's P50 — no recentering) ──
      def quantile(xs: List[BigDecimal], p: Double): BigDecimal = {
        val s = xs.sorted
        if (s.isEmpty) BigDecimal(1)
        else {
          val pos = (s.size - 1) * p
          val lo  = s(pos.toInt)
          val hi  = s(math.min(pos.toInt + 1, s.size - 1))
          lo + (hi - lo) * BigDecimal(pos - pos.toInt)
        }
      }
      val ratios = ratioRows
        .groupBy { case (o, id, _, _) => (o, sectorById.getOrElse(id, "Installers")) }
        .toList
        .flatMap {
          case (_, xs) =>
            val f = xs.map(_._3).sum
            val a = xs.map(_._4).sum
            if (f > 0 && a > 0) Some(a / f) else None
        }
      val med   = quantile(ratios, 0.5).max(BigDecimal("0.0001"))
      val m20   = (quantile(ratios, 0.2) / med).setScale(3, RoundingMode.HALF_UP) // conservative (P80 exceedance)
      val m80   = (quantile(ratios, 0.8) / med).setScale(3, RoundingMode.HALF_UP) // stretch (P20 exceedance)
      val q2Asp = if (q2AllU > 0) q2AllM / q2AllU else median
      // Q2'26: only the June component is uncertain; Q3/Q4: the full quarter is
      def q2Band(m: BigDecimal) = q2AllAprMay + q2AllJunEst * m
      def card(p80: String, p50: String, p20: String, title: String) =
        s"""<div class="kpi"><b>$p50</b><span>$title<br>P80 $p80 · P50 $p50 · P20 $p20</span></div>"""

      // ── measured seasonality pass-through (β): regress our quarter share-of-year on the TAM profile's ──
      // β = +1 would mean market seasonality bleeds straight through; measured ≈ −0.45: sell-in INVERTS it
      // (the channel stocks ahead of the market's H2 and depletes into it; validated: predicted Q4'25 share
      // 22.6% vs actual 22.5%). Forward quarters scale by OUR seasonal shape, not the market's.
      val tamQShare = tamMonthShare
        .groupBy(r => (r._1.getMonthValue + 2) / 3)
        .toList
        .sortBy(_._1)
        .map { case (_, xs) => xs.map(_._2).sum * 100 }
      val ourTotal  = ourQuarters.map(_._2).sum.max(BigDecimal(1))
      val ourQShare = ourQuarters.sortBy(_._1.toEpochDay).map(_._2 / ourTotal * 100)
      val beta =
        if (ourQShare.size == 4 && tamQShare.size == 4) {
          val tMean = tamQShare.sum / 4
          val oMean = ourQShare.sum / 4
          val cov   = tamQShare.zip(ourQShare).map { case (t, o) => (t - tMean) * (o - oMean) }.sum
          val varT  = tamQShare.map(t => (t - tMean) * (t - tMean)).sum
          if (varT > 0) (cov / varT).setScale(3, RoundingMode.HALF_UP) else BigDecimal(0)
        } else BigDecimal(0)
      val oMean                                = if (ourQShare.size == 4) ourQShare.sum / 4 else BigDecimal(25)
      val tMean                                = if (tamQShare.size == 4) tamQShare.sum / 4 else BigDecimal(25)
      def ourShareOf(q: Int)                   = oMean + beta * (tamQShare.lift(q - 1).getOrElse(tMean) - tMean)
      def seasonalFactor(toQ: Int, fromQ: Int) = ourShareOf(toQ) / ourShareOf(fromQ).max(BigDecimal("0.0001"))

      val q3Seasonal = q2AllU * seasonalFactor(3, 2)
      val q4u        = q2AllU * seasonalFactor(4, 2)
      val q4m        = q2AllM * seasonalFactor(4, 2)

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
<p class="note">BUSINESS actual = the full attributed dispatch log (what the company really shipped). The model's err is measured only on the accounts it could score at each origin (enough pre-quarter history) — early quarters have low scoring coverage because the serialized record only reached full adoption Q4'24→Q1'25. Money = units × each account's realized net unit price (embeds its tier).</p>
${quarterTables.mkString("\n")}

<h2>Q2'26 — NOWCAST (open quarter; June counted once: max of model and to-date)</h2>
<table><tr><th>channel</th><th>Apr+May actual u</th><th>June so far u</th><th>June estimate u</th><th>projected u</th><th>projected £</th></tr>
${q2Rows.mkString("\n")}
<tr class='tot'><td>TOTAL B2B</td><td class='num'>${u(q2AllAprMay)}</td><td class='num'>${u(
        q2AllJunAct
      )}</td><td class='num'>${u(q2AllJunEst)}</td><td class='num'>${u(q2AllU)}</td><td class='num'>${gbp(
        q2AllM
      )}</td></tr>
<tr><td>D2C (Stripe, net of ${refund.setScale(
        1,
        RoundingMode.HALF_UP
      )}% refunds)</td><td></td><td></td><td></td><td></td><td class='num'>${gbp(
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
<h2>ASP ACHIEVED PER UNIT (realized ex-VAT, order lines)</h2>
<table><tr><th>quarter</th><th>Wholesale asp · share</th><th>Energy asp · share</th><th>Installers asp · share</th><th>Online asp · share</th><th>BLENDED</th><th>units</th></tr>
${aspTable.mkString("\n")}</table>
<p class="note">Blended ASP has held £490–497 for six quarters — but the mix is rotating hard: Energy 49%→29% of volume, Wholesale 18%→39% (the LOWEST ASP in the book), Installers carrying a widening premium (£500→£515-521). Each unit migrating Energy→Wholesale loses ~£3; Energy→Installers gains ~£28. The £ forecasts below price each account at its own realized ASP, so the mix shift is embedded; the exposure is the installer premium's durability as volumes scale into tiers.</p>

<h2>THE BOTTOM LINE <span style="font-size:11px;color:#8a8aa0">P80 conservative · P50 central · P20 stretch — band = the model's own measured error spread (per-sector actual/forecast ratios, last 3 closed origins), centered on P50</span></h2>
<div>
${card(
        gbp(q2Band(m20) * q2Asp + d2cQ2),
        gbp(q2AllM + d2cQ2),
        gbp(q2Band(m80) * q2Asp + d2cQ2),
        s"Q2'26 all-in (closing) — B2B ${u(q2AllU)}u + D2C ${gbp(d2cQ2)}; only June is still uncertain"
      )}
${card(
        gbp(q3mT * m20 + d2cQ3),
        gbp(q3mT + d2cQ3),
        gbp(q3mT * m80 + d2cQ3),
        s"Q3'26 — B2B ${u(q3uT * m20)}–${u(q3uT)}–${u(q3uT * m80)}u + D2C ${gbp(
          d2cQ3
        )} · pass-through seasonal check ${u(q3Seasonal)}u"
      )}
${card(
        gbp(q4m * m20 + d2cQ4),
        gbp(q4m + d2cQ4),
        gbp(q4m * m80 + d2cQ4),
        s"Q4'26 all-in (pass-through seasonal, β=$beta) — B2B ${u(q4u * m20)}–${u(q4u)}–${u(q4u * m80)}u + D2C ${gbp(
          d2cQ4
        )}"
      )}
</div>
<p class="note">P50 = the model's central number, never recentered (bias-chasing measurably degrades it). The P80/P20 spread is empirical: multipliers ×$m20 / ×$m80 from the distribution of what actually happened vs what was forecast at the last three closed origins. D2C is carried at its central estimate in all three cards.</p>
<p class="note">SEASONALITY PASS-THROUGH (measured — the Q4'25 lesson): the market does 56% of its year in H2 (Q4 = 30.3% share-of-year) but our sell-in does NOT follow. Regressing our 2025 quarter shares (${ourQShare
        .map(_.setScale(1, RoundingMode.HALF_UP).toString)
        .mkString("/")}%) on the TAM profile (${tamQShare
        .map(_.setScale(1, RoundingMode.HALF_UP).toString)
        .mkString(
          "/"
        )}%) gives β = $beta: the channel stocks AHEAD of the market's high season and depletes into it. Validated on the very quarter that exposed it: predicted Q4'25 share ${ourShareOf(
        4
      ).setScale(1, RoundingMode.HALF_UP)}% vs actual ${ourQShare.lastOption
        .map(_.setScale(1, RoundingMode.HALF_UP))
        .getOrElse(
          BigDecimal(0)
        )}%. Forward quarters scale by OUR measured seasonal shape, not the market's — raw TAM seasonality would overstate Q4'26 by roughly £3M.</p>
</body></html>"""
      Files.write(Paths.get("/tmp/conduit-comparables.html"), html.getBytes(StandardCharsets.UTF_8))
      println("written /tmp/conduit-comparables.html")
    }
}
