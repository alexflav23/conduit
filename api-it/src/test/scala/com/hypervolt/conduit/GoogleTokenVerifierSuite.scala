package com.hypervolt.conduit

import cats.effect.IO
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hypervolt.conduit.api.auth.GoogleTokenVerifier
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import scala.jdk.CollectionConverters._
import weaver.SimpleIOSuite

// The Workspace domain gate, server-side (the ghost-busters pattern hardened): a Google ID token passes only
// with our audience, a Google issuer, a verified e-mail AND the hd=hypervolt.co.uk hosted-domain claim — a
// personal gmail.com account (no hd), a token minted for another app, or an expired token all fail closed.
object GoogleTokenVerifierSuite extends SimpleIOSuite {

  private val keys = {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  }
  private val pub  = keys.getPublic.asInstanceOf[RSAPublicKey]
  private val priv = keys.getPrivate.asInstanceOf[RSAPrivateKey]

  private val stubJwks: JwkProvider = (_: String) =>
    Jwk.fromValues(
      Map[String, AnyRef](
        "kty" -> "RSA",
        "kid" -> "test-key",
        "alg" -> "RS256",
        "use" -> "sig",
        "n"   -> Base64.getUrlEncoder.withoutPadding.encodeToString(pub.getModulus.toByteArray),
        "e"   -> Base64.getUrlEncoder.withoutPadding.encodeToString(pub.getPublicExponent.toByteArray)
      ).asJava
    )

  private val verifier = new GoogleTokenVerifier[IO](stubJwks, "conduit-client-id", "hypervolt.co.uk")

  private def token(
      aud: String = "conduit-client-id",
      iss: String = "https://accounts.google.com",
      hd: Option[String] = Some("hypervolt.co.uk"),
      email: String = "flavian@hypervolt.co.uk",
      emailVerified: Boolean = true,
      expiresAt: Instant = Instant.now().plusSeconds(300)
  ): String = {
    val b = JWT
      .create()
      .withKeyId("test-key")
      .withAudience(aud)
      .withIssuer(iss)
      .withClaim("email", email)
      .withClaim("email_verified", emailVerified)
      .withExpiresAt(Date.from(expiresAt))
    hd.fold(b)(d => b.withClaim("hd", d)).sign(Algorithm.RSA256(pub, priv))
  }

  test("a Workspace token with our audience and hd=hypervolt.co.uk resolves to its e-mail") {
    verifier.verify(token()).map(r => expect(r.contains("flavian@hypervolt.co.uk")))
  }

  test("a personal gmail account (no hd claim) fails closed even with a verified e-mail") {
    verifier.verify(token(hd = None, email = "someone@gmail.com")).map(r => expect(r.isEmpty))
  }

  test("a token minted for another app (wrong audience) fails closed") {
    verifier.verify(token(aud = "some-other-app")).map(r => expect(r.isEmpty))
  }

  test("a non-Google issuer fails closed") {
    verifier.verify(token(iss = "https://evil.example.com")).map(r => expect(r.isEmpty))
  }

  test("an expired token fails closed") {
    verifier.verify(token(expiresAt = Instant.now().minusSeconds(60))).map(r => expect(r.isEmpty))
  }

  test("an unverified e-mail fails closed") {
    verifier.verify(token(emailVerified = false)).map(r => expect(r.isEmpty))
  }
}
