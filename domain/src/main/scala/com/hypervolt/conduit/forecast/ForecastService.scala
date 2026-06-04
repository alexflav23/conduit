package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The cycle engine + append-only capture (doc 12 §2–3). Owners submit their OWN accounts each weekly cycle;
// every estimate is retained (a revision is a new forecast_entry with superseded_by on the prior — never an
// update). The submit + outbox append commit in one transaction so capture is lossless and the rollup consumer
// rebuilds coverage from the event.
final class ForecastService[F[_]: Async](xa: Transactor[F]) {

  // The incremental live-update path (doc 12 §4.2): a successful submit recomputes the affected coverage slices
  // immediately so the board is live without waiting on the stream. The Pulsar consumer of forecast.submitted
  // rebuilds the same projection on replay (idempotent delete+insert) — this is not a divergent write path.
  private val projector = new CoverageProjector[F](xa)

  // Open the weekly cycle (scheduler-triggered; idempotent on the ISO-week code). Re-running for the same week
  // adds no duplicate submissions (ON CONFLICT DO NOTHING). Emits forecast.cycle.opened.
  def openCycle(asOf: LocalDate, cadence: String = "weekly", refTz: String = "Europe/London"): F[(UUID, Int)] = {
    val code = IsoWeek.code(asOf)
    (for {
      cycleId <- ForecastRepo.upsertCycle(code, cadence, IsoWeek.start(asOf), IsoWeek.end(asOf), refTz)
      status  <- ForecastRepo.cycleStatus(cycleId)
      created <- if (status.contains("open")) ForecastRepo.generateOutstanding(cycleId) else 0.pure[ConnectionIO]
      _ <- OutboxRepo.append(
        event(
          "forecast.cycle.opened",
          cycleId,
          Json.obj("code" -> code.asJson, "cadence" -> cadence.asJson, "reference_tz" -> refTz.asJson)
        )
      )
    } yield (cycleId, created)).transact(xa)
  }

  def closeCycle(cycleId: UUID): F[Either[String, Unit]] =
    ForecastRepo.closeCycle(cycleId).transact(xa).flatMap { n =>
      if (n == 0) "cycle not open".asLeft[Unit].pure[F]
      else OutboxRepo.append(event("forecast.cycle.closed", cycleId, Json.obj())).transact(xa).as(().asRight[String])
    }

  def currentOpenCycle(cadence: String = "weekly"): F[Option[UUID]] =
    ForecastRepo.openCycleId(cadence).transact(xa)

  // Capture one owner's estimates for one owned account (doc 12 §3.2). Append-only versioning + no-op
  // suppression: an unchanged value is not re-versioned, keeping the time-series meaningful for accuracy.
  def submit(
      owner: UUID,
      account: UUID,
      cycleId: UUID,
      lines: List[ForecastLine],
      device: Option[String]
  ): F[Either[String, Int]] =
    captureTx(owner, account, cycleId, lines, device).transact(xa).flatMap {
      case Left(e) => e.asLeft[Int].pure[F]
      case Right((changed, marketOpt)) =>
        marketOpt
          .fold(().pure[F])(market =>
            lines.map(l => (l.periodMonth, l.scenarioId)).distinct.traverse_ {
              case (m, sc) => projector.recompute(market, m, sc).void
            }
          )
          .as(changed.asRight[String])
    }

  private def captureTx(
      owner: UUID,
      account: UUID,
      cycleId: UUID,
      lines: List[ForecastLine],
      device: Option[String]
  ): ConnectionIO[Either[String, (Int, Option[UUID])]] =
    ForecastRepo.cycleStatus(cycleId).flatMap {
      case s if !s.contains("open") => "cycle_closed".asLeft[(Int, Option[UUID])].pure[ConnectionIO]
      case _ =>
        ForecastRepo.submissionFor(cycleId, owner, account).flatMap {
          case None => "not_owner".asLeft[(Int, Option[UUID])].pure[ConnectionIO]
          case Some((submissionId, _)) =>
            ForecastRepo.accountDims(account).flatMap {
              case None => "unknown_account".asLeft[(Int, Option[UUID])].pure[ConnectionIO]
              case Some(dims) =>
                lines
                  .traverse(line => versionLine(submissionId, cycleId, owner, dims, line))
                  .map(_.count(identity))
                  .flatMap { changed =>
                    ForecastRepo.markSubmitted(submissionId, device, Instant.now()) *>
                      OutboxRepo
                        .append(submittedEvent(cycleId, owner, account, dims, lines, device))
                        .as((changed, dims.marketId).asRight[String])
                  }
            }
        }
    }

  // Capture an aggregate unit count and split it into a per-SKU forecast via the applicable SKU mix (doc 12 §1.2,
  // the spreadsheet's "Overall Product Sales Mix"). The agent thinks in units; H6Q still records per SKU. The
  // split conserves the total exactly (largest-remainder). Returns the number of SKU lines versioned.
  def submitMix(
      owner: UUID,
      account: UUID,
      cycleId: UUID,
      period: LocalDate,
      scenario: UUID,
      totalQty: Int,
      device: Option[String]
  ): F[Either[String, Int]] =
    ForecastRepo.accountDims(account).transact(xa).flatMap {
      case None => "unknown_account".asLeft[Int].pure[F]
      case Some(dims) =>
        SkuMixRepo.resolve(dims.channelId, dims.marketId).transact(xa).flatMap { mix =>
          if (mix.isEmpty) "no_sku_mix".asLeft[Int].pure[F]
          else {
            val lines =
              SkuMix.allocate(totalQty, mix.toVector).map { case (v, q) => ForecastLine(v, period, scenario, q) }.toList
            submit(owner, account, cycleId, lines, device)
          }
        }
    }

  def skip(owner: UUID, account: UUID, cycleId: UUID, reason: String): F[Either[String, Unit]] =
    ForecastRepo
      .markSkipped(cycleId, owner, account, reason)
      .transact(xa)
      .map(n => if (n == 0) "not_owner".asLeft[Unit] else ().asRight[String])

  // ----- internals -----

  // Returns true if this line produced a new version (false = no-op, value unchanged).
  private def versionLine(
      submissionId: UUID,
      cycleId: UUID,
      owner: UUID,
      dims: AccountDims,
      line: ForecastLine
  ): ConnectionIO[Boolean] =
    ForecastRepo
      .currentEntry(dims.branchId, line.productVariantId, line.periodMonth, line.scenarioId, "manual")
      .flatMap {
        case Some((_, qty)) if qty == line.qty => false.pure[ConnectionIO]
        case prior =>
          ForecastRepo
            .insertEntry(
              Some(submissionId),
              Some(cycleId),
              Some(owner),
              dims,
              line.productVariantId,
              line.periodMonth,
              line.scenarioId,
              line.qty,
              "manual",
              None
            )
            .flatMap { newId =>
              prior.fold(().pure[ConnectionIO])(p => ForecastRepo.supersede(p._1, newId).void).as(true)
            }
      }

  private def submittedEvent(
      cycleId: UUID,
      owner: UUID,
      account: UUID,
      dims: AccountDims,
      lines: List[ForecastLine],
      device: Option[String]
  ): OutboxEvent = {
    val payload = Json.obj(
      "cycle_id"   -> cycleId.toString.asJson,
      "forecaster" -> owner.toString.asJson,
      "company"    -> dims.enclosingCustomerId.toString.asJson,
      "branch"     -> dims.branchId.toString.asJson,
      "market_id"  -> dims.marketId.map(_.toString).asJson,
      "device"     -> device.asJson,
      "lines" -> lines
        .map(l =>
          Json.obj(
            "variant"  -> l.productVariantId.toString.asJson,
            "period"   -> l.periodMonth.toString.asJson,
            "scenario" -> l.scenarioId.toString.asJson,
            "qty"      -> l.qty.asJson
          )
        )
        .asJson
    )
    OutboxEvent(
      UUID.randomUUID(),
      "forecast.submitted",
      1,
      "forecast",
      account,
      s"$cycleId:$account",
      dims.marketId.map(m => Json.obj("market_id" -> m.toString.asJson)),
      Some(cycleId),
      Some(cycleId),
      payload,
      Instant.now()
    )
  }

  private def event(eventType: String, cycleId: UUID, payload: Json): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      eventType,
      1,
      "forecast",
      cycleId,
      cycleId.toString,
      None,
      Some(cycleId),
      Some(cycleId),
      payload,
      Instant.now()
    )
}
