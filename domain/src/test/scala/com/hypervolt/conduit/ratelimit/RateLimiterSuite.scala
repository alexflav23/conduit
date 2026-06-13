package com.hypervolt.conduit.ratelimit

import cats.effect.IO
import cats.effect.Ref
import weaver.SimpleIOSuite

// P2.5: the enforceable rate limiter (per-key buckets + injected clock). A burst beyond capacity is denied,
// keys are isolated (one principal's spend doesn't limit another), and after the clock advances the bucket
// refills. No DB, virtual clock ⇒ deterministic.
object RateLimiterSuite extends SimpleIOSuite {

  private def limiter(cap: Double, refill: Double, clock: Ref[IO, Long]): IO[RateLimiter[IO]] =
    RateLimiter.withClock[IO](cap, refill, clock.get)

  test("a burst beyond capacity is denied; keys are isolated") {
    for {
      clock <- Ref.of[IO, Long](1000L)
      rl    <- limiter(cap = 3, refill = 1, clock)
      a1    <- rl.acquire("alice")
      a2    <- rl.acquire("alice")
      a3    <- rl.acquire("alice")
      a4    <- rl.acquire("alice") // 4th over a capacity-3 bucket at the same instant → denied
      bob   <- rl.acquire("bob")   // a different principal is unaffected
    } yield expect(a1 && a2 && a3) and expect(!a4) and expect(bob)
  }

  test("the bucket refills as the clock advances") {
    for {
      clock <- Ref.of[IO, Long](0L)
      rl    <- limiter(cap = 2, refill = 1, clock) // 1 token/sec
      _     <- rl.acquire("k")
      _     <- rl.acquire("k")
      empty <- rl.acquire("k")                     // capacity 2 exhausted → denied
      _     <- clock.set(2000L)                    // +2s ⇒ +2 tokens (capped at 2)
      again <- rl.acquire("k")
    } yield expect(!empty) and expect(again)
  }
}
