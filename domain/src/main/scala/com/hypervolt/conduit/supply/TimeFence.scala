package com.hypervolt.conduit.supply

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.math.BigDecimal.RoundingMode

// Supply-commitment time fences for a contract manufacturer (Volex). Every manufacturer has decision gates: a
// FROZEN window inside the production lead time where the PO is firm and cannot change (a change incurs
// liability / penalty interest against the 6-month buffer — forecasting guide §1); a FLEX window where the
// forecast may still move within a tolerance (the 20% margin-of-error discipline §4); and a FREE window beyond
// the horizon where demand can move freely. This is the classic frozen/slushy/liquid model made explicit.
//
// The REFINED, real-time-friendly mechanism the business wants is `headroom`: for any future week it returns
// exactly how much the forecast can still move (up and down) before it hits a gate — so as forecasting becomes
// continuous, every revision can be admitted, trimmed, or escalated against live remaining capacity rather than
// a coarse weekly cutoff.
object TimeFence {

  sealed abstract class Zone(val name: String)
  object Zone {
    case object Frozen extends Zone("frozen")
    case object Flex   extends Zone("flex")
    case object Free   extends Zone("free")
  }

  // leadTimeDays: within this horizon the PO is firm (frozen). flexHorizonDays: out to here, change is allowed
  // within flexTolerancePct of the committed quantity. Beyond flexHorizonDays it is free. frozenTolerancePct is
  // the small CONTRACTUAL change a manufacturer still allows inside the frozen window (often < 20%, configurable;
  // 0 = truly firm). `graded` smooths the flex band so the tolerance rises CONTINUOUSLY from frozenTolerancePct
  // at the lead-time edge to flexTolerancePct at the horizon — no cliff at the boundary, the right shape for
  // continuous/real-time forecasting (a week just inside the flex band behaves almost like a frozen one).
  final case class Policy(
      leadTimeDays: Int,
      flexHorizonDays: Int,
      flexTolerancePct: BigDecimal,
      frozenTolerancePct: BigDecimal = BigDecimal(0),
      graded: Boolean = false
  )

  // The effective change tolerance at a given horizon — flat per zone, or graded across the flex band.
  def tolerancePct(daysOut: Long, p: Policy): BigDecimal =
    if (daysOut <= p.leadTimeDays) p.frozenTolerancePct
    else if (!p.graded) p.flexTolerancePct
    else {
      val span = (p.flexHorizonDays - p.leadTimeDays).max(1)
      val frac = BigDecimal((daysOut - p.leadTimeDays).min(span.toLong)) / BigDecimal(span)
      (p.frozenTolerancePct + (p.flexTolerancePct - p.frozenTolerancePct) * frac).setScale(4, RoundingMode.HALF_UP)
    }

  def zone(asOf: LocalDate, target: LocalDate, p: Policy): Zone = {
    val daysOut = ChronoUnit.DAYS.between(asOf, target)
    if (daysOut <= p.leadTimeDays) Zone.Frozen
    else if (daysOut <= p.flexHorizonDays) Zone.Flex
    else Zone.Free
  }

  // How much the forecast for `target` can still move given what is already committed (the firm PO for that week).
  // Frozen: no movement. Flex: ± tolerance × committed. Free: unbounded (Int.MaxValue sentinel).
  final case class Headroom(zone: Zone, maxIncrease: Int, maxDecrease: Int) {
    def admits(committed: Int, newDemand: Int): Boolean = {
      val delta = newDemand - committed
      if (delta >= 0) delta <= maxIncrease else (-delta) <= maxDecrease
    }
  }

  def headroom(asOf: LocalDate, target: LocalDate, p: Policy, committed: Int): Headroom = {
    val daysOut = ChronoUnit.DAYS.between(asOf, target)
    zone(asOf, target, p) match {
      case Zone.Frozen =>
        // firm: only the small contractual frozen tolerance (often 0) may move; otherwise no change.
        val tol = (BigDecimal(committed) * tolerancePct(daysOut, p) / 100).setScale(0, RoundingMode.HALF_UP).toInt
        Headroom(Zone.Frozen, tol, tol)
      case Zone.Flex =>
        // tolerance bounds CHANGES to an existing firm commitment; establishing a new plan from zero is allowed.
        if (committed == 0) Headroom(Zone.Flex, Int.MaxValue, 0)
        else {
          val tol = (BigDecimal(committed) * tolerancePct(daysOut, p) / 100).setScale(0, RoundingMode.HALF_UP).toInt
          Headroom(Zone.Flex, tol, tol)
        }
      case Zone.Free => Headroom(Zone.Free, Int.MaxValue, Int.MaxValue)
    }
  }

  // Admissibility of moving a week's committed firm quantity to a new demand (the gate check).
  def admits(asOf: LocalDate, target: LocalDate, p: Policy, committed: Int, newDemand: Int): Boolean =
    headroom(asOf, target, p, committed).admits(committed, newDemand)
}
