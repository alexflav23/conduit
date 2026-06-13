package com.hypervolt.conduit

import cats.effect.IO
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hypervolt.conduit.api.auth.KeycloakJwtVerifier
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import scala.jdk.CollectionConverters._
import weaver.SimpleIOSuite

// P2.4 (doc 34): the Keycloak OIDC trust anchor, server-side. A realm JWT passes only with our audience, the
// realm issuer, a non-expired `exp`, and a valid RS256 signature against the realm JWKS — returning `sub` (the
// keycloak id). A token for another client, a different realm/issuer, a tampered/expired token, or one signed
// by the wrong key all fail closed. No live Keycloak: a generated keypair stands in for the realm JWKS.
object KeycloakJwtVerifierSuite extends SimpleIOSuite {

  private val realm  = "https://kc.hypervolt.co.uk/realms/conduit"
  private val client = "conduit-api"

  private def keypair() = {
    val gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); gen.generateKeyPair()
  }
  private val keys  = keypair()
  private val pub   = keys.getPublic.asInstanceOf[RSAPublicKey]
  private val priv  = keys.getPrivate.asInstanceOf[RSAPrivateKey]
  private val other = keypair() // an attacker's key the realm JWKS does NOT publish

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

  private val verifier = new KeycloakJwtVerifier[IO](stubJwks, realm, client)

  private def token(
      aud: String = client,
      iss: String = realm,
      sub: String = "kc-user-123",
      expiresAt: Instant = Instant.now().plusSeconds(300),
      signWith: (RSAPublicKey, RSAPrivateKey) = (pub, priv)
  ): String =
    JWT
      .create()
      .withKeyId("test-key")
      .withAudience(aud)
      .withIssuer(iss)
      .withSubject(sub)
      .withExpiresAt(Date.from(expiresAt))
      .sign(Algorithm.RSA256(signWith._1, signWith._2))

  test("a realm token with our audience + issuer resolves to its sub (the keycloak id)") {
    verifier.verify(token()).map(r => expect(r.contains("kc-user-123")))
  }
  test("a token for another client (wrong audience) fails closed") {
    verifier.verify(token(aud = "some-other-client")).map(r => expect(r.isEmpty))
  }
  test("a token from a different realm/issuer fails closed") {
    verifier.verify(token(iss = "https://kc.hypervolt.co.uk/realms/evil")).map(r => expect(r.isEmpty))
  }
  test("an expired token fails closed") {
    verifier.verify(token(expiresAt = Instant.now().minusSeconds(60))).map(r => expect(r.isEmpty))
  }
  test("a token signed by a key the JWKS does not publish fails closed") {
    val o = other
    verifier
      .verify(token(signWith = (o.getPublic.asInstanceOf[RSAPublicKey], o.getPrivate.asInstanceOf[RSAPrivateKey])))
      .map(r => expect(r.isEmpty))
  }
}
