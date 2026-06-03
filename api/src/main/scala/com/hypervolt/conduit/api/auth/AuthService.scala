package com.hypervolt.conduit.api.auth

import cats.effect.Async
import com.hypervolt.conduit.access.AccessRepo
import com.hypervolt.conduit.access.Principal
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

// Resolves the authenticated Principal from a bearer token. Production verifies a Keycloak JWT (JWKS) and
// reads `sub`; local/dev accepts `dev:<keycloak_id>` so tests and the desk e2e run without a live Keycloak.
final class AuthService[F[_]: Async](xa: Transactor[F], devMode: Boolean) {

  def resolve(token: String): F[Option[Principal]] =
    keycloakId(token) match {
      case Some(kc) => AccessRepo.loadPrincipal(kc).transact(xa)
      case None     => Async[F].pure(None)
    }

  private def keycloakId(token: String): Option[String] =
    if (devMode && token.startsWith("dev:")) Some(token.drop(4)).filter(_.nonEmpty)
    else None // prod: verify JWKS + extract `sub` (wired alongside the Keycloak realm)
}

final case class ApiError(error: String, message: String)
object ApiError {
  implicit val codec: Codec[ApiError] = deriveCodec
}
