package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.forecast.DemandSeriesRepo
import com.hypervolt.conduit.forecast.PolicyRepo
import com.hypervolt.conduit.forecast.PolicySelector
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

// The engine-side tournament, run blind on the real HubSpot history: for every channel series the policy is
// selected from evidence strictly before the origin (all prior origins), then applied to the censored history
// and scored against the real Q2'25 — the same protocol the report-side prototype used, now through the
// production PolicySelector so the numbers must reproduce.
object PolicyTournamentReport extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val origin = LocalDate.of(2025, 4, 1)

  private val channelKeys: ConnectionIO[List[(UUID, String, UUID)]] =
    sql"""SELECT DISTINCT o.sold_to_party_id, p.display_name, ol.product_variant_id
          FROM order_line ol
          JOIN "order" o ON o.id = ol.order_id
          JOIN party p ON p.id = o.sold_to_party_id
          WHERE p.display_name LIKE 'CH: %'"""
      .query[(UUID, String, UUID)]
      .to[List]

  override def run: IO[Unit] =
    channelKeys.transact(xa).flatMap { keys =>
      keys
        .traverse {
          case (company, name, variant) =>
            (
              PolicyRepo.evidence(company, origin).transact(xa),
              DemandSeriesRepo.history(company, variant, origin).transact(xa),
              DemandSeriesRepo.actuals(company, variant, origin, origin.plusMonths(3)).transact(xa)
            ).mapN {
              case (evidence, hist, actuals) =>
                val policy   = PolicySelector.select(evidence)
                val forecast = policy.predict(hist, 3).foldLeft(BigDecimal(0))(_ + _)
                val actual   = actuals.values.foldLeft(BigDecimal(0))(_ + _)
                val err =
                  if (actual > 0) ((forecast - actual).abs / actual * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
                  else BigDecimal(0)
                (name.stripPrefix("CH: "), policy.key, forecast, actual, err, hist.nonEmpty)
            }
        }
        .flatMap { rows =>
          val (trained, untrained) = rows.sortBy(-_._4).partition(_._6)
          val header               = f"${"channel"}%-30s ${"policy"}%-28s ${"forecast £"}%12s ${"actual £"}%12s ${"err%"}%7s"
          def line(r: (String, String, BigDecimal, BigDecimal, BigDecimal, Boolean)) =
            f"${r._1}%-30s ${r._2}%-28s ${r._3.setScale(0, BigDecimal.RoundingMode.HALF_UP)}%12s ${r._4
              .setScale(0, BigDecimal.RoundingMode.HALF_UP)}%12s ${r._5}%6s%%"
          val totF = trained.map(_._3).foldLeft(BigDecimal(0))(_ + _)
          val totA = trained.map(_._4).foldLeft(BigDecimal(0))(_ + _)
          val wape = trained
            .map(r => (r._3 - r._4).abs)
            .foldLeft(BigDecimal(0))(_ + _) / totA.max(1) * 100
          IO.println(
            (header +: trained.map(line)).mkString("\n") +
              f"\nTRAINED forecast ${totF.setScale(0, BigDecimal.RoundingMode.HALF_UP)} vs actual ${totA
                .setScale(0, BigDecimal.RoundingMode.HALF_UP)}  |  per-channel WAPE ${wape
                .setScale(1, BigDecimal.RoundingMode.HALF_UP)}%%" +
              (if (untrained.isEmpty) ""
               else
                 "\nUNTRAINED (no history at origin — excluded from the WAPE line):\n" +
                   untrained.map(line).mkString("\n"))
          )
        }
    }
}
