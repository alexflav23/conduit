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

// The honest evaluation of the engine tournament: at EVERY eval origin the policy is selected from evidence
// strictly before that origin and scored one-step-ahead on the quarter that followed — a mean over quarters,
// never a single test quarter (optimising one quarter is curve-fitting with extra steps). The policy forecast
// is the weighted blend of the stored per-model predictions at that origin (identical to predicting from the
// censored history, since blending is linear).
object PolicyTournamentReport extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val evalOrigins =
    List(
      LocalDate.of(2024, 7, 1),
      LocalDate.of(2024, 10, 1),
      LocalDate.of(2025, 1, 1),
      LocalDate.of(2025, 4, 1),
      LocalDate.of(2025, 7, 1)
    )

  private val channels: ConnectionIO[List[(UUID, String)]] =
    sql"SELECT id, display_name FROM party WHERE display_name LIKE 'CH: %' ORDER BY display_name"
      .query[(UUID, String)]
      .to[List]

  private def originRows(company: UUID, origin: LocalDate): ConnectionIO[Map[String, (BigDecimal, BigDecimal)]] =
    sql"""SELECT model_key, SUM(forecast_qty), SUM(actual_qty)
          FROM model_accuracy WHERE company_id = $company AND origin_month = $origin
          GROUP BY model_key"""
      .query[(String, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map { case (k, f, a) => k -> ((f, a)) }.toMap)

  private def evalOne(company: UUID, origin: LocalDate): IO[Option[(String, BigDecimal, BigDecimal)]] =
    (PolicyRepo.evidence(company, origin).transact(xa), originRows(company, origin).transact(xa)).mapN {
      (evidence, rows) =>
        rows.values.headOption.map {
          case (_, actual) =>
            val policy = PolicySelector.select(evidence)
            val forecast = policy.weights.toList
              .map { case (k, w) => rows.get(k).map(_._1).getOrElse(BigDecimal(0)) * w }
              .foldLeft(BigDecimal(0))(_ + _)
            (policy.key, forecast, actual)
        }
    }

  private def pct(f: BigDecimal, a: BigDecimal): String =
    if (a <= 0) "    n/a"
    else f"${((f - a).abs / a * 100).setScale(1, RoundingMode.HALF_UP)}%6s%%"

  override def run: IO[Unit] =
    channels.transact(xa).flatMap { chs =>
      chs
        .traverse {
          case (company, name) =>
            evalOrigins
              .traverse(o => evalOne(company, o).map(o -> _))
              .map(evals => (name.stripPrefix("CH: "), evals))
        }
        .flatMap { rows =>
          val header = f"${"channel"}%-30s" + evalOrigins.map(o => f"${o.toString.take(7)}%8s").mkString +
            "   policy @ last origin"
          val lines = rows.map {
            case (name, evals) =>
              val cells = evals.map {
                case (_, Some((_, f, a))) => pct(f, a)
                case (_, None)            => "       -"
              }.mkString
              val policy = evals.last._2.map(_._1).getOrElse("untrained")
              f"$name%-30s$cells   $policy"
          }
          val perOrigin = evalOrigins.zipWithIndex.map {
            case (o, i) =>
              val scored = rows.flatMap(_._2(i)._2)
              val errs   = scored.map { case (_, f, a) => (f - a).abs }.foldLeft(BigDecimal(0))(_ + _)
              val acts   = scored.map(_._3).foldLeft(BigDecimal(0))(_ + _)
              val tot    = scored.map(_._2).foldLeft(BigDecimal(0))(_ + _)
              (o, errs / acts.max(1) * 100, (tot - acts).abs / acts.max(1) * 100)
          }
          val meanWape = perOrigin.map(_._2).foldLeft(BigDecimal(0))(_ + _) / perOrigin.length
          IO.println(
            (header +: lines).mkString("\n") + "\n" +
              perOrigin
                .map {
                  case (o, w, t) =>
                    f"${o.toString.take(7)}: per-channel WAPE ${w.setScale(1, RoundingMode.HALF_UP)}%5s%%   total-level ${t
                      .setScale(1, RoundingMode.HALF_UP)}%5s%%"
                }
                .mkString("\n") +
              f"\nMEAN per-channel WAPE over ${perOrigin.length} quarters: ${meanWape.setScale(1, RoundingMode.HALF_UP)}%%"
          )
        }
    }
}
