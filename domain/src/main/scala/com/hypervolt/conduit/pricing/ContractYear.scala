package com.hypervolt.conduit.pricing

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// The rolling contract year (doc 24 §5.1): 12 months from the agreement's valid_from (its commencement), NOT a
// calendar/fiscal year. Year N = [valid_from + N·12mo, valid_from + (N+1)·12mo). DERIVED from valid_from + an
// occurred-at instant (a UTC-instant re-projection, like fiscal-period assignment, doc 14 §2) — never stored. Each
// agreement runs on its own anniversary boundary; cumulative volume and the rebate accrual reset at each anniversary.
object ContractYear {

  // The zero-based contract-year index containing `asOf` (0 before/at commencement).
  def indexOf(validFrom: Instant, asOf: Instant): Long = {
    val months = ChronoUnit.MONTHS.between(validFrom.atZone(ZoneOffset.UTC), asOf.atZone(ZoneOffset.UTC))
    if (months < 0) 0L else months / 12
  }

  // The [start, end) window of the contract year containing `asOf` — anniversary-correct via calendar months.
  def windowFor(validFrom: Instant, asOf: Instant): (Instant, Instant) = {
    val vf = validFrom.atZone(ZoneOffset.UTC)
    val n  = indexOf(validFrom, asOf)
    (vf.plusMonths(n * 12).toInstant, vf.plusMonths((n + 1) * 12).toInstant)
  }
}
