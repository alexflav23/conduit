package com.hypervolt.conduit.forecast

import java.time.LocalDate
import weaver.SimpleIOSuite

// doc 26 §4a — depletion visible in sell-in alone: a stocking spike above the consumption rate forecasts a
// sub-rate quarter (the shelf must digest), then the account returns to its rate. All censored, no telemetry.
object PantryReversalSpec extends SimpleIOSuite {

  private def hist(qty: Vector[Int]): DemandHistory = {
    val start = LocalDate.of(2024, 1, 1)
    DemandHistory(Vector.tabulate(qty.length)(i => start.plusMonths(i.toLong)), qty.map(BigDecimal(_)))
  }

  pureTest("a stocking spike forecasts the mirror-image digestion quarter, then the consumption rate") {
    // four quarters: 300, 300, 300, then a 600 stocking wave → consumption 375, reversal = 2·375 − 600 = 150
    val h = hist(Vector(100, 100, 100, 100, 100, 100, 100, 100, 100, 200, 200, 200))
    val p = DemandModel.PantryReversal.predict(h, 6)
    expect(p.take(3).map(_.toInt) == Vector(50, 50, 50)) and  // 150 / 3
      expect(p.drop(3).map(_.toInt) == Vector(125, 125, 125)) // back to consumption 375 / 3
  }

  pureTest("a starved quarter forecasts recovery, clamped at twice the consumption rate") {
    // quarters: 300, 300, 300, 0 → consumption 225, reversal = min(2·225 − 0, 2·225) = 450
    val h = hist(Vector(100, 100, 100, 100, 100, 100, 100, 100, 100, 0, 0, 0))
    expect(DemandModel.PantryReversal.predict(h, 3).map(_.toInt) == Vector(150, 150, 150))
  }

  pureTest("under two full quarters of history it degrades to the run-rate") {
    val h = hist(Vector(100, 120, 80, 100))
    expect(DemandModel.PantryReversal.predict(h, 2) == DemandModel.RunRate3.predict(h, 2))
  }
}
