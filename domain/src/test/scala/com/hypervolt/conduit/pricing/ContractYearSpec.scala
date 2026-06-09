package com.hypervolt.conduit.pricing

import java.time.Instant
import weaver.SimpleIOSuite

// doc 24 §5.1 — the rolling contract year is anchored to valid_from, resets at each anniversary, and is derived.
object ContractYearSpec extends SimpleIOSuite {

  private val commence = Instant.parse("2024-07-01T00:00:00Z")

  pureTest("year index increments at each anniversary, not at calendar/fiscal boundaries") {
    expect(ContractYear.indexOf(commence, Instant.parse("2024-07-01T00:00:00Z")) == 0L) and
      expect(ContractYear.indexOf(commence, Instant.parse("2025-06-30T23:59:59Z")) == 0L) and // still year 0
      expect(ContractYear.indexOf(commence, Instant.parse("2025-07-01T00:00:00Z")) == 1L) and // anniversary → year 1
      expect(ContractYear.indexOf(commence, Instant.parse("2026-08-15T00:00:00Z")) == 2L) and
      expect(ContractYear.indexOf(commence, Instant.parse("2024-01-01T00:00:00Z")) == 0L) // before commencement
  }

  pureTest("the window for a date is the [anniversary, next-anniversary) it falls in") {
    val (s0, e0) = ContractYear.windowFor(commence, Instant.parse("2025-01-15T00:00:00Z"))
    val (s1, e1) = ContractYear.windowFor(commence, Instant.parse("2025-09-01T00:00:00Z"))
    expect(s0 == Instant.parse("2024-07-01T00:00:00Z")) and
      expect(e0 == Instant.parse("2025-07-01T00:00:00Z")) and
      expect(s1 == Instant.parse("2025-07-01T00:00:00Z")) and
      expect(e1 == Instant.parse("2026-07-01T00:00:00Z")) and
      expect(e0 == s1) // contiguous, no gap/overlap → no unit counts toward two years
  }
}
