package com.hypervolt.conduit.forecast

import java.time.LocalDate
import weaver.SimpleIOSuite

// doc 26 §5 — the technique tournament, promoted from the report-side prototype: selection on ALL prior origins,
// blends only when they earn it, thin evidence degrades to the pooled-argmin single (the old champion), and the
// variance guard demotes winners that blew up on any single origin (the thin-channel failure mode).
object PolicySelectorSpec extends SimpleIOSuite {

  private val o1 = LocalDate.of(2024, 7, 1)
  private val o2 = LocalDate.of(2024, 10, 1)
  private val o3 = LocalDate.of(2025, 1, 1)

  private def ev(origin: LocalDate, model: String, forecast: Int, actual: Int): PolicyEvidence =
    PolicyEvidence(origin, origin, model, BigDecimal(forecast), BigDecimal(actual))

  pureTest("a uniformly best single model wins outright when blending the laggards cannot help") {
    val evidence = List(o1, o2, o3).flatMap(o =>
      List(ev(o, "ewma", 98, 100), ev(o, "runrate3", 70, 100), ev(o, "seasonal_naive", 60, 100))
    )
    expect(PolicySelector.select(evidence) == Policy.single("ewma"))
  }

  pureTest("two models erring on opposite sides are blended — the blend beats both singles") {
    val evidence = List(o1, o2, o3).flatMap(o =>
      List(ev(o, "ewma", 120, 100), ev(o, "holt_fast", 80, 100), ev(o, "seasonal_naive", 200, 100))
    )
    val selected = PolicySelector.select(evidence)
    expect(selected.weights.keySet == Set("ewma", "holt_fast")) and
      expect(selected.key == "blend(ewma+holt_fast)")
  }

  pureTest("thin evidence with an unforecastable winner still demotes to the run-rate") {
    val evidence = List(o1, o2).flatMap(o => List(ev(o, "ewma", 250, 100), ev(o, "holt_fast", 300, 100)))
    expect(PolicySelector.select(evidence) == Policy.single("runrate3"))
  }

  pureTest("fewer than three origins of evidence degrades to the pooled-argmin single — never a blend") {
    val evidence = List(o1, o2).flatMap(o =>
      List(ev(o, "ewma", 120, 100), ev(o, "holt_fast", 80, 100), ev(o, "seasonal_naive", 101, 100))
    )
    expect(PolicySelector.select(evidence) == Policy.single("seasonal_naive"))
  }

  pureTest("no evidence at all means the seasonal-naive baseline") {
    expect(PolicySelector.select(Nil) == Policy.single(DemandModel.SeasonalNaive.key))
  }

  pureTest("the variance guard demotes a winner whose worst origin blew up to the robust run-rate") {
    val evidence = List(o1, o2, o3).flatMap(o =>
      List(
        ev(o, "ewma", if (o == o3) 300 else 100, 100),
        ev(o, "croston_sba", if (o == o3) 280 else 110, 100)
      )
    )
    expect(PolicySelector.select(evidence) == Policy.single("runrate3"))
  }

  pureTest("near-ties resolve to the more stable candidate, not the marginally better pooled score") {
    val evidence = List(
      ev(o1, "ewma", 100, 100),
      ev(o2, "ewma", 100, 100),
      ev(o3, "ewma", 79, 100),
      ev(o1, "holt_fast", 93, 100),
      ev(o2, "holt_fast", 93, 100),
      ev(o3, "holt_fast", 93, 100)
    )
    expect(PolicySelector.select(evidence) == Policy.single("holt_fast"))
  }

  pureTest("a blended policy's prediction is the weighted sum of its members") {
    val h = DemandHistory(
      Vector.tabulate(6)(i => LocalDate.of(2025, 1, 1).plusMonths(i.toLong)),
      Vector.fill(6)(BigDecimal(100))
    )
    val blend = Policy(Map("ewma" -> BigDecimal("0.5"), "runrate3" -> BigDecimal("0.5")))
    expect(blend.predict(h, 3).forall(_ == BigDecimal(100).setScale(4)))
  }
}
