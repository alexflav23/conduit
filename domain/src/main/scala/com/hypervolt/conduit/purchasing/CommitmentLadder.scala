package com.hypervolt.conduit.purchasing

import java.time.LocalDate
import java.util.UUID

// The pure signal logic of the rolling commitment ladder (M9c): zone classification, the deviation signal, and
// the issue decision. Lifted out of CommitmentService so it unit-tests with no Postgres; the service delegates here.
object CommitmentLadder {

  // firm (the contractual locked window) / flex (±tolerance) / indicative (the horizon beyond).
  def zoneFor(month: LocalDate, firmUntil: LocalDate, flexUntil: LocalDate): String =
    if (month.isBefore(firmUntil)) "firm"
    else if (month.isBefore(flexUntil)) "flex"
    else "indicative"

  // the largest relative move (%) on any firm/flex bucket vs what was last communicated — buckets beyond the flex
  // window, and buckets that were zero last time, don't signal.
  def maxDeviation(
      now: Map[(UUID, LocalDate), BigDecimal],
      last: Map[(UUID, LocalDate), BigDecimal],
      flexUntil: LocalDate
  ): BigDecimal =
    last.toList
      .map {
        case (key @ (_, month), prev) if month.isBefore(flexUntil) && prev > 0 =>
          (now.getOrElse(key, BigDecimal(0)) - prev).abs / prev * 100
        case _ => BigDecimal(0)
      }
      .maxOption
      .getOrElse(BigDecimal(0))

  // issue a new version on the calendar backstop (force), the first-ever ladder, or a forecast move past threshold.
  def shouldIssue(force: Boolean, lastEmpty: Boolean, deviation: BigDecimal, threshold: BigDecimal): Boolean =
    force || lastEmpty || deviation > threshold
}
