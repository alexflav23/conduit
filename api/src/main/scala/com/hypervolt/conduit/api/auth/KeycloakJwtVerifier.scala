package com.hypervolt.conduit.api.auth

import cats.effect.Sync
import cats.syntax.all._
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.RSAPublicKey

// Verifies a Keycloak-issued OIDC JWT (P2.4 / doc 34): RS256 against the realm's JWKS, issuer (the realm URL),
// audience (our client), and expiry (java-jwt enforces `exp` by default). Returns the `sub` — the keycloak id —
// which the caller maps to a Conduit Principal (the doc-05 policy layer then applies). Google federates as an
// IdP *inside* Keycloak, so the user experience is unchanged; this is just the server-side trust anchor moving
// from Google's JWKS to the realm's. Mirrors GoogleTokenVerifier (the same auth0 jwks-rsa + java-jwt machinery).
final class KeycloakJwtVerifier[F[_]: Sync](jwks: JwkProvider, issuer: String, audience: String) {

  def verify(token: String): F[Option[String]] =
    Sync[F]
      .blocking {
        val unverified = JWT.decode(token)
        val key        = jwks.get(unverified.getKeyId).getPublicKey.asInstanceOf[RSAPublicKey]
        val verified = JWT
          .require(Algorithm.RSA256(key, null))
          .withIssuer(issuer)
          .withAudience(audience)
          .build()
          .verify(token)
        Option(verified.getSubject).filter(_.nonEmpty)
      }
      .recover { case _: Exception => None }
}

object KeycloakJwtVerifier {
  // The realm JWKS endpoint (the trust anchor): …/realms/<realm>/protocol/openid-connect/certs (CLAUDE.md §2).
  def jwksUrl(baseUrl: String, realm: String): String =
    s"${baseUrl.stripSuffix("/")}/realms/$realm/protocol/openid-connect/certs"

  def issuer(baseUrl: String, realm: String): String =
    s"${baseUrl.stripSuffix("/")}/realms/$realm"
}
