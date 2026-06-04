package com.hypervolt.conduit.supply

import java.time.LocalDate
import weaver.SimpleIOSuite

// The contract-manufacturer firm-commitment gates (frozen / flex / free) and the real-time headroom primitive.
object TimeFenceSpec extends SimpleIOSuite {

  private val asOf   = LocalDate.of(2026, 6, 1)
  private val policy = TimeFence.Policy(leadTimeDays = 56, flexHorizonDays = 180, flexTolerancePct = BigDecimal(20))

  pureTest("inside the production lead time the window is FROZEN — no change is admitted") {
    val target = asOf.plusDays(14)
    val hr     = TimeFence.headroom(asOf, target, policy, committed = 100)
    expect(TimeFence.zone(asOf, target, policy) == TimeFence.Zone.Frozen) and
      expect(hr.maxIncrease == 0) and expect(hr.maxDecrease == 0) and
      expect(hr.admits(100, 100)) and expect(!hr.admits(100, 110))
  }

  pureTest("in the FLEX window an existing commitment may move within ± the tolerance band (20% of 100 = 20)") {
    val target = asOf.plusDays(90)
    val hr     = TimeFence.headroom(asOf, target, policy, committed = 100)
    expect(TimeFence.zone(asOf, target, policy) == TimeFence.Zone.Flex) and
      expect(hr.maxIncrease == 20) and expect(hr.maxDecrease == 20) and
      expect(hr.admits(100, 115)) and expect(hr.admits(100, 85)) and
      expect(!hr.admits(100, 130)) and expect(!hr.admits(100, 70))
  }

  pureTest("establishing a NEW plan from zero is allowed in the flex window (only changes are tolerance-bound)") {
    val hr = TimeFence.headroom(asOf, asOf.plusDays(90), policy, committed = 0)
    expect(hr.admits(0, 500))
  }

  pureTest("beyond the horizon the window is FREE — demand can move freely") {
    val target = asOf.plusDays(220)
    expect(TimeFence.zone(asOf, target, policy) == TimeFence.Zone.Free) and
      expect(TimeFence.admits(asOf, target, policy, committed = 100, newDemand = 400))
  }
}
