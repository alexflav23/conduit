package com.hypervolt.conduit

import com.hypervolt.conduit.forecast.Coverage
import com.hypervolt.conduit.forecast.SkuMix
import com.hypervolt.conduit.supply.TimeFence
import java.time.LocalDate
import java.util.UUID
import weaver.SimpleIOSuite

// Exhaustive BOUNDARY scenarios across the H6Q/supply mechanics — the exact edges (the property suites fuzz the
// interiors). Pure, so they run fast and pin behaviour precisely.
object ScenariosSpec extends SimpleIOSuite {

  private val asOf              = LocalDate.of(2026, 6, 1)
  private def sku(i: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-${i}%012d")

  // ---------- time-fence zone boundaries ----------
  private val pol = TimeFence.Policy(leadTimeDays = 56, flexHorizonDays = 180, flexTolerancePct = BigDecimal(20))

  pureTest("zone boundary: exactly at the lead time is FROZEN; one day past is FLEX") {
    expect(TimeFence.zone(asOf, asOf.plusDays(56), pol) == TimeFence.Zone.Frozen) and
      expect(TimeFence.zone(asOf, asOf.plusDays(57), pol) == TimeFence.Zone.Flex)
  }
  pureTest("zone boundary: exactly at the flex horizon is FLEX; one day past is FREE") {
    expect(TimeFence.zone(asOf, asOf.plusDays(180), pol) == TimeFence.Zone.Flex) and
      expect(TimeFence.zone(asOf, asOf.plusDays(181), pol) == TimeFence.Zone.Free)
  }
  pureTest("a configured frozen tolerance admits exactly that band and no more") {
    val p  = pol.copy(frozenTolerancePct = BigDecimal(10))
    val hr = TimeFence.headroom(asOf, asOf.plusDays(14), p, committed = 100)
    expect(hr.maxIncrease == 10) and expect(hr.admits(100, 110)) and expect(!hr.admits(100, 111))
  }
  pureTest("flex decrease beyond tolerance is rejected; exactly at tolerance is admitted") {
    val hr = TimeFence.headroom(asOf, asOf.plusDays(90), pol, committed = 100)
    expect(hr.admits(100, 80)) and expect(!hr.admits(100, 79))
  }
  pureTest("a target in the past resolves to FROZEN (negative days out)") {
    expect(TimeFence.zone(asOf, asOf.minusDays(5), pol) == TimeFence.Zone.Frozen)
  }

  // ---------- SKU-mix allocation edges ----------
  pureTest("a single-SKU mix takes the whole count") {
    expect(SkuMix.allocate(137, Vector(sku(1) -> BigDecimal(1))) == Vector(sku(1) -> 137))
  }
  pureTest("a zero total splits to all zeros (no units invented)") {
    val out = SkuMix.allocate(0, Vector(sku(1) -> BigDecimal(3), sku(2) -> BigDecimal(7)))
    expect(out.map(_._2).sum == 0)
  }
  pureTest("a zero-weight SKU gets nothing; the rest still conserve the total") {
    val out = SkuMix.allocate(100, Vector(sku(1) -> BigDecimal(0), sku(2) -> BigDecimal(1))).toMap
    expect(out(sku(1)) == 0) and expect(out(sku(2)) == 100)
  }
  pureTest("weights that do not sum to 1 are normalised") {
    val out = SkuMix.allocate(100, Vector(sku(1) -> BigDecimal(3), sku(2) -> BigDecimal(1))).toMap
    expect(out(sku(1)) == 75) and expect(out(sku(2)) == 25)
  }

  // ---------- coverage ratio edges ----------
  pureTest("coverage over 100% when shipped + pipeline exceeds forecast") {
    expect(Coverage.ratio(150, BigDecimal(0), 100).contains(BigDecimal("1.5000")))
  }
  pureTest("coverage of a zero forecast is undefined (None), never a divide-by-zero") {
    expect(Coverage.ratio(10, BigDecimal(5), 0).isEmpty)
  }
  pureTest("an empty leaf set rolls up to no rows") {
    expect(Coverage.rollup(Nil).isEmpty) and expect(Coverage.rollupBySku(Nil).isEmpty)
  }
}
