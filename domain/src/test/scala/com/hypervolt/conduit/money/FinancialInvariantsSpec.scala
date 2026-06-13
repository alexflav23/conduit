package com.hypervolt.conduit.money

import cats.Show
import java.time.LocalDate
import org.scalacheck.Gen
import scala.math.BigDecimal.RoundingMode
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// The financial-core invariants beyond allocation (doc 14 §1.4/§5.4): FX round-trips within a bounded rounding
// error, reversal exactly negates, and scaling is well-behaved at the boundaries. ScalaCheck properties — the
// money core is the one place a silent rounding/sign bug is unrecoverable, so it earns property coverage, not
// just examples. Ledger double-entry conservation is structural (every TB transfer debits one account and
// credits another by the same integer minor units) and is exercised end-to-end by LedgerIntegrationSuite.
object FinancialInvariantsSpec extends SimpleIOSuite with Checkers {
  import Currency._

  private implicit def showAll[A]: Show[A] = Show.fromToString
  private val asOf                         = LocalDate.of(2026, 1, 1)
  private val hu                           = RoundingPolicy.HalfUp

  private val gbp: Gen[Money[GBP.type]] =
    Gen.choose(0L, 100000000L).map(c => Money((BigDecimal(c) / 100).setScale(2), GBP))

  // A round-trippable rate in [0.5, 2.0] with its exact inverse carried to high precision.
  private val rate: Gen[BigDecimal] = Gen.choose(500L, 2000L).map(i => (BigDecimal(i) / 1000).setScale(6))

  test("FX round-trip is bounded: GBP→EUR→GBP lands within two minor units of the original") {
    forall(for { m <- gbp; r <- rate } yield (m, r)) {
      case (m, r) =>
        val toEur = m.convert(FxRate(GBP, EUR, r, FxRateType.Spot, "test", asOf), hu)
        val back = toEur.convert(
          FxRate(EUR, GBP, (BigDecimal(1) / r).setScale(12, RoundingMode.HALF_UP), FxRateType.Spot, "test", asOf),
          hu
        )
        // two roundings (each ≤ half a minor unit, the second magnified by 1/r ≤ 2) bound the drift at ≤ 0.02.
        expect((back.amount - m.amount).abs <= BigDecimal("0.02")) and expect(back.currency == GBP)
    }
  }

  test("conversion preserves zero and sign (a rate in [0.5,2] cannot flip or vanish a nonzero amount)") {
    forall(for { m <- gbp; r <- rate } yield (m, r)) {
      case (m, r) =>
        val fx        = FxRate(GBP, EUR, r, FxRateType.Spot, "test", asOf)
        val converted = m.convert(fx, hu)
        expect(Money.zero(GBP).convert(fx, hu).isZero) and
          expect(if (m.isZero) converted.isZero else converted.isPositive)
    }
  }

  test("reversal exactly negates: m + (-m) == 0 and -(-m) == m, for all amounts") {
    forall(gbp)(m => expect((m + m.unary_-).isZero) and expect(m.unary_-.unary_- == m) and expect((m - m).isZero))
  }

  test("scaling boundaries: times(0) is zero, times(1) is the rounded identity, times(2) == m + m") {
    forall(gbp) { m =>
      expect(m.times(BigDecimal(0), hu).isZero) and
        expect(m.times(BigDecimal(1), hu) == m.roundToMinorUnits(hu)) and
        expect(m.times(BigDecimal(2), hu) == (m + m).roundToMinorUnits(hu))
    }
  }
}
