package com.hypervolt.conduit.supply

import weaver.SimpleIOSuite

object ReplenishmentSpec extends SimpleIOSuite {

  pureTest("a sustained run-rate increase moves the replenishment suggestion up") {
    val low  = Replenishment.suggestedQty(runRateUnits = 70, windowDays = 7, leadTimeDays = 14, safetyDays = 7, available = 10)
    val high = Replenishment.suggestedQty(runRateUnits = 210, windowDays = 7, leadTimeDays = 14, safetyDays = 7, available = 10)
    expect(high > low) and expect(low > 0)
  }

  pureTest("no suggestion when available stock already covers the reorder point") {
    expect(Replenishment.suggestedQty(70, 7, 14, 7, 100000) == 0)
  }
}
