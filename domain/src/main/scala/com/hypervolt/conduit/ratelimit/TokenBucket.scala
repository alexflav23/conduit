package com.hypervolt.conduit.ratelimit

// Per-principal token-bucket rate limiting (P2.5 / doc 19 §B.4): a reseller/API caller draws one token per
// request; the bucket refills at a steady rate up to its capacity, so steady traffic flows and bursts are
// capped — the reseller tier degrades (429) before it can starve core. This is the pure arithmetic core
// (no clock, no state) so it is property-testable; RateLimiter wraps it with a Ref-map + the real clock.
final case class Bucket(tokens: Double, lastMs: Long)

object TokenBucket {

  // Refill for the elapsed time (never above capacity, never on a clock that went backwards), then try to take
  // one token. Returns the new bucket + whether the request is admitted.
  def tryAcquire(b: Bucket, capacity: Double, refillPerSec: Double, nowMs: Long): (Bucket, Boolean) = {
    val elapsedSec = math.max(0L, nowMs - b.lastMs) / 1000.0
    val refilled   = math.min(capacity, b.tokens + elapsedSec * refillPerSec)
    if (refilled >= 1.0) (Bucket(refilled - 1.0, nowMs), true)
    else (Bucket(refilled, nowMs), false)
  }

  def full(capacity: Double, nowMs: Long): Bucket = Bucket(capacity, nowMs)
}
