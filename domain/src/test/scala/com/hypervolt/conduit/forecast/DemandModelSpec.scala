package com.hypervolt.conduit.forecast

import java.time.LocalDate
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// doc 26 §4/§5 — the model families are pure and deterministic (reproducibility is a property, not a hope),
// never negative, and each recovers the pattern it exists for on synthetic data.
object DemandModelSpec extends SimpleIOSuite with Checkers {

  private def hist(qty: Vector[BigDecimal]): DemandHistory = {
    val start = LocalDate.of(2023, 1, 1)
    DemandHistory(Vector.tabulate(qty.length)(i => start.plusMonths(i.toLong)), qty)
  }

  private val series: Gen[Vector[BigDecimal]] =
    Gen.choose(6, 40).flatMap(n => Gen.listOfN(n, Gen.choose(0, 500).map(BigDecimal(_))).map(_.toVector))

  test("every registry model is deterministic and never forecasts negative demand") {
    forall(series) { qs =>
      val h = hist(qs)
      DemandModel.registry
        .map { m =>
          val a = m.predict(h, 6)
          val b = m.predict(h, 6)
          expect(a == b) and expect(a.forall(_ >= 0)) and expect(a.length == 6)
        }
        .reduce(_ and _)
    }
  }

  pureTest("seasonal_naive recovers an exactly-repeating yearly pattern with zero error") {
    val year  = Vector(100, 100, 100, 100, 100, 250, 250, 250, 100, 100, 100, 100).map(BigDecimal(_))
    val h     = hist(year ++ year) // 24 months, exact repetition
    val preds = DemandModel.SeasonalNaive.predict(h, 12)
    expect(preds == year.map(_.setScale(4)))
  }

  pureTest("croston_sba rates a lumpy series between zero and the spike size (never the naive extremes)") {
    val lumpy = Vector.tabulate(24)(i => if (i % 3 == 0) BigDecimal(300) else BigDecimal(0))
    val rate  = new DemandModel.CrostonSba(BigDecimal("0.2")).predict(hist(lumpy), 1).head
    expect(rate > 0) and expect(rate < 300) and expect((rate - BigDecimal(90)).abs < 25) // ≈ (1−α/2)·300/3
  }

  pureTest("depletion: the customer's shelf empties before sell-in resumes (cumulative = max(0, v·m − shelf))") {
    val h     = hist(Vector.fill(12)(BigDecimal(50))).copy(shelfStock = Some(100), activationVelocity = Some(50))
    val preds = DemandModel.Depletion.predict(h, 4)
    // cum: m1=0, m2=0, m3=50, m4=100 → monthly: 0, 0, 50, 50
    expect(preds.map(_.toInt) == Vector(0, 0, 50, 50))
  }

  pureTest("seasonal_ets tracks a trending seasonal series within tolerance (and degrades gracefully under 24m)") {
    val grow  = Vector.tabulate(36)(i => BigDecimal((100 + i) * (if (i % 12 >= 5 && i % 12 <= 7) 2 else 1)))
    val ets   = new DemandModel.SeasonalEts(BigDecimal("0.3"), BigDecimal("0.05"), BigDecimal("0.2"))
    val p     = ets.predict(hist(grow), 12)
    val short = ets.predict(hist(grow.take(18)), 3) // < 2 periods → seasonal-naive shape, never a crash
    expect(p.length == 12) and expect(p.forall(_ >= 0)) and
      expect(p(6) > p(0)) and // the summer seasonal lift survives the fit
      expect(short.length == 3)
  }
}
