package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

// The censored demand series (doc 26 §5 honesty rule 1): everything is computed from rows strictly BEFORE the
// origin — the world as the forecaster would have seen it. Demand basis = ordered units (sell-in); the depletion
// context (shelf, velocity) comes from the serial/activation log, equally censored.
object DemandSeriesRepo {

  // (company, variant) pairs with enough history to be forecastable as-of the origin (doc 26 §1: data-driven).
  def forecastableKeys(origin: LocalDate, minOrders: Int): ConnectionIO[List[(UUID, UUID)]] =
    sql"""SELECT o.sold_to_party_id, ol.product_variant_id
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE o.created_at < $origin AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
          GROUP BY o.sold_to_party_id, ol.product_variant_id
          HAVING COUNT(DISTINCT o.id) >= $minOrders"""
      .query[(UUID, UUID)]
      .to[List]

  // The account×SKU monthly unit series, censored at the origin, zero-filled and contiguous from first demand.
  def history(company: UUID, variant: UUID, origin: LocalDate): ConnectionIO[DemandHistory] =
    sql"""SELECT date_trunc('month', o.created_at)::date, SUM(ol.qty)::numeric
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
            AND o.created_at < $origin AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
          GROUP BY 1 ORDER BY 1"""
      .query[(LocalDate, BigDecimal)]
      .to[List]
      .flatMap(raw =>
        depletionContext(company, variant, origin).flatMap(ctx =>
          dealContext(company, origin).map {
            case (book, funnel, momentum) => zeroFill(raw, origin, ctx, book, funnel, momentum)
          }
        )
      )

  // The censored deal view behind the order-book and retail-funnel models (doc 26 §4a): closures at-or-after
  // the origin are INVISIBLE — the deal appears open, exactly as the forecaster would have seen it. Series map
  // to a pipeline through the channel-party convention ('CH: <pipeline>'); accounts without one carry no context.
  private def dealContext(
      company: UUID,
      origin: LocalDate
  ): ConnectionIO[
    ((Option[BigDecimal], Option[BigDecimal], Option[BigDecimal]), Option[BigDecimal], Option[BigDecimal])
  ] =
    sql"SELECT display_name FROM party WHERE id = $company AND display_name LIKE 'CH: %'"
      .query[String]
      .option
      .flatMap {
        case None =>
          (
            (Option.empty[BigDecimal], Option.empty[BigDecimal], Option.empty[BigDecimal]),
            Option.empty[BigDecimal],
            Option.empty[BigDecimal]
          ).pure[ConnectionIO]
        case Some(name) =>
          sql"""SELECT created_at,
                       CASE WHEN is_closed AND closed_at < $origin THEN closed_at END,
                       is_won AND is_closed AND closed_at < $origin,
                       amount,
                       payment_method
                FROM deal_snapshot WHERE pipeline = ${name.stripPrefix("CH: ")} AND created_at < $origin"""
            .query[DealRow]
            .to[List]
            .map(deals =>
              (
                OrderBookCalc.context(deals, origin),
                RetailFunnelCalc.expectedQuarter(deals, origin),
                RetailFunnelCalc.expectedQuarter(deals, origin, momentum = true)
              )
            )
      }

  // Shelf stock + activation velocity as-of the origin (the doc 26 §4 edge), from the serial/activation log.
  // Velocity = activations over the trailing 6 months / 6.
  private def depletionContext(
      company: UUID,
      variant: UUID,
      origin: LocalDate
  ): ConnectionIO[(Option[BigDecimal], Option[BigDecimal])] =
    sql"""SELECT
            (SELECT COUNT(*)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin
               AND (su.activated_at IS NULL OR su.activated_at >= $origin)),
            (SELECT COUNT(*)::numeric / 6.0 FROM serial_unit su
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND su.activated_at >= ${origin.minusMonths(6)} AND su.activated_at < $origin)"""
      .query[(Option[BigDecimal], Option[BigDecimal])]
      .unique
      .map { case (shelf, vel) => (shelf.filter(_ > 0), vel.filter(_ > 0)) }

  private def zeroFill(
      raw: List[(LocalDate, BigDecimal)],
      origin: LocalDate,
      ctx: (Option[BigDecimal], Option[BigDecimal]),
      book: (Option[BigDecimal], Option[BigDecimal], Option[BigDecimal]),
      funnel: Option[BigDecimal],
      momentum: Option[BigDecimal]
  ): DemandHistory =
    raw.headOption match {
      case None =>
        DemandHistory(Vector.empty, Vector.empty, ctx._1, ctx._2, book._1, book._2, book._3, funnel, momentum)
      case Some((first, _)) =>
        val byMonth = raw.toMap
        val months = Iterator
          .iterate(first.withDayOfMonth(1))(_.plusMonths(1))
          .takeWhile(_.isBefore(origin.withDayOfMonth(1)))
          .toVector
        DemandHistory(
          months,
          months.map(m => byMonth.getOrElse(m, BigDecimal(0))),
          ctx._1,
          ctx._2,
          book._1,
          book._2,
          book._3,
          funnel,
          momentum
        )
    }

  // Actuals for scoring a horizon (the same demand definition, after the origin).
  def actuals(
      company: UUID,
      variant: UUID,
      from: LocalDate,
      until: LocalDate
  ): ConnectionIO[Map[LocalDate, BigDecimal]] =
    sql"""SELECT date_trunc('month', o.created_at)::date, SUM(ol.qty)::numeric
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
            AND o.created_at >= $from AND o.created_at < $until
            AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
          GROUP BY 1"""
      .query[(LocalDate, BigDecimal)]
      .to[List]
      .map(_.toMap)
}

object ForecastRunRepo {

  def insertRun(
      origin: LocalDate,
      horizon: Int,
      model: DemandModel,
      purpose: String
  ): ConnectionIO[Option[UUID]] =
    sql"""INSERT INTO forecast_run (origin_month, horizon_months, model_key, model_version, purpose)
          VALUES ($origin, $horizon, ${model.key}, ${model.version}, $purpose)
          ON CONFLICT (origin_month, model_key, model_version, purpose) DO NOTHING
          RETURNING id""".query[UUID].option

  def insertPrediction(
      runId: UUID,
      company: UUID,
      variant: UUID,
      period: LocalDate,
      qty: BigDecimal
  ): ConnectionIO[Int] =
    sql"""INSERT INTO forecast_run_prediction (run_id, company_id, product_variant_id, period_month, qty)
          VALUES ($runId, $company, $variant, $period, $qty)
          ON CONFLICT DO NOTHING""".update.run

  def score(
      runId: UUID,
      company: UUID,
      variant: UUID,
      modelKey: String,
      origin: LocalDate,
      period: LocalDate,
      horizon: Int,
      forecast: BigDecimal,
      actual: BigDecimal
  ): ConnectionIO[Int] =
    sql"""INSERT INTO model_accuracy
            (run_id, company_id, product_variant_id, model_key, origin_month, period_month, horizon,
             forecast_qty, actual_qty, abs_error)
          VALUES ($runId, $company, $variant, $modelKey, $origin, $period, $horizon,
             $forecast, $actual, ${(forecast - actual).abs})
          ON CONFLICT DO NOTHING""".update.run

  // The champion (doc 26 §5): pure argmin over the error ledger — DERIVED, never stored. The censored variant is
  // the honest one for any forecast made at `origin`: selection evidence must PREDATE the origin (the same
  // no-leakage rule the predictions obey — otherwise the scored quarter votes for its own champion).
  def champion(company: UUID): ConnectionIO[Option[(String, BigDecimal)]] =
    championBefore(company, LocalDate.of(9999, 1, 1))

  def championBefore(company: UUID, origin: LocalDate): ConnectionIO[Option[(String, BigDecimal)]] =
    sql"""SELECT model_key, SUM(abs_error) / GREATEST(SUM(actual_qty), 1) AS wape
          FROM model_accuracy WHERE company_id = $company AND origin_month < $origin
          GROUP BY model_key ORDER BY wape ASC, model_key ASC LIMIT 1"""
      .query[(String, BigDecimal)]
      .option

  def leaderboard(company: UUID): ConnectionIO[List[(String, BigDecimal)]] =
    sql"""SELECT model_key, SUM(abs_error) / GREATEST(SUM(actual_qty), 1) AS wape
          FROM model_accuracy WHERE company_id = $company
          GROUP BY model_key ORDER BY wape ASC, model_key ASC"""
      .query[(String, BigDecimal)]
      .to[List]
}

// The rolling-origin learning loop (doc 26 §5). For an origin: censored snapshot → every registry model predicts
// the horizon → IMMUTABLE run + predictions → scored against actuals as they exist → the champion is whatever the
// error ledger says. Idempotent at every level (run UNIQUE, predictions/scores ON CONFLICT) — re-running an origin
// is a no-op, exactly like event redelivery.
final class BacktestEngine[F[_]: Async](xa: Transactor[F]) {

  def runOrigin(origin: LocalDate, horizonMonths: Int, minOrders: Int = 3): F[Int] =
    DemandSeriesRepo
      .forecastableKeys(origin, minOrders)
      .transact(xa)
      .flatMap(keys => DemandModel.registry.traverse(m => runModel(m, origin, horizonMonths, keys)).map(_.sum))

  private def runModel(model: DemandModel, origin: LocalDate, horizon: Int, keys: List[(UUID, UUID)]): F[Int] = {
    val program = ForecastRunRepo.insertRun(origin, horizon, model, "backtest").flatMap {
      case None => 0.pure[ConnectionIO] // this (origin, model) already ran — idempotent
      case Some(runId) =>
        keys
          .traverse {
            case (company, variant) =>
              DemandSeriesRepo.history(company, variant, origin).flatMap { h =>
                val preds = model.predict(h, horizon)
                preds.zipWithIndex.toList.traverse_ {
                  case (qty, i) =>
                    ForecastRunRepo.insertPrediction(runId, company, variant, origin.plusMonths(i.toLong), qty)
                }
              }
          }
          .as(keys.size)
    }
    program.transact(xa)
  }

  // Score every unscored prediction of runs whose horizon has (partly) elapsed — called as quarters close;
  // adding actuals automatically extends the learning (doc 26 §5).
  def scoreOrigin(origin: LocalDate, asOf: LocalDate): F[Int] = {
    val program = runsAt(origin).flatMap(
      _.traverse {
        case (runId, modelKey) =>
          predictionsOf(runId).flatMap(
            _.filter { case (_, _, period, _) => period.plusMonths(1).compareTo(asOf) <= 0 }
              .traverse {
                case (company, variant, period, forecast) =>
                  DemandSeriesRepo
                    .actuals(company, variant, period, period.plusMonths(1))
                    .flatMap { act =>
                      val actual  = act.getOrElse(period, BigDecimal(0))
                      val horizon = java.time.Period.between(origin, period).toTotalMonths.toInt + 1
                      ForecastRunRepo
                        .score(runId, company, variant, modelKey, origin, period, horizon, forecast, actual)
                    }
              }
              .map(_.sum)
          )
      }.map(_.sum)
    )
    program.transact(xa)
  }

  def champion(company: UUID): F[Option[(String, BigDecimal)]] = ForecastRunRepo.champion(company).transact(xa)

  private def runsAt(origin: LocalDate): ConnectionIO[List[(UUID, String)]] =
    sql"SELECT id, model_key FROM forecast_run WHERE origin_month = $origin AND purpose = 'backtest'"
      .query[(UUID, String)]
      .to[List]

  private def predictionsOf(runId: UUID): ConnectionIO[List[(UUID, UUID, LocalDate, BigDecimal)]] =
    sql"""SELECT company_id, product_variant_id, period_month, qty
          FROM forecast_run_prediction WHERE run_id = $runId"""
      .query[(UUID, UUID, LocalDate, BigDecimal)]
      .to[List]
}
