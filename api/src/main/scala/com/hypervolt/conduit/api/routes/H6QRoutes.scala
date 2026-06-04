package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastQueryRepo
import com.hypervolt.conduit.forecast.ForecastService
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class SubmitLineReq(variant: String, period: String, scenario: String, qty: Int)
object SubmitLineReq { implicit val codec: Codec[SubmitLineReq] = deriveCodec }

final case class SubmitForecastReq(cycle: String, lines: List[SubmitLineReq])
object SubmitForecastReq { implicit val codec: Codec[SubmitForecastReq] = deriveCodec }

final case class SkipReq(cycle: String, reason: String)
object SkipReq { implicit val codec: Codec[SkipReq] = deriveCodec }

// H6Q REST surface (doc 12 §11). Capture is own-scope create:forecast; the coverage board is
// view:pipeline_coverage, scope-filtered and layer-projected (volume/commercial/profitability).
final class H6QRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base    = Secured.base[F](auth)
  private val service = new ForecastService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def date(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid date: $s"))

  private val scenarios =
    base.get
      .in("api" / "v1" / "h6q" / "scenarios")
      .out(jsonBody[Json])
      .serverLogic(_ => _ => ForecastQueryRepo.scenarios.transact(xa).map(rows => Right(Json.fromValues(rows))))

  private val cycles =
    base.get
      .in("api" / "v1" / "h6q" / "cycles")
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(_ =>
        status => ForecastQueryRepo.cycles(status).transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  // The owner's capture grid for the current open cycle (own scope — keyed by the principal's user id).
  private val myForecasts =
    base.get
      .in("api" / "v1" / "h6q" / "my-forecasts")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        _ =>
          service.currentOpenCycle().flatMap {
            case None => Async[F].pure(Right(Json.obj("cycle" -> Json.Null, "accounts" -> Json.arr())))
            case Some(cycleId) =>
              ForecastQueryRepo
                .myForecasts(principal.userId, cycleId)
                .transact(xa)
                .map(rows => Right(Json.obj("cycle" -> cycleId.toString.asJson, "accounts" -> Json.fromValues(rows))))
          }
      )

  private val submit =
    base.post
      .in("api" / "v1" / "h6q" / "my-forecasts" / path[String]("company_id") / "submit")
      .in(jsonBody[SubmitForecastReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, req) =>
          val parsed = for {
            account <- uuid(companyStr)
            cycle   <- uuid(req.cycle)
            lines <- req.lines.traverse(l =>
              (uuid(l.variant), date(normaliseMonth(l.period)), uuid(l.scenario))
                .mapN((v, m, sc) => ForecastLine(v, m, sc, l.qty))
            )
          } yield (account, cycle, lines)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((account, cycle, lines)) =>
              service.submit(principal.userId, account, cycle, lines, Some("desk")).map {
                case Left("not_owner") =>
                  Left(err(StatusCode.Forbidden, "forbidden", "you do not own this account this cycle"))
                case Left("cycle_closed") => Left(err(StatusCode.Conflict, "cycle_closed", "the cycle is closed"))
                case Left(other)          => Left(err(StatusCode.UnprocessableEntity, "invalid", other))
                case Right(changed) =>
                  Right(
                    Json.obj(
                      "company_id" -> account.toString.asJson,
                      "versioned"  -> changed.asJson,
                      "status"     -> "submitted".asJson
                    )
                  )
              }
          }
      })

  private val skip =
    base.post
      .in("api" / "v1" / "h6q" / "my-forecasts" / path[String]("company_id") / "skip")
      .in(jsonBody[SkipReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, req) =>
          (uuid(companyStr), uuid(req.cycle)).tupled match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((account, cycle)) =>
              service.skip(principal.userId, account, cycle, req.reason).map {
                case Left(_)  => Left(err(StatusCode.Forbidden, "forbidden", "you do not own this account this cycle"))
                case Right(_) => Right(Json.obj("company_id" -> account.toString.asJson, "status" -> "skipped".asJson))
              }
          }
      })

  private val outstanding =
    base.get
      .in("api" / "v1" / "h6q" / "outstanding")
      .in(query[String]("cycle"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        cycleStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            uuid(cycleStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(cycleId) =>
                ForecastQueryRepo.outstanding(cycleId).transact(xa).map(rows => Right(Json.fromValues(rows)))
            }
      )

  // The coverage board at one level (org axis or by agent), scope-filtered and layer-projected.
  private val coverage =
    base.get
      .in("api" / "v1" / "h6q" / "coverage")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("group_by"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr, groupByOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr)), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario)) =>
                val level = groupByOpt.getOrElse("market")
                ForecastQueryRepo
                  .coverage(market, period, scenario, level)
                  .transact(xa)
                  .map(rows =>
                    Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "pipeline_coverage", r))))
                  )
            }
      })

  private val reconcile =
    base.get
      .in("api" / "v1" / "h6q" / "coverage" / "reconcile")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr)), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario)) =>
                ForecastQueryRepo.reconcile(market, period, scenario).transact(xa).map(Right(_))
            }
      })

  private def normaliseMonth(s: String): String = if (s.length == 7) s + "-01" else s

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(scenarios, cycles, myForecasts, submit, skip, outstanding, coverage, reconcile)
    )
}
