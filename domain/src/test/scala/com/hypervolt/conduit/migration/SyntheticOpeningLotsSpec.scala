package com.hypervolt.conduit.migration

import cats.Show
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import java.util.UUID
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// Reconciling MRPeasy's weighted-average inventory value to a specific-identification ledger (doc 18 §1.3, §2):
// Σ(per-lot opening value) must tie to the reported total EXACTLY — to the penny — for ALL inputs.
object SyntheticOpeningLotsSpec extends SimpleIOSuite with Checkers {
  import Currency._

  private implicit def showAll[A]: Show[A] = Show.fromToString

  private val loc = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

  private val scenario: Gen[(Money[GBP.type], Vector[SyntheticOpeningLots.OpeningLot])] = for {
    cents <- Gen.choose(1L, 5000000000L)
    n     <- Gen.choose(1, 12)
    lots <-
      Gen
        .listOfN(
          n,
          for {
            qty  <- Gen.choose(1, 5000)
            cost <- Gen.choose(1, 200000).map(i => BigDecimal(i) / 100) // a plausible avg_cost
          } yield SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), loc, qty, cost)
        )
        .map(_.toVector)
  } yield (Money((BigDecimal(cents) / 100).setScale(2), GBP), lots)

  test("the synthetic opening lots' value ties to MRPeasy's reported inventory value exactly") {
    forall(scenario) {
      case (reported, lots) =>
        val allocated     = SyntheticOpeningLots.reconcile(reported, lots)
        val sumValue      = allocated.map(_.value.amount).foldLeft(BigDecimal(0))(_ + _)
        val sumMinor      = allocated.map(_.minorAmount).foldLeft(BigInt(0))(_ + _)
        val reportedMinor = (reported.amount * 100).toBigInt
        expect(sumValue == reported.amount) and
          expect(sumMinor == reportedMinor) and
          expect(allocated.length == lots.length) and
          expect(allocated.forall(_.landedUnitCost.scale == 4))
    }
  }

  pureTest("a £100.01 balance over two equal lots of qty 3 still sums to the penny") {
    val lots = Vector(
      SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), loc, 3, BigDecimal("33.34")),
      SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), loc, 3, BigDecimal("33.34"))
    )
    val allocated = SyntheticOpeningLots.reconcile(Money.of(BigDecimal("100.01"), GBP), lots)
    expect(allocated.map(_.value.amount).foldLeft(BigDecimal(0))(_ + _) == BigDecimal("100.01")) and
      expect(allocated.map(_.minorAmount).foldLeft(BigInt(0))(_ + _) == BigInt(10001))
  }
}
