package com.hypervolt.conduit.warranty

import java.time.LocalDate
import weaver.SimpleIOSuite

object WarrantyMathSpec extends SimpleIOSuite {

  private val est   = BigDecimal("100.0000")
  private val start = LocalDate.parse("2026-01-01")
  private val end   = LocalDate.parse("2026-01-11") // 10-day term

  pureTest("nothing released at the start") {
    expect(WarrantyMath.released(est, start, end, start) == BigDecimal("0.0000"))
  }

  pureTest("straight-line: half released at the midpoint") {
    expect(WarrantyMath.released(est, start, end, LocalDate.parse("2026-01-06")) == BigDecimal("50.0000"))
  }

  pureTest("fully released at term end and capped beyond it") {
    expect(WarrantyMath.released(est, start, end, end) == BigDecimal("100.0000")) and
      expect(WarrantyMath.released(est, start, end, LocalDate.parse("2027-01-01")) == BigDecimal("100.0000"))
  }

  pureTest("outstanding = estimated - released - consumed") {
    expect(WarrantyMath.outstanding(BigDecimal("100.00"), BigDecimal("40.00"), BigDecimal("10.00")) == BigDecimal("50.00"))
  }
}
