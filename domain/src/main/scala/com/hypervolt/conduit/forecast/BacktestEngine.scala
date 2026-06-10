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
  // Two demand sources union here: serialized dispatches (chargers — the ASC-606 basis) and order lines for
  // non-serialized series. Parts and accessories are NOT charger demand (measured: moulded components shipped
  // to a manufacturing partner read as a 39k-unit "order" until classified out).
  def forecastableKeys(origin: LocalDate, minOrders: Int): ConnectionIO[List[(UUID, UUID)]] =
    sql"""SELECT o.sold_to_party_id, ol.product_variant_id
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE o.created_at < $origin AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
            AND pv.product_class NOT IN ('part', 'accessory')
          GROUP BY o.sold_to_party_id, ol.product_variant_id
          HAVING COUNT(DISTINCT o.id) >= $minOrders
          UNION
          SELECT su.company_id, su.product_variant_id
          FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
          WHERE su.company_id IS NOT NULL AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin
          GROUP BY su.company_id, su.product_variant_id
          HAVING COUNT(DISTINCT su.dispatch_id) >= $minOrders"""
      .query[(UUID, UUID)]
      .to[List]

  // The account×SKU monthly unit series, censored at the origin, zero-filled and contiguous from first demand.
  // Serialized series use DISPATCH dates (the business date; MRPeasy order created_at is a record-entry date
  // — measured: a quarter of real shipments read as a demand collapse on order dates); order lines otherwise.
  def history(company: UUID, variant: UUID, origin: LocalDate): ConnectionIO[DemandHistory] =
    monthlySeries(company, variant, origin.atStartOfDay())
      .flatMap(raw =>
        depletionContext(company, variant, origin).flatMap(ctx =>
          dealContext(company, origin).flatMap {
            case (book, funnel, momentum) =>
              mrpBookContext(company, variant, origin)
                .map(zeroFill(raw, origin, ctx, book, funnel, momentum, _))
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
  // Velocity at two windows: trailing 6 months /6 (depletion's smooth rate) and 3 months /3 (the sell-through
  // level — the user's installer steer, measured better pooled in every segment).
  private def depletionContext(
      company: UUID,
      variant: UUID,
      origin: LocalDate
  ): ConnectionIO[(Option[BigDecimal], Option[BigDecimal], Option[BigDecimal])] =
    sql"""SELECT
            (SELECT COUNT(*)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin
               AND (su.activated_at IS NULL OR su.activated_at >= $origin)),
            (SELECT COUNT(*)::numeric / 6.0 FROM serial_unit su
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND su.activated_at >= ${origin.minusMonths(6)} AND su.activated_at < $origin),
            (SELECT COUNT(*)::numeric / 3.0 FROM serial_unit su
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND su.activated_at >= ${origin.minusMonths(3)} AND su.activated_at < $origin)"""
      .query[(Option[BigDecimal], Option[BigDecimal], Option[BigDecimal])]
      .unique
      .map { case (shelf, vel, vel3) => (shelf.filter(_ > 0), vel.filter(_ > 0), vel3.filter(_ > 0)) }

  // The MRPeasy open book (the user's lag-structure point, doc 26): orders measurably sit days-to-weeks between
  // creation and dispatch, differently per account. The open book = un-dispatched qty on orders created in the
  // 60 days before the origin (older open lines are stale records, not demand); the ratio = the trailing-4-month
  // measured share of dispatched qty that was already booked at its dispatch month's start. Both censored.
  private def mrpBookContext(
      company: UUID,
      variant: UUID,
      origin: LocalDate
  ): ConnectionIO[(Option[BigDecimal], Option[BigDecimal])] =
    sql"""SELECT
            (SELECT SUM(GREATEST(ol.qty - COALESCE(shipped.q, 0), 0))::numeric
             FROM order_line ol JOIN "order" o ON o.id = ol.order_id
             LEFT JOIN LATERAL (
               SELECT SUM(dl.qty) AS q FROM dispatch_line dl JOIN dispatch d ON d.id = dl.dispatch_id
               WHERE dl.order_line_id = ol.id AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin
             ) shipped ON true
             WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
               AND o.created_at < $origin AND o.created_at >= ${origin.minusDays(60)}
               AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')),
            (SELECT SUM(CASE WHEN o.created_at < date_trunc('month', COALESCE(d.delivered_at, d.date::timestamptz))
                             THEN dl.qty ELSE 0 END)::numeric / NULLIF(SUM(dl.qty), 0)
             FROM dispatch_line dl
             JOIN dispatch d ON d.id = dl.dispatch_id
             JOIN order_line ol ON ol.id = dl.order_line_id
             JOIN "order" o ON o.id = ol.order_id
             WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
               AND COALESCE(d.delivered_at, d.date::timestamptz) >= ${origin.minusMonths(4)}
               AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin)"""
      .query[(Option[BigDecimal], Option[BigDecimal])]
      .unique
      .map { case (open, ratio) => (open.filter(_ > 0), ratio) }

  private def zeroFill(
      raw: List[(LocalDate, BigDecimal)],
      origin: LocalDate,
      ctx: (Option[BigDecimal], Option[BigDecimal], Option[BigDecimal]),
      book: (Option[BigDecimal], Option[BigDecimal], Option[BigDecimal]),
      funnel: Option[BigDecimal],
      momentum: Option[BigDecimal],
      mrp: (Option[BigDecimal], Option[BigDecimal])
  ): DemandHistory =
    raw.headOption match {
      case None =>
        DemandHistory(
          Vector.empty,
          Vector.empty,
          ctx._1,
          ctx._2,
          book._1,
          book._2,
          book._3,
          funnel,
          momentum,
          mrp._1,
          mrp._2,
          ctx._3
        )
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
          momentum,
          mrp._1,
          mrp._2,
          ctx._3
        )
    }

  // Actuals for scoring a horizon (the same demand definition, after the origin).
  def actuals(
      company: UUID,
      variant: UUID,
      from: LocalDate,
      until: LocalDate
  ): ConnectionIO[Map[LocalDate, BigDecimal]] =
    hasSerials(company, variant).flatMap {
      case true =>
        sql"""SELECT month, SUM(qty)::numeric FROM (
                SELECT date_trunc('month', COALESCE(d.delivered_at, d.date::timestamptz))::date AS month,
                       1::numeric AS qty
                FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
                WHERE su.company_id = $company AND su.product_variant_id = $variant
                  AND COALESCE(d.delivered_at, d.date::timestamptz) >= GREATEST($from, $DispatchBasisCutover)
                  AND COALESCE(d.delivered_at, d.date::timestamptz) < $until
                UNION ALL
                SELECT date_trunc('month', o.created_at)::date, ol.qty
                FROM order_line ol JOIN "order" o ON o.id = ol.order_id
                WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
                  AND o.created_at >= $from AND o.created_at < LEAST($until, $DispatchBasisCutover)
                  AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
              ) t GROUP BY 1""".query[(LocalDate, BigDecimal)].to[List].map(_.toMap)
      case false =>
        sql"""SELECT date_trunc('month', o.created_at)::date, SUM(ol.qty)::numeric
              FROM order_line ol JOIN "order" o ON o.id = ol.order_id
              WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
                AND o.created_at >= $from AND o.created_at < $until
                AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
              GROUP BY 1""".query[(LocalDate, BigDecimal)].to[List].map(_.toMap)
    }

  // Dispatch lines are the dispatch-dated demand record (the ASC-606 basis) wherever they exist; raw serial
  // counts are NOT a demand series — V3 serialization ramped through 2024 and reads as phantom growth.
  // MRPeasy shipment RECORDS have their own adoption ramp (measured: 2.6k → 8.3k → 13.7k units/quarter through
  // Q4'24 against flat order volume) — so the series cuts over at the adoption boundary: order dates before,
  // dispatch dates after. A fixed, documented splice beats a phantom 3× growth curve feeding every model.
  private val DispatchBasisCutover = LocalDate.of(2025, 1, 1)

  // The serial log carries the SHIPMENT's own account+variant attribution — orders are booked under one SKU
  // and fulfilled under another (measured: a 2,000-unit account read as 62 through the order-line join), so
  // any join through order lines mis-attributes cross-SKU fulfilment. Serials post-cutover, orders before.
  private def hasSerials(company: UUID, variant: UUID): ConnectionIO[Boolean] =
    sql"""SELECT EXISTS(
            SELECT 1 FROM serial_unit WHERE company_id = $company AND product_variant_id = $variant)"""
      .query[Boolean]
      .unique

  private def monthlySeries(
      company: UUID,
      variant: UUID,
      origin: java.time.LocalDateTime
  ): ConnectionIO[List[(LocalDate, BigDecimal)]] =
    hasSerials(company, variant).flatMap {
      case true =>
        sql"""SELECT month, SUM(qty)::numeric FROM (
                SELECT date_trunc('month', COALESCE(d.delivered_at, d.date::timestamptz))::date AS month,
                       1::numeric AS qty
                FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
                WHERE su.company_id = $company AND su.product_variant_id = $variant
                  AND COALESCE(d.delivered_at, d.date::timestamptz) >= $DispatchBasisCutover
                  AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin
                UNION ALL
                SELECT date_trunc('month', o.created_at)::date, ol.qty
                FROM order_line ol JOIN "order" o ON o.id = ol.order_id
                WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
                  AND o.created_at < LEAST($origin, $DispatchBasisCutover)
                  AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
              ) t GROUP BY 1 ORDER BY 1""".query[(LocalDate, BigDecimal)].to[List]
      case false =>
        sql"""SELECT date_trunc('month', o.created_at)::date, SUM(ol.qty)::numeric
              FROM order_line ol JOIN "order" o ON o.id = ol.order_id
              WHERE o.sold_to_party_id = $company AND ol.product_variant_id = $variant
                AND o.created_at < $origin AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
              GROUP BY 1 ORDER BY 1""".query[(LocalDate, BigDecimal)].to[List]
    }
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

  // Each key's censored history (and deal/depletion context) is fetched ONCE per origin and shared by every
  // registry model — with thousands of real accounts a per-model fetch multiplies the dominant cost twelvefold.
  def runOrigin(origin: LocalDate, horizonMonths: Int, minOrders: Int = 3): F[Int] =
    DemandSeriesRepo
      .forecastableKeys(origin, minOrders)
      .transact(xa)
      .flatMap { keys =>
        DemandModel.registry
          .traverse(m => ForecastRunRepo.insertRun(origin, horizonMonths, m, "backtest").map(m -> _))
          .transact(xa)
          .flatMap { runs =>
            val live = runs.collect { case (m, Some(runId)) => (m, runId) }
            if (live.isEmpty) 0.pure[F] // every (origin, model) already ran — idempotent
            else
              keys
                .traverse(key => predictKey(key, origin, horizonMonths, live))
                .map(_ => keys.size * live.size)
          }
      }

  private def predictKey(
      key: (UUID, UUID),
      origin: LocalDate,
      horizon: Int,
      runs: List[(DemandModel, UUID)]
  ): F[Unit] = {
    val (company, variant) = key
    val program = DemandSeriesRepo.history(company, variant, origin).flatMap { h =>
      runs.traverse_ {
        case (model, runId) =>
          model.predictClamped(h, horizon).zipWithIndex.toList.traverse_ {
            case (qty, i) =>
              ForecastRunRepo.insertPrediction(runId, company, variant, origin.plusMonths(i.toLong), qty)
          }
      }
    }
    program.transact(xa)
  }

  // Score every unscored prediction of runs whose horizon has (partly) elapsed — called as quarters close;
  // adding actuals automatically extends the learning (doc 26 §5). Actuals are fetched ONCE per
  // (company, variant) and shared across every model's predictions — they are the same numbers, and at real
  // account counts a per-prediction fetch is two orders of magnitude more queries.
  def scoreOrigin(origin: LocalDate, asOf: LocalDate): F[Int] =
    runsAt(origin).transact(xa).flatMap { runs =>
      val modelOf = runs.toMap
      runs
        .traverse(r => predictionsOf(r._1).map(_.map(p => (r._1, p))).transact(xa))
        .map(_.flatten.filter { case (_, (_, _, period, _)) => period.plusMonths(1).compareTo(asOf) <= 0 })
        .flatMap(
          _.groupBy { case (_, (company, variant, _, _)) => (company, variant) }.toList
            .traverse {
              case ((company, variant), preds) =>
                val program = DemandSeriesRepo
                  .actuals(company, variant, origin, origin.plusMonths(horizonOf(preds, origin)))
                  .flatMap(act =>
                    preds
                      .traverse {
                        case (runId, (_, _, period, forecast)) =>
                          val actual  = act.getOrElse(period, BigDecimal(0))
                          val horizon = java.time.Period.between(origin, period).toTotalMonths.toInt + 1
                          ForecastRunRepo.score(
                            runId,
                            company,
                            variant,
                            modelOf.getOrElse(runId, "?"),
                            origin,
                            period,
                            horizon,
                            forecast,
                            actual
                          )
                      }
                      .map(_.sum)
                  )
                program.transact(xa)
            }
            .map(_.sum)
        )
    }

  private def horizonOf(preds: List[(UUID, (UUID, UUID, LocalDate, BigDecimal))], origin: LocalDate): Long =
    preds
      .map { case (_, (_, _, period, _)) => java.time.Period.between(origin, period).toTotalMonths + 1 }
      .maxOption
      .getOrElse(0L)

  def champion(company: UUID): F[Option[(String, BigDecimal)]] = ForecastRunRepo.champion(company).transact(xa)

  // Materialize the tournament's choice per account for an origin (doc 26 §5): selection recomputed at read
  // time took 21 minutes over the population; written at scoring time it is a millisecond query and the H6Q
  // board serves it live. Latest selection wins — evidence and selection code both evolve.
  def materializeSelections(origin: LocalDate): F[Int] =
    sql"SELECT DISTINCT company_id FROM model_accuracy WHERE origin_month = $origin"
      .query[UUID]
      .to[List]
      .transact(xa)
      .flatMap(
        _.traverse { company =>
          val program = PolicyRepo.evidence(company, origin).flatMap { ev =>
            sql"""SELECT model_key, SUM(forecast_qty), SUM(actual_qty)
                  FROM model_accuracy WHERE company_id = $company AND origin_month = $origin
                  GROUP BY model_key"""
              .query[(String, BigDecimal, BigDecimal)]
              .to[List]
              .flatMap { rows =>
                val byModel = rows.map { case (k, f, a) => k -> ((f, a)) }.toMap
                byModel.values.headOption match {
                  case None => 0.pure[ConnectionIO]
                  case Some((_, actual)) =>
                    val policy = PolicySelector.select(ev)
                    val forecast = policy.weights.toList
                      .map { case (k, w) => byModel.get(k).map(_._1).getOrElse(BigDecimal(0)) * w }
                      .foldLeft(BigDecimal(0))(_ + _)
                    val weightsJson = io.circe.Json
                      .obj(policy.weights.toList.map { case (k, w) => k -> io.circe.Json.fromBigDecimal(w) }: _*)
                      .noSpaces
                    sql"""INSERT INTO policy_selection (origin_month, company_id, policy_key, weights, forecast_qty, actual_qty)
                          VALUES ($origin, $company, ${policy.key}, $weightsJson::jsonb, $forecast, $actual)
                          ON CONFLICT (origin_month, company_id) DO UPDATE SET
                            policy_key = EXCLUDED.policy_key, weights = EXCLUDED.weights,
                            forecast_qty = EXCLUDED.forecast_qty, actual_qty = EXCLUDED.actual_qty,
                            selected_at = now()""".update.run
                }
              }
          }
          program.transact(xa)
        }.map(_.sum)
      )

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
