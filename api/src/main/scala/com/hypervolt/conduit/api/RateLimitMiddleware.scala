package com.hypervolt.conduit.api

import cats.Monad
import cats.syntax.all._
import com.hypervolt.conduit.ratelimit.RateLimiter
import org.http4s.Header
import org.http4s.HttpApp
import org.http4s.Response
import org.http4s.Status
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

// Per-principal rate limiting at the edge (P2.5 / doc 19 §B.4): keys each request by its bearer token (one
// token = one principal) and 429s with Retry-After when that principal's token bucket is empty — so a runaway
// caller (a reseller, a buggy integration) degrades itself before it can starve core. Health checks are exempt
// (never throttle liveness). Applied only to the API port, not the admin/health server.
object RateLimitMiddleware {

  def apply[F[_]: Monad](limiter: RateLimiter[F])(app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      if (req.uri.path.renderString.startsWith("/health")) app(req)
      else {
        val key = req.headers.get[Authorization].map(_.credentials.toString).getOrElse("anon")
        limiter.acquire(key).flatMap {
          case true => app(req)
          case false =>
            Response[F](Status.TooManyRequests)
              .putHeaders(Header.Raw(CIString("Retry-After"), "1"))
              .pure[F]
        }
      }
    }
}
