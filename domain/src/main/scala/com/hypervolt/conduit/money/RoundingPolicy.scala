package com.hypervolt.conduit.money

import scala.math.BigDecimal.RoundingMode

// Explicit, per-boundary rounding (doc 14 §1.2). Never a scattered `.setScale` — the mode and the
// boundary it applies at are passed in and therefore recorded, not implied. Default HALF_UP for
// commercial amounts; a jurisdiction that mandates otherwise configures its own policy.
final case class RoundingPolicy(mode: RoundingMode.Value)

object RoundingPolicy {
  val HalfUp: RoundingPolicy   = RoundingPolicy(RoundingMode.HALF_UP)
  val HalfEven: RoundingPolicy = RoundingPolicy(RoundingMode.HALF_EVEN)
  val Floor: RoundingPolicy    = RoundingPolicy(RoundingMode.FLOOR)
  val default: RoundingPolicy  = HalfUp
}
