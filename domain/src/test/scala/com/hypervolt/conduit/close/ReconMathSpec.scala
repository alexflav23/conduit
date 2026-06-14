package com.hypervolt.conduit.close

import weaver.SimpleIOSuite

// The reconciliation verdict (doc 14 §5): matched iff actual ties expected to the penny — Docker-free.
object ReconMathSpec extends SimpleIOSuite {

  pureTest("money rounds minor units HALF_UP to 2dp") {
    expect(ReconMath.money(BigDecimal(25000)) == BigDecimal("250.00"))
  }

  pureTest("a penny-exact tie is matched with zero variance") {
    val e = ReconMath.evaluate(BigDecimal(250), BigDecimal(250))
    expect(e.status == "matched") and expect(e.variance == BigDecimal("0.00"))
  }

  pureTest("any non-zero variance is an exception") {
    val over  = ReconMath.evaluate(BigDecimal(250), BigDecimal("250.50"))
    val under = ReconMath.evaluate(BigDecimal(250), BigDecimal("249.50"))
    expect(over.status == "exception") and expect(over.variance == BigDecimal("0.50")) and
      expect(under.status == "exception") and expect(under.variance == BigDecimal("-0.50"))
  }
}
