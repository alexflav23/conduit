package com.hypervolt.conduit.api.auth

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.AccessRepo
import com.hypervolt.conduit.access.Principal
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

// Resolves the authenticated Principal from a bearer token. Doors, all server-side, tried in order:
//  - `dev:<keycloak_id>` in non-prod only, so tests and the desk e2e run without live auth;
//  - Keycloak-federated OIDC JWT (P2.4, KeycloakJwtVerifier) → principal by `sub` (the keycloak id);
//  - Google ID token (the Workspace domain gate, GoogleTokenVerifier) → principal by verified e-mail.
// Keycloak and Google tokens are distinguished by issuer (each verifier rejects the other's), so both can run
// during the cutover from the Google-only first-pass door to Keycloak (doc 05/19).
final class AuthService[F[_]: Async](
    xa: Transactor[F],
    devMode: Boolean,
    google: Option[GoogleTokenVerifier[F]] = None,
    keycloak: Option[KeycloakJwtVerifier[F]] = None
) {

  def resolve(token: String): F[Option[Principal]] =
    devKeycloakId(token) match {
      case Some(kc) => AccessRepo.loadPrincipal(kc).transact(xa)
      case None =>
        keycloak.flatTraverse(_.verify(token)).flatMap {
          case Some(sub) => AccessRepo.loadPrincipal(sub).transact(xa)
          case None =>
            google
              .flatTraverse(_.verify(token))
              .flatMap(_.flatTraverse(email => AccessRepo.loadPrincipalByEmail(email).transact(xa)))
        }
    }

  private def devKeycloakId(token: String): Option[String] =
    if (devMode && token.startsWith("dev:")) Some(token.drop(4)).filter(_.nonEmpty)
    else None
}

final case class ApiError(error: String, message: String)
object ApiError {
  implicit val codec: Codec[ApiError] = deriveCodec
}
