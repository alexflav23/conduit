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

// The honest evaluation, three views (doc 26 §5):
//   BACKTEST — at every eval origin the policy is selected from evidence strictly before it and scored
//   one-step-ahead; HubSpot channels and the MRPeasy account population (aggregated) report side by side.
//   NOWCAST — the OPEN quarter is the product: closed months are actuals, the rest is the policy forecast.
//   FORWARD — Q3/Q4'26 predictions censored at today, with the UK BEV TAM trajectory alongside (the demand
//   ceiling the statistics can't see).
object PolicyTournamentReport extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val evalOrigins = List(
    LocalDate.of(2024, 7, 1),
    LocalDate.of(2024, 10, 1),
    LocalDate.of(2025, 1, 1),
    LocalDate.of(2025, 4, 1),
    LocalDate.of(2025, 7, 1),
    LocalDate.of(2025, 10, 1),
    LocalDate.of(2026, 1, 1),
    LocalDate.of(2026, 4, 1)
  )
  private val nowcastOrigin = LocalDate.of(2026, 4, 1)
  private val forwardOrigin = LocalDate.of(2026, 7, 1) // horizon 6: Q3'26 = months 0-2, Q4'26 = months 3-5

  private val channels: ConnectionIO[List[(UUID, String)]] =
    sql"SELECT id, display_name FROM party WHERE display_name LIKE 'CH: %' ORDER BY display_name"
      .query[(UUID, String)]
      .to[List]

  private def mrpAccountsAt(origin: LocalDate): ConnectionIO[List[UUID]] =
    sql"""SELECT DISTINCT p.company_id
          FROM forecast_run_prediction p
          JOIN forecast_run r ON r.id = p.run_id
          JOIN party pa ON pa.id = p.company_id
          WHERE r.origin_month = $origin AND pa.display_name LIKE 'MRP: %'"""
      .query[UUID]
      .to[List]

  private def scoredRows(company: UUID, origin: LocalDate): ConnectionIO[Map[String, (BigDecimal, BigDecimal)]] =
    sql"""SELECT model_key, SUM(forecast_qty), SUM(actual_qty)
          FROM model_accuracy WHERE company_id = $company AND origin_month = $origin
          GROUP BY model_key"""
      .query[(String, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map { case (k, f, a) => k -> ((f, a)) }.toMap)

  private def predRows(
      company: UUID,
      origin: LocalDate,
      from: Int,
      until: Int
  ): ConnectionIO[Map[String, BigDecimal]] =
    sql"""SELECT r.model_key, SUM(p.qty)
          FROM forecast_run_prediction p JOIN forecast_run r ON r.id = p.run_id
          WHERE p.company_id = $company AND r.origin_month = $origin
            AND p.period_month >= ${origin.plusMonths(from.toLong)}
            AND p.period_month < ${origin.plusMonths(until.toLong)}
          GROUP BY r.model_key"""
      .query[(String, BigDecimal)]
      .to[List]
      .map(_.toMap)

  private def blend(weights: Map[String, BigDecimal], rows: Map[String, BigDecimal]): BigDecimal =
    weights.toList.map { case (k, w) => rows.getOrElse(k, BigDecimal(0)) * w }.foldLeft(BigDecimal(0))(_ + _)

  private def evalOne(company: UUID, origin: LocalDate): IO[Option[(String, BigDecimal, BigDecimal)]] =
    (PolicyRepo.evidence(company, origin).transact(xa), scoredRows(company, origin).transact(xa)).mapN {
      (evidence, rows) =>
        rows.values.headOption.map {
          case (_, actual) =>
            val policy = PolicySelector.select(evidence)
            (policy.key, blend(policy.weights, rows.view.mapValues(_._1).toMap), actual)
        }
    }

  // closed months come from the quarter origin's scoring; the REMAINING month is forecast from the freshest
  // month boundary (June from data through May) — a nowcast that ignores the quarter's own closed months is
  // two months stale by construction
  private val freshOrigin = LocalDate.of(2026, 6, 1)

  private def nowcastOne(company: UUID): IO[Option[(BigDecimal, BigDecimal)]] =
    (
      PolicyRepo.evidence(company, freshOrigin).transact(xa),
      scoredRows(company, nowcastOrigin).transact(xa),
      predRows(company, freshOrigin, 0, 1).transact(xa)
    ).mapN { (evidence, scored, future) =>
      scored.values.headOption.map {
        case (_, actualClosed) =>
          (actualClosed, blend(PolicySelector.select(evidence).weights, future))
      }
    }

  private def forwardOne(company: UUID, origin: LocalDate, from: Int, until: Int): IO[BigDecimal] =
    (PolicyRepo.evidence(company, origin).transact(xa), predRows(company, origin, from, until).transact(xa)).mapN {
      (evidence, rows) => blend(PolicySelector.select(evidence).weights, rows)
    }

  private def pct(err: BigDecimal, base: BigDecimal): String =
    if (base <= 0) "    n/a"
    else f"${(err / base * 100).setScale(1, RoundingMode.HALF_UP)}%6s%%"

  private def units(x: BigDecimal): String = x.setScale(0, RoundingMode.HALF_UP).toString + " units"

  override def run: IO[Unit] =
    for {
      chs <- channels.transact(xa)
      chRows <- chs.traverse {
        case (company, name) =>
          evalOrigins.traverse(o => evalOne(company, o)).map(evals => (name.stripPrefix("CH: "), evals))
      }
      mrpPerOrigin <- evalOrigins.traverse(o =>
        mrpAccountsAt(o)
          .transact(xa)
          .flatMap(_.traverse(a => evalOne(a, o)).map(_.flatten))
          .map(rs =>
            (
              rs.size,
              rs.map { case (_, f, a) => (f - a).abs }.foldLeft(BigDecimal(0))(_ + _),
              rs.map(_._3).foldLeft(BigDecimal(0))(_ + _),
              rs.map(_._2).foldLeft(BigDecimal(0))(_ + _)
            )
          )
      )
      nowAccounts <- mrpAccountsAt(nowcastOrigin).transact(xa)
      nowcasts    <- nowAccounts.traverse(nowcastOne).map(_.flatten)
      fwd <- List(("Q3'26", 0, 3), ("Q4'26", 3, 6)).traverse {
        case (label, from, until) =>
          mrpAccountsAt(forwardOrigin)
            .transact(xa)
            .flatMap(_.traverse(forwardOne(_, forwardOrigin, from, until)))
            .map(xs => (label, xs.foldLeft(BigDecimal(0))(_ + _)))
      }
      tam <-
        sql"""SELECT period_month, value FROM exogenous_series
                   WHERE series_key = 'uk_bev_registrations' ORDER BY period_month"""
          .query[(LocalDate, BigDecimal)]
          .to[List]
          .transact(xa)
    } yield {
      val header = f"${"series"}%-34s" + evalOrigins.map(o => f"${o.toString.take(7)}%8s").mkString
      val chLines = chRows.map {
        case (name, evals) =>
          f"$name%-34s" + evals.map {
            case Some((_, f, a)) => pct((f - a).abs, a)
            case None            => "       -"
          }.mkString
      }
      val mrpWape = f"${"MRP B2B per-account WAPE"}%-34s" + mrpPerOrigin.map {
        case (n, errs, acts, _) => if (n == 0) "       -" else pct(errs, acts)
      }.mkString
      val mrpTotal = f"${"MRP B2B total-level"}%-34s" + mrpPerOrigin.map {
        case (n, _, acts, tot) => if (n == 0) "       -" else pct((tot - acts).abs, acts)
      }.mkString
      val counts = f"${"MRP B2B accounts evaluated"}%-34s" + mrpPerOrigin.map {
        case (n, _, _, _) => f"$n%8d"
      }.mkString
      val nowActual   = nowcasts.map(_._1).foldLeft(BigDecimal(0))(_ + _)
      val nowForecast = nowcasts.map(_._2).foldLeft(BigDecimal(0))(_ + _)
      println(((header +: chLines) ++ List(mrpWape, mrpTotal, counts)).mkString("\n"))
      println(
        s"\nNOWCAST Q2'26 (MRP B2B): Apr+May actual ${units(nowActual)} + June model ${units(nowForecast)}" +
          s" = projected quarter ${units(nowActual + nowForecast)} across ${nowcasts.size} accounts"
      )
      fwd.foreach { case (label, total) => println(s"FORWARD $label (MRP B2B): ${units(total)}") }
      println(
        "\nTAM — UK BEV registrations (SMMT): " +
          tam.map { case (m, v) => s"${m.toString.take(7)}=${v.setScale(0, RoundingMode.HALF_UP)}" }.mkString(" ")
      )
    }
}
