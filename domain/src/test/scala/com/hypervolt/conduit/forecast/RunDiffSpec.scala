package com.hypervolt.conduit.forecast

import java.util.UUID
import weaver.SimpleIOSuite

// Forecast-run evolution analysis (doc 26) — the diff between two origins' champion selections, Docker-free.
object RunDiffSpec extends SimpleIOSuite {

  private val a = UUID.randomUUID()
  private val b = UUID.randomUUID()
  private val c = UUID.randomUUID()

  private def row(co: UUID, key: String, f: BigDecimal, act: BigDecimal) = RunDiff.SelRow(co, key, f, act)

  pureTest("structural classification: telemetry models structural, shapes statistical, blends by token") {
    expect(RunDiff.isStructural("depletion")) and
      expect(RunDiff.isStructural("blend:depletion+runrate3")) and
      expect(!RunDiff.isStructural("runrate3")) and
      expect(!RunDiff.isStructural("seasonal_ets"))
  }

  pureTest("stats: total-level error and structural share") {
    val s = RunDiff.stats(List(row(a, "depletion", 120, 100), row(b, "runrate3", 80, 100)))
    // Σforecast 200, Σactual 200 → 0% total-level error; 1 of 2 structural → 0.5
    expect(s.accounts == 2) and
      expect(s.totalLevelErrorPct == BigDecimal("0.0")) and
      expect(s.structuralShare == BigDecimal("0.500"))
  }

  pureTest("stats: total-level error nets offsetting account errors (the served-grain headline)") {
    val s = RunDiff.stats(List(row(a, "runrate3", 150, 100), row(b, "runrate3", 50, 100)))
    // Σforecast 200 vs Σactual 200 → 0% even though each account is 50% off
    expect(s.totalLevelErrorPct == BigDecimal("0.0"))
  }

  pureTest("diff: champion changes, added/dropped accounts, and the move toward structural") {
    val from = List(row(a, "runrate3", 100, 100), row(b, "runrate3", 100, 100))
    val to   = List(row(a, "depletion", 100, 100), row(c, "runrate3", 100, 100))
    val d    = RunDiff.diff(from, to)
    expect(d.accountsAdded == List(c)) and
      expect(d.accountsDropped == List(b)) and
      expect(d.championChanges == List(RunDiff.ChampionChange(a, "runrate3", "depletion"))) and
      expect(d.narrative.exists(_.contains("structural")))
  }

  pureTest("diff: error-delta sign — improvement is negative") {
    val from = List(row(a, "runrate3", 130, 100))  // 30%
    val to   = List(row(a, "depletion", 110, 100)) // 10%
    val d    = RunDiff.diff(from, to)
    expect(d.errorDeltaPct == BigDecimal("-20.0")) and
      expect(d.narrative.exists(_.contains("improved")))
  }
}
