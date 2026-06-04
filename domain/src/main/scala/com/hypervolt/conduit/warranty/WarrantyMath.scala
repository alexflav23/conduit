package com.hypervolt.conduit.warranty

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.math.BigDecimal.RoundingMode

// Straight-line release over [warranty_start, warranty_end] (doc 04 §Warranty). Pure; BigDecimal, no float.
object WarrantyMath {

  def released(estimated: BigDecimal, start: LocalDate, end: LocalDate, asOf: LocalDate): BigDecimal = {
    val termDays = ChronoUnit.DAYS.between(start, end)
    if (termDays <= 0L) estimated
    else {
      val cappedAsOf = if (asOf.isAfter(end)) end else asOf
      val elapsed    = math.max(ChronoUnit.DAYS.between(start, cappedAsOf), 0L)
      (estimated * BigDecimal(elapsed) / BigDecimal(termDays)).setScale(4, RoundingMode.HALF_UP).min(estimated)
    }
  }

  def outstanding(estimated: BigDecimal, released: BigDecimal, consumed: BigDecimal): BigDecimal =
    estimated - released - consumed
}
