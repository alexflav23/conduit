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

// Hyperview integration (doc 12 §6): Prophet retail forecasts land as source='hyperview' rows (no submission,
// no forecaster — attributed to model_version). H6Q runs no model; it ingests the output as a source. Rows are
// append-only and versioned just like manual estimates; precedence (manual overrides hyperview by default) is
// resolved at coverage time (CoverageProjector). Idempotent on (key, qty, model_version) — a republish of the
// same number is a no-op.
final class HyperviewService[F[_]: Async](xa: Transactor[F]) {

  private val projector = new CoverageProjector[F](xa)

  def publish(
      account: UUID,
      variant: UUID,
      period: LocalDate,
      scenario: UUID,
      qty: Int,
      modelVersion: String
  ): F[Either[String, Boolean]] =
    publishTx(account, variant, period, scenario, qty, modelVersion).transact(xa).flatMap {
      case Left(e) => e.asLeft[Boolean].pure[F]
      case Right((changed, market)) =>
        market.fold(().pure[F])(m => projector.recompute(m, period, scenario).void).as(changed.asRight[String])
    }

  private def publishTx(
      account: UUID,
      variant: UUID,
      period: LocalDate,
      scenario: UUID,
      qty: Int,
      modelVersion: String
  ): ConnectionIO[Either[String, (Boolean, Option[UUID])]] =
    ForecastRepo.accountDims(account).flatMap {
      case None => "unknown_account".asLeft[(Boolean, Option[UUID])].pure[ConnectionIO]
      case Some(dims) =>
        currentHyperview(dims.branchId, variant, period, scenario).flatMap {
          case Some((_, q, mv)) if q == qty && mv.contains(modelVersion) =>
            (false, dims.marketId).asRight[String].pure[ConnectionIO]
          case prior =>
            ForecastRepo
              .insertEntry(None, None, None, dims, variant, period, scenario, qty, "hyperview", Some(modelVersion))
              .flatMap { newId =>
                prior.fold(().pure[ConnectionIO])(p => ForecastRepo.supersede(p._1, newId).void) *>
                  OutboxRepo
                    .append(event(dims, variant, period, scenario, qty, modelVersion))
                    .as((true, dims.marketId).asRight[String])
              }
        }
    }

  private def currentHyperview(
      branch: UUID,
      variant: UUID,
      period: LocalDate,
      scenario: UUID
  ): ConnectionIO[Option[(UUID, Int, Option[String])]] =
    sql"""SELECT id, qty, model_version FROM forecast_entry
          WHERE branch_company_id = $branch AND product_variant_id = $variant AND period_month = $period
            AND scenario_id = $scenario AND source = 'hyperview' AND superseded_by IS NULL
          ORDER BY created_at DESC LIMIT 1""".query[(UUID, Int, Option[String])].option

  private def event(
      dims: AccountDims,
      variant: UUID,
      period: LocalDate,
      scenario: UUID,
      qty: Int,
      modelVersion: String
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "forecast.hyperview.published",
      1,
      "forecast",
      dims.branchId,
      s"${dims.channelId.getOrElse(dims.marketId)}:$period",
      dims.marketId.map(m => Json.obj("market_id" -> m.toString.asJson)),
      None,
      None,
      Json.obj(
        "market_id"     -> dims.marketId.map(_.toString).asJson,
        "channel_id"    -> dims.channelId.map(_.toString).asJson,
        "company_id"    -> dims.branchId.toString.asJson,
        "variant"       -> variant.toString.asJson,
        "period_month"  -> period.toString.asJson,
        "scenario"      -> scenario.toString.asJson,
        "qty"           -> qty.asJson,
        "model_version" -> modelVersion.asJson
      ),
      Instant.now()
    )
}
