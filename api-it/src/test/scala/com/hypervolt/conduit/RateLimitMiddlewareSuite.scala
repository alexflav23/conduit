package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import com.hypervolt.conduit.api.RateLimitMiddleware
import com.hypervolt.conduit.ratelimit.RateLimiter
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.HttpApp
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.http4s.headers.Authorization
import weaver.SimpleIOSuite

// P2.5: the edge rate-limit middleware. A principal over its bucket gets 429+Retry-After before the app runs;
// a different principal is unaffected; /health is never throttled. Pure http4s (no DB), fixed clock.
object RateLimitMiddlewareSuite extends SimpleIOSuite {

  private val okApp: HttpApp[IO] = HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok)))

  private def req(path: String, token: Option[String]) = {
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
  }

  test("a principal over capacity gets 429; another principal is unaffected; health is exempt") {
    for {
      clock <- Ref.of[IO, Long](1000L)
      rl    <- RateLimiter.withClock[IO](capacity = 1, refillPerSec = 1, clock.get)
      app = RateLimitMiddleware(rl)(okApp)
      first  <- app(req("/api/v1/anything", Some("tok-a"))).map(_.status.code)
      second <- app(req("/api/v1/anything", Some("tok-a"))).map(_.status.code) // same principal, bucket empty
      other  <- app(req("/api/v1/anything", Some("tok-b"))).map(_.status.code) // different principal
      health <- app(req("/health", None)).map(_.status.code)                   // exempt even after the limit hit
      retry <- app(req("/api/v1/anything", Some("tok-a")))
        .map(_.headers.get(org.typelevel.ci.CIString("Retry-After")).isDefined)
    } yield expect(first == 200) and expect(second == 429) and expect(other == 200) and
      expect(health == 200) and expect(retry)
  }
}
