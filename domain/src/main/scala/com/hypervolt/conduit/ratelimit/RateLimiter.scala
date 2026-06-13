package com.hypervolt.conduit.ratelimit

import cats.Monad
import cats.effect.Clock
import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all._

// Per-key (per-principal) rate limiter (P2.5 / doc 19 §B.4): a concurrency-safe Ref-map of token buckets over
// the pure TokenBucket arithmetic. `acquire(key)` atomically refills + takes a token, returning admitted/denied.
// The clock is injected (`now`) so the enforcement is deterministically testable; `create` wires the real clock.
final class RateLimiter[F[_]: Monad](
    state: Ref[F, Map[String, Bucket]],
    now: F[Long],
    capacity: Double,
    refillPerSec: Double
) {
  def acquire(key: String): F[Boolean] =
    now.flatMap { t =>
      state.modify { m =>
        val bucket   = m.getOrElse(key, TokenBucket.full(capacity, t))
        val (nb, ok) = TokenBucket.tryAcquire(bucket, capacity, refillPerSec, t)
        (m.updated(key, nb), ok)
      }
    }
}

object RateLimiter {
  def create[F[_]: Sync](capacity: Double, refillPerSec: Double): F[RateLimiter[F]] =
    Ref
      .of[F, Map[String, Bucket]](Map.empty)
      .map(new RateLimiter[F](_, Clock[F].realTime.map(_.toMillis), capacity, refillPerSec))

  // for tests / a virtual clock: supply the `now` thunk explicitly.
  def withClock[F[_]: Sync](capacity: Double, refillPerSec: Double, now: F[Long]): F[RateLimiter[F]] =
    Ref.of[F, Map[String, Bucket]](Map.empty).map(new RateLimiter[F](_, now, capacity, refillPerSec))
}
