package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured

import com.hypervolt.conduit.forecast.ForecastRunReportRepo
import com.hypervolt.conduit.forecast.RunDiff
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Forecast-run tracking (doc 26 §7): the timeline of every forecast origin, a comprehensive per-run report
// (stats + by-segment outturn + the model runs and their scored error — the BASIS the champion was chosen on),
// and a human-readable diff between any two runs (RunDiff). Read-only, gated view:pipeline_coverage — the same
// gate as the H6Q board. Origins are immutable, idempotent records, so every figure is reproducible.
final class ForecastRunRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def gate(p: Principal): Boolean                                      = PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage")
  private val denied                                                           = err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")

  private def origin(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(if (s.length == 7) s + "-01" else s)).toEither
      .leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid origin month: $s"))

  private def statsJson(s: RunDiff.RunStats): Json =
    Json.obj(
      "accounts"              -> s.accounts.asJson,
      "forecast_units"        -> s.forecastUnits.asJson,
      "actual_units"          -> s.actualUnits.asJson,
      "total_level_error_pct" -> s.totalLevelErrorPct.asJson,
      "structural_share"      -> s.structuralShare.asJson
    )

  private def mixJson(m: Map[String, Int]): Json =
    Json.fromValues(
      m.toList.sortBy(-_._2).map { case (k, n) => Json.obj("policy_key" -> k.asJson, "accounts" -> n.asJson) }
    )

  private val runs =
    base.get
      .in("api" / "v1" / "forecast" / "runs")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else ForecastRunReportRepo.origins.transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  private val report =
    base.get
      .in("api" / "v1" / "forecast" / "runs" / path[String]("origin") / "report")
      .out(jsonBody[Json])
      .serverLogic(p =>
        originStr =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            origin(originStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(o) =>
                (
                  ForecastRunReportRepo.selections(o),
                  ForecastRunReportRepo.segments(o),
                  ForecastRunReportRepo.provenance(o),
                  ForecastRunReportRepo.modelAccuracy(o)
                ).tupled.transact(xa).map {
                  case (sel, segs, prov, acc) =>
                    Right(
                      Json.obj(
                        "origin"         -> originStr.asJson,
                        "stats"          -> statsJson(RunDiff.stats(sel)),
                        "policy_mix"     -> mixJson(RunDiff.policyMix(sel)),
                        "segments"       -> Json.fromValues(segs),
                        "model_runs"     -> Json.fromValues(prov),
                        "model_accuracy" -> Json.fromValues(acc)
                      )
                    )
                }
            }
      )

  private val axes = Set("segment", "channel", "market")

  private val diff =
    base.get
      .in("api" / "v1" / "forecast" / "runs" / "diff")
      .in(query[String]("from"))
      .in(query[String]("to"))
      .in(query[Option[String]]("group_by"))
      .in(query[Option[String]]("market"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (fromStr, toStr, groupByOpt, marketOpt) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else {
            val axis      = groupByOpt.filter(axes).getOrElse("segment")
            val marketFil = marketOpt.flatMap(s => Try(java.util.UUID.fromString(s)).toOption)
            (origin(fromStr), origin(toStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fromO, toO)) =>
                (
                  ForecastRunReportRepo.selections(fromO),
                  ForecastRunReportRepo.selections(toO),
                  ForecastRunReportRepo.dimensionDelta(fromO, toO, axis, marketFil),
                  ForecastRunReportRepo.marketsFor(fromO, toO)
                ).tupled
                  .transact(xa)
                  .map {
                    case (from, to, breakdown, markets) =>
                      val d = RunDiff.diff(from, to)
                      Right(
                        Json.obj(
                          "from"             -> fromStr.asJson,
                          "to"               -> toStr.asJson,
                          "group_by"         -> axis.asJson,
                          "from_stats"       -> statsJson(d.fromStats),
                          "to_stats"         -> statsJson(d.toStats),
                          "error_delta_pct"  -> d.errorDeltaPct.asJson,
                          "accounts_added"   -> d.accountsAdded.size.asJson,
                          "accounts_dropped" -> d.accountsDropped.size.asJson,
                          "champion_changes" -> Json.fromValues(
                            d.championChanges.map(c =>
                              Json.obj(
                                "company_id" -> c.company.toString.asJson,
                                "from"       -> c.from.asJson,
                                "to"         -> c.to.asJson
                              )
                            )
                          ),
                          "policy_mix_from" -> mixJson(d.policyMixFrom),
                          "policy_mix_to"   -> mixJson(d.policyMixTo),
                          "breakdown"       -> Json.fromValues(breakdown),
                          "markets"         -> Json.fromValues(markets),
                          "narrative"       -> Json.fromValues(d.narrative.map(_.asJson))
                        )
                      )
                  }
            }
          }
      })

  private def uuidOpt(s: Option[String]): Option[java.util.UUID] =
    s.flatMap(x => Try(java.util.UUID.fromString(x)).toOption)

  // Account-level delta: per-account champion change + forecast Δ + the LIVE depletion state (stock, rate,
  // runway) — sorted by biggest forecast move so enterprise accounts surface. Optional market/segment/channel.
  private val accounts =
    base.get
      .in("api" / "v1" / "forecast" / "runs" / "diff" / "accounts")
      .in(query[String]("from"))
      .in(query[String]("to"))
      .in(query[Option[String]]("market"))
      .in(query[Option[String]]("segment"))
      .in(query[Option[String]]("channel"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (fromStr, toStr, mkt, seg, chan, lim) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            (origin(fromStr), origin(toStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fromO, toO)) =>
                ForecastRunReportRepo
                  .accountDelta(fromO, toO, uuidOpt(mkt), seg, uuidOpt(chan), lim.getOrElse(100).min(500))
                  .transact(xa)
                  .map(rows => Right(Json.fromValues(rows)))
            }
      })

  // One account's drill-down: the participating models (the bake-off) at the `to` origin + its per-SKU depletion
  // snapshot delta (shelf + rate, from→to).
  private val account =
    base.get
      .in("api" / "v1" / "forecast" / "runs" / "account" / path[String]("company"))
      .in(query[String]("from"))
      .in(query[String]("to"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (companyStr, fromStr, toStr) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            (
              origin(fromStr),
              origin(toStr),
              Try(java.util.UUID.fromString(companyStr)).toEither.left
                .map(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $companyStr"))
            ).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fromO, toO, c)) =>
                ForecastRunReportRepo.accountDrill(fromO, toO, c).transact(xa).map(Right(_))
            }
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(runs, report, diff, accounts, account))
}
