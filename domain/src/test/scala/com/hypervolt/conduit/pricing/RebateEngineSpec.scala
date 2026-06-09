package com.hypervolt.conduit.pricing

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// doc 24 §5.7 — the earned-rebate math, as properties (the "must be perfect" core, at the pure layer).
object RebateEngineSpec extends SimpleIOSuite with Checkers {

  // a well-formed descending ladder: entry @600, then steps down as the threshold rises
  private val ladder = List(
    RebateEngine.Tier(0, BigDecimal("600")),
    RebateEngine.Tier(100, BigDecimal("560")),
    RebateEngine.Tier(500, BigDecimal("520")),
    RebateEngine.Tier(1000, BigDecimal("480"))
  )

  private val gen: Gen[(Int, Int)] = for {
    cum   <- Gen.choose(0, 5000)
    units <- Gen.choose(0, 5000)
  } yield (cum, units)

  test("earned is never negative and is zero below the first step (reproducible, deterministic)") {
    forall(gen) {
      case (cum, units) =>
        val e  = RebateEngine.earned(ladder, cum, units)
        val e2 = RebateEngine.earned(ladder, cum, units) // replay → identical
        expect(e >= BigDecimal(0)) and expect(e == e2) and
          expect(if (cum < 100) e == BigDecimal(0) else e >= BigDecimal(0))
    }
  }

  test("earned is monotonic non-decreasing in cumulative volume (a better tier only grows the rebate)") {
    forall(Gen.choose(0, 2000)) { units =>
      val points = List(0, 100, 500, 1000, 1500).map(c => RebateEngine.earned(ladder, c, units))
      expect(points == points.sorted) // non-decreasing as cumVol crosses each step
    }
  }

  pureTest("worked figures: 1000 units at tier @480 (entry @600) earns 1000 × 120 = 120000") {
    expect(RebateEngine.earned(ladder, 1000, 1000) == BigDecimal("120000")) and
      expect(RebateEngine.earned(ladder, 50, 50) == BigDecimal("0")) and  // below first step
      expect(RebateEngine.earned(ladder, 100, 100) == BigDecimal("4000")) // 100 × (600−560)
  }
}
