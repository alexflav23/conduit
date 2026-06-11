package com.hypervolt.conduit.api.auth

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.AccessRepo
import com.hypervolt.conduit.access.Principal
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

// Resolves the authenticated Principal from a bearer token. Two doors, both server-side:
//  - Google ID token (the Workspace domain gate, GoogleTokenVerifier) → principal by verified e-mail;
//  - `dev:<keycloak_id>` in non-prod only, so tests and the desk e2e run without live Google.
// Keycloak-federated JWTs come later (doc 19); the Google gate is the first-pass production door.
final class AuthService[F[_]: Async](
    xa: Transactor[F],
    devMode: Boolean,
    google: Option[GoogleTokenVerifier[F]] = None
) {

  def resolve(token: String): F[Option[Principal]] =
    devKeycloakId(token) match {
      case Some(kc) => AccessRepo.loadPrincipal(kc).transact(xa)
      case None =>
        google
          .flatTraverse(_.verify(token))
          .flatMap(_.flatTraverse(email => AccessRepo.loadPrincipalByEmail(email).transact(xa)))
    }

  private def devKeycloakId(token: String): Option[String] =
    if (devMode && token.startsWith("dev:")) Some(token.drop(4)).filter(_.nonEmpty)
    else None
}

final case class ApiError(error: String, message: String)
object ApiError {
  implicit val codec: Codec[ApiError] = deriveCodec
}
