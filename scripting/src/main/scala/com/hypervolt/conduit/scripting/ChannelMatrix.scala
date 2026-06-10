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
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The H6Q channel matrix (doc 12 / doc 20-H6Q): channel × quarter, UNITS and £, Q2'25 → Q3'26. Money is a
// projection — units × the account's REALIZED net unit price (the price the account actually pays, which
// embeds its tier; TierResolver supersedes per account as governed agreements are migrated — doc 12 §8.3,
// H6Q never owns money). Past quarters show policy forecast vs actual; open/future quarters show the
// published live forecast.
object ChannelMatrix extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val evalOrigins =
    List(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 1), LocalDate.of(2025, 10, 1), LocalDate.of(2026, 1, 1))

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

  // the account's realized net unit price: trailing average over priced lines; the population median otherwise
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

  override def run: IO[Unit] =
    for {
      accts  <- accounts.transact(xa)
      priceM <- prices.transact(xa)
      median = {
        val ps = priceM.values.toList.sorted
        if (ps.isEmpty) BigDecimal(600) else ps(ps.size / 2)
      }
      priceOf = (id: UUID) => priceM.getOrElse(id, median)
      past <- evalOrigins.traverse { o =>
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
                    (sectorOf(name), f, actual, priceOf(id))
                }
              }
          }
          .map(rs => o -> rs.flatten)
      }
      q2a <- accts.traverse {
        case (id, name) =>
          sql"""SELECT COALESCE(count(*), 0)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
                WHERE su.company_id = $id AND COALESCE(d.delivered_at, d.date::timestamptz) >= '2026-04-01'"""
            .query[BigDecimal]
            .unique
            .transact(xa)
            .map(units => (sectorOf(name), units, priceOf(id)))
      }
      q2f <- accts.traverse {
        case (id, name) =>
          fwd(id, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))
            .transact(xa)
            .map(u => (sectorOf(name), u, priceOf(id)))
      }
      q3f <- accts.traverse {
        case (id, name) =>
          fwd(id, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1))
            .transact(xa)
            .map(u => (sectorOf(name), u, priceOf(id)))
      }
    } yield {
      val sectors                  = List("Wholesale/Distribution", "Energy", "Installers", "Online Retail")
      def k(x: BigDecimal): String = (x / 1000).setScale(1, RoundingMode.HALF_UP).toString + "k"
      def fmtPast(o: LocalDate, sector: String): String = {
        val rs  = past.toMap.apply(o).filter(_._1 == sector)
        val fU  = rs.map(_._2).foldLeft(BigDecimal(0))(_ + _)
        val aU  = rs.map(_._3).foldLeft(BigDecimal(0))(_ + _)
        val fM  = rs.map(r => r._2 * r._4).foldLeft(BigDecimal(0))(_ + _)
        val aM  = rs.map(r => r._3 * r._4).foldLeft(BigDecimal(0))(_ + _)
        val err = if (aU > 0) ((fU - aU).abs / aU * 100).setScale(0, RoundingMode.HALF_UP).toString + "%" else "n/a"
        s"${fU.setScale(0, RoundingMode.HALF_UP)}/${aU.setScale(0, RoundingMode.HALF_UP)}u £${k(fM)}/£${k(aM)} ($err)"
      }
      println("channel × quarter — forecast/actual units, forecast/actual £ (realized net price), |err|")
      sectors.foreach { sec =>
        val cols = evalOrigins.map(o => fmtPast(o, sec)).mkString(" | ")
        val a2   = q2a.filter(_._1 == sec)
        val f2   = q2f.filter(_._1 == sec)
        val u2   = a2.map(_._2).sum + f2.map(_._2).sum
        val m2   = a2.map(r => r._2 * r._3).sum + f2.map(r => r._2 * r._3).sum
        val r3   = q3f.filter(_._1 == sec)
        val u3   = r3.map(_._2).sum
        val m3   = r3.map(r => r._2 * r._3).sum
        println(f"$sec%-24s $cols | Q2'26proj ${u2.setScale(0, RoundingMode.HALF_UP)}u £${k(m2)} | Q3'26 ${u3
          .setScale(0, RoundingMode.HALF_UP)}u £${k(m3)}")
      }
    }
}
