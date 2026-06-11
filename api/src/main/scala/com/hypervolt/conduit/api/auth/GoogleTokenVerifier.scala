package com.hypervolt.conduit.api.auth

import cats.effect.Sync
import cats.syntax.all._
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.RSAPublicKey

// Verifies a Google ID token server-side (the ghost-busters domain gate, moved out of the client where it
// was advisory): RS256 signature against Google's JWKS, issuer, audience (OUR OAuth client id — a token
// minted for any other app fails), expiry, a verified email, and the `hd` hosted-domain claim — which only
// Google Workspace accounts carry, so a personal gmail.com can never pass even if the consent screen were
// misconfigured. Returns the e-mail, the caller maps it to a Principal.
final class GoogleTokenVerifier[F[_]: Sync](jwks: JwkProvider, clientId: String, workspaceDomain: String) {

  private val Issuers = Set("https://accounts.google.com", "accounts.google.com")

  def verify(token: String): F[Option[String]] =
    Sync[F]
      .blocking {
        val unverified = JWT.decode(token)
        val key        = jwks.get(unverified.getKeyId).getPublicKey.asInstanceOf[RSAPublicKey]
        val verified = JWT
          .require(Algorithm.RSA256(key, null))
          .withAudience(clientId)
          .build()
          .verify(token)
        val issuerOk = Issuers.contains(verified.getIssuer)
        val emailOk  = Option(verified.getClaim("email_verified").asBoolean()).exists(_.booleanValue())
        val domainOk = Option(verified.getClaim("hd").asString()).contains(workspaceDomain)
        Option(verified.getClaim("email").asString()).filter(_ => issuerOk && emailOk && domainOk)
      }
      .recover { case _: Exception => None }
}

object GoogleTokenVerifier {
  val JwksUrl = "https://www.googleapis.com/oauth2/v3/certs"
}
