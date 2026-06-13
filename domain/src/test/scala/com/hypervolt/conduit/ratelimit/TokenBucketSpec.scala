package com.hypervolt.conduit.ratelimit

import cats.Show
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// P2.5 (doc 19 §B.4): the token-bucket invariants under the rate limiter. The bucket must never exceed capacity
// or go negative, a same-instant burst on a full bucket admits exactly capacity then 429s the rest, and after
// idle it refills at the rate (bounded). Pure arithmetic ⇒ ScalaCheck.
object TokenBucketSpec extends SimpleIOSuite with Checkers {

  private implicit def showAll[A]: Show[A] = Show.fromToString

  private val params: Gen[(Double, Double, Long)] = for {
    cap    <- Gen.choose(1, 1000).map(_.toDouble)
    refill <- Gen.choose(1, 500).map(_.toDouble)
    now    <- Gen.choose(0L, 10_000_000L)
  } yield (cap, refill, now)

  test("tokens stay within [0, capacity] across an arbitrary acquire sequence") {
    forall(for {
      p <- params; steps <- Gen.choose(1, 200); gaps <- Gen.listOfN(steps, Gen.choose(0L, 5000L))
    } yield (p, gaps)) {
      case ((cap, refill, start), gaps) =>
        var b   = TokenBucket.full(cap, start)
        var now = start
        var ok  = true
        gaps.foreach { g =>
          now += g
          val (nb, _) = TokenBucket.tryAcquire(b, cap, refill, now)
          b = nb
          if (b.tokens < -1e-9 || b.tokens > cap + 1e-9) ok = false
        }
        expect(ok)
    }
  }

  test("a same-instant burst on a full bucket admits exactly floor(capacity), then rejects") {
    forall(Gen.choose(1, 50)) { capInt =>
      val cap = capInt.toDouble
      var b   = TokenBucket.full(cap, 1000L)
      val admitted = (1 to capInt + 5).count { _ =>
        val (nb, ok) = TokenBucket.tryAcquire(b, cap, refillPerSec = 10.0, nowMs = 1000L) // same instant ⇒ no refill
        b = nb; ok
      }
      expect(admitted == capInt) // exactly the capacity, the extra 5 all 429
    }
  }

  pureTest("after idle, the bucket refills at the rate (and never past capacity)") {
    // empty a cap=10 bucket, then wait 1s at refill=5/s ⇒ exactly 5 more admits, not 6
    var b   = Bucket(0.0, 0L)
    val cap = 10.0; val refill = 5.0
    val (b1, firstAfterWait) =
      TokenBucket.tryAcquire(b, cap, refill, nowMs = 1000L) // +5 tokens, take 1 ⇒ 4 left, admitted
    b = b1
    val more = (1 to 10).count { _ =>
      val (nb, ok) = TokenBucket.tryAcquire(b, cap, refill, 1000L); b = nb; ok
    }
    expect(firstAfterWait) and expect(more == 4) // 5 refilled, 1 taken on the wait-acquire, 4 remain
  }

  pureTest("a clock that goes backwards does not grant free tokens") {
    val b = Bucket(3.0, 5000L)
    val (nb, ok) =
      TokenBucket.tryAcquire(b, capacity = 10.0, refillPerSec = 100.0, nowMs = 1000L) // earlier than lastMs
    expect(ok) and expect(nb.tokens == 2.0)                                           // no refill from negative elapsed; just took 1
  }
}
