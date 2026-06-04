package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Forecast accuracy scoring (doc 12 §9). Holds each owner's estimate vs reality per (forecaster, company,
// variant, period, basis): error (actual − forecast, signed), bias (= error here, accumulated to systematic
// over/under elsewhere), MAPE (|error| / actual, the metric the 20%-margin Volex discipline rides on —
// forecasting guide §4). Reconstructed from the append-only forecast_entry history + dispatch/activation
// actuals, so it is replayable. Scored against sell-in (order forecasting) and v3 sell-through (end demand).
final class AccuracyScorer[F[_]: Async](xa: Transactor[F]) {

  // The 20% margin-of-error threshold (the Volex constraint) — a MAPE at or below this is "in discipline".
  val MarginThreshold: BigDecimal = BigDecimal("0.20")

  def score(company: UUID, period: LocalDate, scenario: UUID, basis: String): F[Int] =
    (for {
      forecasts <- forecastByVariant(company, period, scenario)
      actuals <-
        (if (basis == "sell_through") sellThroughByVariant(company, period) else sellInByVariant(company, period))
      _ <- sql"""DELETE FROM forecast_accuracy
                 WHERE company_id = $company AND period_month = $period AND scenario_id = $scenario
                   AND actual_basis = $basis AND model_version IS NULL""".update.run
      n <- forecasts.traverse {
        case (forecaster, variant, fq) =>
          val actual = actuals.getOrElse(variant, 0)
          upsert(forecaster, company, variant, period, scenario, basis, fq, actual)
      }
    } yield n.sum).transact(xa)

  private def forecastByVariant(
      company: UUID,
      period: LocalDate,
      scenario: UUID
  ): ConnectionIO[List[(Option[UUID], UUID, Int)]] =
    sql"""SELECT forecaster_user_id, product_variant_id, qty FROM forecast_entry
          WHERE branch_company_id = $company AND period_month = $period AND scenario_id = $scenario
            AND source = 'manual' AND superseded_by IS NULL AND product_variant_id IS NOT NULL"""
      .query[(Option[UUID], UUID, Int)]
      .to[List]

  private def sellInByVariant(company: UUID, period: LocalDate): ConnectionIO[Map[UUID, Int]] =
    sql"""SELECT ol.product_variant_id, COALESCE(SUM(dl.qty),0)::int
          FROM dispatch d JOIN "order" o ON o.id = d.order_id JOIN dispatch_line dl ON dl.dispatch_id = d.id
            JOIN order_line ol ON ol.id = dl.order_line_id
          WHERE o.sold_to_party_id = $company AND date_trunc('month', d.date)::date = $period
          GROUP BY ol.product_variant_id""".query[(UUID, Int)].to[List].map(_.toMap)

  private def sellThroughByVariant(company: UUID, period: LocalDate): ConnectionIO[Map[UUID, Int]] =
    sql"""SELECT s.product_variant_id, COUNT(*)::int
          FROM activation a JOIN serial_unit s ON s.serial_no = a.serial
          WHERE s.company_id = $company AND s.generation = 'v3' AND date_trunc('month', a.activated_at)::date = $period
          GROUP BY s.product_variant_id""".query[(UUID, Int)].to[List].map(_.toMap)

  private def upsert(
      forecaster: Option[UUID],
      company: UUID,
      variant: UUID,
      period: LocalDate,
      scenario: UUID,
      basis: String,
      forecast: Int,
      actual: Int
  ): ConnectionIO[Int] = {
    val error = actual - forecast
    val ape =
      if (actual == 0) BigDecimal(0) else (BigDecimal(error.abs) / BigDecimal(actual)).setScale(4, RoundingMode.HALF_UP)
    sql"""INSERT INTO forecast_accuracy
            (forecaster_user_id, company_id, product_variant_id, period_month, scenario_id, actual_basis,
             forecast_qty, actual_qty, error, bias, mape)
          VALUES ($forecaster, $company, $variant, $period, $scenario, $basis, $forecast, $actual, $error,
             ${BigDecimal(error)}, $ape)""".update.run
  }
}
