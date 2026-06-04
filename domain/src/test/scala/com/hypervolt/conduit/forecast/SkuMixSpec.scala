package com.hypervolt.conduit.forecast

import cats.Show
import java.util.UUID
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// Splitting an agent's unit count into a per-SKU forecast (the "Overall Product Sales Mix"): Σ per-SKU == the
// entered total, EXACTLY, for all inputs — no unit invented or lost (doc 12 §1.2).
object SkuMixSpec extends SimpleIOSuite with Checkers {

  private implicit def showAll[A]: Show[A] = Show.fromToString
  private def sku(i: Int): UUID            = UUID.fromString(f"00000000-0000-0000-0000-${i}%012d")

  private val scenario: Gen[(Int, Vector[(UUID, BigDecimal)])] = for {
    total <- Gen.choose(0, 100000)
    n     <- Gen.choose(1, 9)
    ws    <- Gen.listOfN(n, Gen.choose(1, 1000).map(i => BigDecimal(i) / 100)).map(_.toVector)
  } yield (total, ws.zipWithIndex.map { case (w, i) => sku(i) -> w })

  test("the SKU split conserves the total exactly for all inputs") {
    forall(scenario) {
      case (total, weights) =>
        val out = SkuMix.allocate(total, weights)
        expect(out.map(_._2).sum == total) and
          expect(out.length == weights.length) and
          expect(out.forall(_._2 >= 0))
    }
  }

  pureTest("a 100-unit count over 5m/7.5m/10m × colour weights splits per SKU and sums to 100") {
    // a slice of the spreadsheet's Overall Product Sales Mix (weights need not sum to 1; they are normalised)
    val weights = Vector(
      sku(1) -> BigDecimal("0.2281"), // 5m black
      sku(2) -> BigDecimal("0.1304"), // 5m white
      sku(3) -> BigDecimal("0.1510"), // 5m grey
      sku(4) -> BigDecimal("0.1302"), // 7.5m black
      sku(5) -> BigDecimal("0.0850") // 10m black
    )
    val out = SkuMix.allocate(100, weights)
    expect(out.map(_._2).sum == 100) and expect(out.maxBy(_._2)._1 == sku(1)) // 5m black is the largest share
  }

  pureTest("101 over three equal SKUs is 34 + 34 + 33 (largest-remainder, conserving)") {
    val out = SkuMix.allocate(101, Vector(sku(1) -> BigDecimal(1), sku(2) -> BigDecimal(1), sku(3) -> BigDecimal(1)))
    expect(out.map(_._2).sum == 101) and expect(out.map(_._2).sorted == List(33, 34, 34))
  }
}
