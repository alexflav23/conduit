package com.hypervolt.conduit.pricing

// The earned retrospective rebate as PURE, deterministic math (doc 24 §5.2) — reproducible by construction, so a
// replay of the same (ladder, cumulative volume, units) yields the identical figure to the penny. ACCRUE only: this
// computes what is OWED; it never applies/settles anything (doc 24 §5).
object RebateEngine {

  final case class Tier(fromQty: Int, price: BigDecimal)

  // The entry tier — the firm in-year invoice price = the lowest-threshold band.
  def entryPrice(ladder: List[Tier]): Option[BigDecimal] = ladder.minByOption(_.fromQty).map(_.price)

  // The achieved tier price given cumulative qualifying volume = the highest from_qty ≤ cumVol (entry if below all).
  def achievedPrice(ladder: List[Tier], cumVol: Int): Option[BigDecimal] =
    ladder.filter(_.fromQty <= cumVol).maxByOption(_.fromQty).map(_.price).orElse(entryPrice(ladder))

  // The rebate at a given tier position: per-unit saving (entry − price at `tierVol`) × the units receiving it
  // (doc 24 §5.2/§5.3). EARNED uses the actual cumulative volume; EXPECTED uses the larger of actual volume and the
  // contract commitment (the H6Q floor) — same math, different position. Never negative (a well-formed ladder steps
  // DOWN, so achieved ≤ entry); clamped at 0 to be robust to a mis-ordered ladder.
  def earnedAt(ladder: List[Tier], tierVol: Int, unitsReceiving: Int): BigDecimal =
    (entryPrice(ladder), achievedPrice(ladder, tierVol)) match {
      case (Some(e), Some(a)) => (BigDecimal(unitsReceiving) * (e - a)).max(BigDecimal(0))
      case _                  => BigDecimal(0)
    }

  def earned(ladder: List[Tier], cumVol: Int, unitsReceiving: Int): BigDecimal =
    earnedAt(ladder, cumVol, unitsReceiving)
}
