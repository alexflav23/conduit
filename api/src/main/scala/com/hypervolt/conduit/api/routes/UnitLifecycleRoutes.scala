package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.warranty.UnitLifecycleService
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The unit replacement lifecycle: a serial's family timeline (original → RMA replacements), the shared warranty
// window, and the support tickets that link them. Gated view:pipeline_coverage (the shelf/serial read gate).
final class UnitLifecycleRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val svc  = new UnitLifecycleService[F](xa)

  private val lifecycle =
    base.get
      .in("api" / "v1" / "serials" / path[String]("serial") / "lifecycle")
      .out(jsonBody[Json])
      .serverLogic(p =>
        serial =>
          if (!PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage"))
            Async[F].pure(Left((StatusCode.Forbidden, ApiError("forbidden", "requires view:pipeline_coverage"))))
          else
            svc.lifecycle(serial).map {
              case None    => Right(Json.obj("error" -> s"unknown serial $serial".asJson))
              case Some(j) => Right(j)
            }
      )

  private val rmaStats =
    base.get
      .in("api" / "v1" / "warranty" / "rma-stats")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage"))
            Async[F].pure(Left((StatusCode.Forbidden, ApiError("forbidden", "requires view:pipeline_coverage"))))
          else svc.rmaStats.map(Right(_))
      )

  val serverEndpoints = List(lifecycle, rmaStats)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
