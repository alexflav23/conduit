package com.hypervolt.conduit.api.auth

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Principal
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.PartialServerEndpoint

// Shared secured-endpoint base: bearer auth -> Principal, with (StatusCode, ApiError) errors.
object Secured {
  type SecureBase[F[_]] = PartialServerEndpoint[String, Principal, Unit, (StatusCode, ApiError), Unit, Any, F]

  def base[F[_]: Async](auth: AuthService[F]): SecureBase[F] =
    endpoint
      .securityIn(sttp.tapir.auth.bearer[String]())
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .serverSecurityLogic[Principal, F](token =>
        auth.resolve(token).map(_.toRight((StatusCode.Unauthorized, ApiError("unauthorized", "missing or invalid token"))))
      )
}
