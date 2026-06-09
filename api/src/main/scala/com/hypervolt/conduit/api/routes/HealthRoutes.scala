package com.hypervolt.conduit.api.routes

import cats.effect.Async
import org.http4s.HttpRoutes
import sttp.tapir.PublicEndpoint
import sttp.tapir.endpoint
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.stringBody

object HealthRoutes {

  val healthEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get.in("health").out(stringBody)

  def routes[F[_]: Async]: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(
      healthEndpoint.serverLogicSuccess[F](_ => Async[F].pure("OK"))
    )
}
