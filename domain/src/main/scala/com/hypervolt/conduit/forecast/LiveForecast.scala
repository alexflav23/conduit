package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The live engine (doc 26 §6, batch layer): for each forecastable account×SKU, the BACKTEST CHAMPION (argmin over
// the error ledger; seasonal_naive until an account has history) fits the full censored-at-now history and its
// predictions land in the existing H6Q spine as forecast_entry(source='model') rows — so humans, hyperview and the
// learned models are scored on identical terms, and the coverage projector fans them out unchanged. Prior model
// rows for the same key are superseded (the H6Q append-only chain), never deleted.
final class LiveForecastService[F[_]: Async](xa: Transactor[F]) {

  def publish(origin: LocalDate, horizonMonths: Int, minOrders: Int = 3): F[Int] =
    DemandSeriesRepo.forecastableKeys(origin, minOrders).transact(xa).flatMap {
      _.traverse {
        case (company, variant) =>
          ForecastRunRepo.championBefore(company, origin).transact(xa).flatMap { champ =>
            val model = champ
              .flatMap(c => DemandModel.registry.find(_.key == c._1))
              .getOrElse(DemandModel.SeasonalNaive)
            publishOne(company, variant, model, origin, horizonMonths)
          }
      }.map(_.sum)
    }

  private def publishOne(
      company: UUID,
      variant: UUID,
      model: DemandModel,
      origin: LocalDate,
      horizon: Int
  ): F[Int] = {
    val program = DemandSeriesRepo.history(company, variant, origin).flatMap { h =>
      val preds = model.predict(h, horizon)
      defaultScenario.flatMap { scenario =>
        preds.zipWithIndex.toList
          .traverse {
            case (qty, i) =>
              val period = origin.plusMonths(i.toLong)
              insertModelEntry(company, variant, period, scenario, qty, model)
                .flatMap(newId => supersedePrior(company, variant, period, newId).as(1))
          }
          .map(_.sum)
      }
    }
    program.transact(xa)
  }

  private val defaultScenario: ConnectionIO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE is_default = true LIMIT 1".query[UUID].unique

  // the H6Q append-only chain: prior current model rows for the key point at the replacing entry, never deleted
  private def supersedePrior(company: UUID, variant: UUID, period: LocalDate, newId: UUID): ConnectionIO[Int] =
    sql"""UPDATE forecast_entry SET superseded_by = $newId
          WHERE company_id = $company AND product_variant_id = $variant AND period_month = $period
            AND source = 'model' AND superseded_by IS NULL AND id <> $newId""".update.run

  private def insertModelEntry(
      company: UUID,
      variant: UUID,
      period: LocalDate,
      scenario: UUID,
      qty: BigDecimal,
      model: DemandModel
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO forecast_entry (company_id, product_variant_id, period_month, scenario_id, qty, source, model_version)
          VALUES ($company, $variant, $period, $scenario, ${qty.setScale(0, RoundingMode.HALF_UP).toInt},
                  'model', ${model.key + ":v" + model.version})
          RETURNING id""".query[UUID].unique
}

// The soft-real-time runway projection (doc 26 §6, streaming layer): recomputed from the serial/activation log
// (rebuildable, never authoritative). Velocity = trailing-6-month activations; runway = shelf ÷ velocity. When the
// runway crosses the account's reorder point, `forecast.account.runway` fires — the sales signal with a date on it.
final class RunwayService[F[_]: Async](xa: Transactor[F]) {

  private val defaultReorderPointDays = BigDecimal(30)

  def refresh(company: UUID, variant: UUID, asOf: Instant): F[Option[BigDecimal]] = {
    val origin = LocalDate.ofInstant(asOf, java.time.ZoneOffset.UTC).plusDays(1)
    val program = shelfAndVelocity(company, variant, origin).flatMap {
      case (shelf, velocity) =>
        val runway =
          velocity.filter(_ > 0).map(v => (shelf / v * BigDecimal("30.44")).setScale(1, RoundingMode.HALF_UP))
        upsertState(company, variant, shelf, velocity.getOrElse(BigDecimal(0)), runway, asOf) *>
          (runway match {
            case Some(d) if d <= defaultReorderPointDays => emitRunway(company, variant, shelf, d).as(runway)
            case _                                       => runway.pure[ConnectionIO]
          })
    }
    program.transact(xa)
  }

  private def shelfAndVelocity(
      company: UUID,
      variant: UUID,
      origin: LocalDate
  ): ConnectionIO[(BigDecimal, Option[BigDecimal])] =
    sql"""SELECT
            (SELECT COUNT(*)::numeric FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND COALESCE(d.delivered_at, d.date::timestamptz) < $origin AND su.activated_at IS NULL),
            (SELECT COUNT(*)::numeric / 6.0 FROM serial_unit su
             WHERE su.company_id = $company AND su.product_variant_id = $variant
               AND su.activated_at >= ${origin.minusMonths(6)} AND su.activated_at < $origin)"""
      .query[(BigDecimal, Option[BigDecimal])]
      .unique

  private def upsertState(
      company: UUID,
      variant: UUID,
      shelf: BigDecimal,
      velocity: BigDecimal,
      runway: Option[BigDecimal],
      asOf: Instant
  ): ConnectionIO[Int] =
    sql"""INSERT INTO account_forecast_state
            (company_id, product_variant_id, shelf_stock, velocity_ewma, runway_days, reorder_point_days, last_event_at)
          VALUES ($company, $variant, $shelf, $velocity, $runway, $defaultReorderPointDays, $asOf)
          ON CONFLICT (company_id, product_variant_id) DO UPDATE SET
            shelf_stock = EXCLUDED.shelf_stock, velocity_ewma = EXCLUDED.velocity_ewma,
            runway_days = EXCLUDED.runway_days, last_event_at = EXCLUDED.last_event_at""".update.run

  private def emitRunway(company: UUID, variant: UUID, shelf: BigDecimal, runway: BigDecimal): ConnectionIO[Int] =
    OutboxRepo.append(
      OutboxEvent(
        UUID.randomUUID(),
        "forecast.account.runway",
        1,
        "forecast",
        company,
        company.toString,
        None,
        None,
        None,
        Json.obj(
          "company_id"  -> company.toString.asJson,
          "variant_id"  -> variant.toString.asJson,
          "shelf_stock" -> shelf.toString.asJson,
          "runway_days" -> runway.toString.asJson
        ),
        Instant.now(),
        "service:forecast"
      )
    )
}
