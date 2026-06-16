package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.shadow.FreeShipmentService
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Free-shipment analytics (the COGS-without-revenue population, tracked distinctly). Category mix + monthly trend +
// the warranty-replacement rate that feeds liability accrual. view:free_shipment to read; edit to rebuild/reclassify.
final class FreeShipmentRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val svc  = new FreeShipmentService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def forbid(p: Principal, action: Action): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, action, "free_shipment"))(
      err(StatusCode.Forbidden, "forbidden", s"requires ${action.name}:free_shipment")
    )

  private val summary =
    base.get
      .in("api" / "v1" / "free-shipments" / "summary")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.View) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => svc.summary.map(rows => Right(Json.fromValues(rows)))
          }
      )

  private val trend =
    base.get
      .in("api" / "v1" / "free-shipments" / "trend")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.View) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => svc.trend.map(rows => Right(Json.fromValues(rows)))
          }
      )

  private val warranty =
    base.get
      .in("api" / "v1" / "free-shipments" / "warranty-metrics")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.View) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => svc.warrantyMetrics.map(j => Right(j))
          }
      )

  private val rebuild =
    base.post
      .in("api" / "v1" / "free-shipments" / "rebuild")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.Edit) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => svc.rebuild.map(n => Right(Json.obj("classified" -> n.asJson)))
          }
      )

  private val reclassify =
    base.post
      .in("api" / "v1" / "free-shipments" / path[String]("dispatchId") / "reclassify")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (idS, body) =>
          forbid(p, Action.Edit) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (Try(UUID.fromString(idS)).toOption, body.hcursor.get[String]("category").toOption) match {
                case (None, _) => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid id: $idS")))
                case (_, None) =>
                  Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "missing field: category")))
                case (Some(id), Some(cat)) =>
                  svc.reclassify(id, cat, p.userId).map {
                    case 0 => Left(err(StatusCode.NotFound, "not_found", s"no free shipment $idS"))
                    case _ => Right(Json.obj("dispatch_id" -> idS.asJson, "category" -> cat.asJson))
                  }
              }
          }
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(List(summary, trend, warranty, rebuild, reclassify))
}
