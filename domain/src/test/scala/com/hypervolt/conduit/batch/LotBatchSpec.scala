package com.hypervolt.conduit.batch

import java.util.UUID
import weaver.SimpleIOSuite

object LotBatchSpec extends SimpleIOSuite {

  private val v = UUID.randomUUID()

  private def batch(price: String, fx: String, freight: String, duty: String): NewBatch =
    NewBatch(
      "B",
      None,
      v,
      100,
      BigDecimal(price),
      BigDecimal(fx),
      "spot",
      None,
      BigDecimal(freight),
      BigDecimal(duty),
      "GBP"
    )

  pureTest("landed unit cost = unit_cost_usd*fx + per-unit freight + per-unit duty") {
    // 100 * 0.79 + 500/100 + 200/100 = 79 + 5 + 2 = 86.0000
    expect(LotBatch.landedUnitCost(batch("100.00", "0.7900", "500.00", "200.00")) == BigDecimal("86.0000"))
  }

  pureTest("two lots of one SKU differ when price, freight or FX differ (no averaging)") {
    val a = LotBatch.landedUnitCost(batch("100.00", "0.7900", "500.00", "200.00"))
    val b = LotBatch.landedUnitCost(batch("110.00", "0.8100", "800.00", "300.00"))
    expect(a != b)
  }
}
