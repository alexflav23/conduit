package com.hypervolt.conduit.forecast

import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Turning an agent's aggregate unit count into a per-SKU forecast (the "Overall Product Sales Mix" in the H6Q
// spreadsheet). Hypervolt sells the same model in many SKUs (colour × cable length × generation, e.g.
// HV3PROAAUW050T2 = Home 3 Pro / White / 5.0m / Type 2). Agents think in unit counts; H6Q must still record
// quantities PER SKU, so we split the count by a historical/configured demand mix. The split is conserving:
// Σ per-SKU == the entered total, EXACTLY, via largest-remainder integer allocation (the same discipline as
// Money.allocate, doc 14 §1.3) — no unit is invented or lost.
object SkuMix {

  // weights need not sum to 1; they are normalised. Returns one (variant, qty) per weight, summing to `total`.
  def allocate(total: Int, weights: Vector[(UUID, BigDecimal)]): Vector[(UUID, Int)] = {
    require(total >= 0, "total must be non-negative")
    require(weights.nonEmpty, "at least one SKU weight is required")
    require(weights.forall(_._2 >= 0), "weights must be non-negative")
    val sum = weights.map(_._2).foldLeft(BigDecimal(0))(_ + _)
    require(sum > 0, "weights must sum to > 0")

    val raw   = weights.map { case (v, w) => v -> (BigDecimal(total) * w / sum) }
    val floor = raw.map { case (v, r) => (v, r.setScale(0, RoundingMode.FLOOR).toInt, r) }
    val used  = floor.map(_._2).sum
    var left  = total - used
    // hand the leftover units to the largest fractional remainders (stable by original index on ties).
    val order = floor.zipWithIndex.sortBy { case ((_, fl, r), i) => (-(r - BigDecimal(fl)), i) }.map(_._2)
    val bump  = Array.fill(floor.length)(0)
    var k     = 0
    while (left > 0 && floor.nonEmpty) {
      bump(order(k % order.length)) += 1
      left -= 1
      k += 1
    }
    floor.zipWithIndex.map { case ((v, fl, _), i) => v -> (fl + bump(i)) }
  }
}
