package com.hypervolt.conduit.purchasing

import java.time.LocalDate
import java.util.UUID
import weaver.SimpleIOSuite

// The commitment-ladder signal logic (M9c): zoning, the deviation signal, and the issue decision — Docker-free.
object CommitmentLadderSpec extends SimpleIOSuite {

  private val v         = UUID.randomUUID()
  private val firmUntil = LocalDate.parse("2026-09-01")
  private val flexUntil = LocalDate.parse("2026-11-01")

  pureTest("zoneFor: before firm = firm, between = flex, beyond = indicative") {
    expect(CommitmentLadder.zoneFor(LocalDate.parse("2026-08-01"), firmUntil, flexUntil) == "firm") and
      expect(CommitmentLadder.zoneFor(LocalDate.parse("2026-10-01"), firmUntil, flexUntil) == "flex") and
      expect(CommitmentLadder.zoneFor(LocalDate.parse("2026-12-01"), firmUntil, flexUntil) == "indicative")
  }

  pureTest("maxDeviation: the largest in-window relative move; beyond-flex and zero-prev buckets do not signal") {
    val inWin  = (v, LocalDate.parse("2026-08-01"))
    val inWin2 = (v, LocalDate.parse("2026-10-01"))
    val beyond = (v, LocalDate.parse("2026-12-01"))
    val last   = Map(inWin -> BigDecimal(100), inWin2 -> BigDecimal(200), beyond -> BigDecimal(100))
    val now    = Map(inWin -> BigDecimal(120), inWin2 -> BigDecimal(260), beyond -> BigDecimal(999))
    // inWin: 20%, inWin2: 30% (the max), beyond: ignored
    expect(CommitmentLadder.maxDeviation(now, last, flexUntil) == BigDecimal(30))
  }

  pureTest("maxDeviation: no prior communication → zero signal") {
    expect(CommitmentLadder.maxDeviation(Map.empty, Map.empty, flexUntil) == BigDecimal(0))
  }

  pureTest("shouldIssue: force, first-ever, or past-threshold; otherwise hold") {
    expect(CommitmentLadder.shouldIssue(force = true, lastEmpty = false, BigDecimal(0), BigDecimal(10))) and
      expect(CommitmentLadder.shouldIssue(force = false, lastEmpty = true, BigDecimal(0), BigDecimal(10))) and
      expect(CommitmentLadder.shouldIssue(force = false, lastEmpty = false, BigDecimal(15), BigDecimal(10))) and
      expect(!CommitmentLadder.shouldIssue(force = false, lastEmpty = false, BigDecimal(5), BigDecimal(10)))
  }
}
