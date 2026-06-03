package com.hypervolt.conduit.money

import cats.Show
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// The penny problem (doc 14 §1.3 / §5.4): Σ parts == whole, EXACTLY, for all inputs.
object AllocationSpec extends SimpleIOSuite with Checkers {
  import Currency._

  // Render shrunk counterexamples via toString (Money/Vector have no cats.Show by default).
  private implicit def showAll[A]: Show[A] = Show.fromToString

  private val gbpIntegerWeights: Gen[(Money[GBP.type], Vector[BigDecimal])] = for {
    cents <- Gen.choose(0L, 100000000L)
    n     <- Gen.choose(1, 25)
    ws    <- Gen.listOfN(n, Gen.choose(1, 10000)).map(_.toVector.map(i => BigDecimal(i)))
  } yield (Money((BigDecimal(cents) / 100).setScale(2), GBP), ws)

  test("allocate conserves the total exactly (GBP, integer weights)") {
    forall(gbpIntegerWeights) { case (total, ws) =>
      val parts = Money.allocate(total, ws)
      expect(parts.map(_.amount).sum == total.amount) and
        expect(parts.length == ws.length) and
        expect(parts.forall(_.amount.scale == 2))
    }
  }

  private val gbpFractionalWeights: Gen[(Money[GBP.type], Vector[BigDecimal])] = for {
    cents <- Gen.choose(1L, 100000000L)
    n     <- Gen.choose(2, 10)
    ws    <- Gen.listOfN(n, Gen.choose(1, 100000).map(i => BigDecimal(i) / 1000)).map(_.toVector)
  } yield (Money((BigDecimal(cents) / 100).setScale(2), GBP), ws)

  test("allocate conserves the total exactly (GBP, fractional weights)") {
    forall(gbpFractionalWeights) { case (total, ws) =>
      expect(Money.allocate(total, ws).map(_.amount).sum == total.amount)
    }
  }

  private val jpyCase: Gen[(Money[JPY.type], Vector[BigDecimal])] = for {
    yen <- Gen.choose(0L, 100000000L)
    n   <- Gen.choose(1, 25)
    ws  <- Gen.listOfN(n, Gen.choose(1, 10000)).map(_.toVector.map(i => BigDecimal(i)))
  } yield (Money(BigDecimal(yen).setScale(0), JPY), ws)

  test("allocate conserves the total exactly (JPY, zero minor units)") {
    forall(jpyCase) { case (total, ws) =>
      expect(Money.allocate(total, ws).map(_.amount).sum == total.amount)
    }
  }

  pureTest("the classic 100 / 3 split is 33.34 + 33.33 + 33.33") {
    val parts = Money.allocate(Money.of(BigDecimal("100.00"), GBP), Vector(1, 1, 1).map(BigDecimal(_)))
    expect(parts.map(_.amount).sum == BigDecimal("100.00")) and
      expect(parts.map(_.amount).sorted == Vector(BigDecimal("33.33"), BigDecimal("33.33"), BigDecimal("33.34")))
  }
}
