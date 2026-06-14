package com.hypervolt.conduit.forecast

import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// The pure analysis behind forecast-run tracking (doc 26): given the champion selection of two forecast
// origins (policy_selection rows), compute the headline stats, the champion changes, the policy-mix shift, and
// a human-readable narrative of HOW the forecast evolved and WHY. Pure + Docker-free so the evolution report is
// a single source of truth the API serves and the spec pins. The selection rows are an immutable, idempotent
// record per origin (UNIQUE(origin, company)), so a diff between two origins is deterministic and reproducible.
object RunDiff {

  // the structural model family (reads the physical world — shelf/activations/order books) vs the statistical
  // shapes; a champion key may be a blend, so membership is by token containment (doc 26 registry).
  private val structuralTokens =
    List("depletion", "sell_through", "order_book", "mrp_order_book", "retail_funnel", "pantry_reversal")

  def isStructural(policyKey: String): Boolean =
    structuralTokens.exists(policyKey.contains)

  final case class SelRow(company: UUID, policyKey: String, forecast: BigDecimal, actual: BigDecimal)

  final case class RunStats(
      accounts: Int,
      forecastUnits: BigDecimal,
      actualUnits: BigDecimal,
      totalLevelErrorPct: BigDecimal,
      structuralShare: BigDecimal // fraction of accounts on a structural champion
  )

  private def pct(n: BigDecimal, d: BigDecimal): BigDecimal =
    (n.abs / (if (d.abs < BigDecimal(1)) BigDecimal(1) else d.abs) * 100).setScale(1, RoundingMode.HALF_UP)

  // total-level (served-grain) error for a forecast/actual pair — the per-cell headline of the browsable delta.
  def totalLevelErrorPct(forecast: BigDecimal, actual: BigDecimal): BigDecimal = pct(forecast - actual, actual)

  def stats(rows: List[SelRow]): RunStats = {
    val fc     = rows.map(_.forecast).sum
    val ac     = rows.map(_.actual).sum
    val struct = rows.count(r => isStructural(r.policyKey))
    val share  = if (rows.isEmpty) BigDecimal(0) else (BigDecimal(struct) / rows.size).setScale(3, RoundingMode.HALF_UP)
    RunStats(rows.size, fc, ac, pct(fc - ac, ac), share)
  }

  final case class ChampionChange(company: UUID, from: String, to: String)

  final case class RunDiffResult(
      fromStats: RunStats,
      toStats: RunStats,
      errorDeltaPct: BigDecimal, // toError − fromError (negative = improved)
      accountsAdded: List[UUID],
      accountsDropped: List[UUID],
      championChanges: List[ChampionChange],
      policyMixFrom: Map[String, Int],
      policyMixTo: Map[String, Int],
      narrative: List[String]
  )

  private def mix(rows: List[SelRow]): Map[String, Int] =
    rows.groupBy(_.policyKey).map { case (k, v) => k -> v.size }

  def policyMix(rows: List[SelRow]): Map[String, Int] = mix(rows)

  def diff(from: List[SelRow], to: List[SelRow]): RunDiffResult = {
    val fromBy  = from.map(r => r.company -> r).toMap
    val toBy    = to.map(r => r.company -> r).toMap
    val added   = to.map(_.company).filterNot(fromBy.contains).distinct
    val dropped = from.map(_.company).filterNot(toBy.contains).distinct
    val changes = toBy.toList.flatMap {
      case (c, t) =>
        fromBy.get(c).filter(_.policyKey != t.policyKey).map(f => ChampionChange(c, f.policyKey, t.policyKey))
    }
    val fs         = stats(from)
    val ts         = stats(to)
    val errorDelta = (ts.totalLevelErrorPct - fs.totalLevelErrorPct).setScale(1, RoundingMode.HALF_UP)
    RunDiffResult(
      fs,
      ts,
      errorDelta,
      added,
      dropped,
      changes,
      mix(from),
      mix(to),
      narrate(fs, ts, added, dropped, changes, errorDelta)
    )
  }

  private def narrate(
      fs: RunStats,
      ts: RunStats,
      added: List[UUID],
      dropped: List[UUID],
      changes: List[ChampionChange],
      errorDelta: BigDecimal
  ): List[String] = {
    val toStructural  = changes.count(c => !isStructural(c.from) && isStructural(c.to))
    val toStatistical = changes.count(c => isStructural(c.from) && !isStructural(c.to))
    val errorLine =
      if (errorDelta.signum < 0)
        s"Total-level error improved from ${fs.totalLevelErrorPct}% to ${ts.totalLevelErrorPct}% (${errorDelta.abs} pts better)."
      else if (errorDelta.signum > 0)
        s"Total-level error worsened from ${fs.totalLevelErrorPct}% to ${ts.totalLevelErrorPct}% (${errorDelta} pts)."
      else s"Total-level error held at ${ts.totalLevelErrorPct}%."
    val coverageLine =
      s"Coverage: ${ts.accounts} scored accounts (was ${fs.accounts}) — ${added.size} new, ${dropped.size} dropped."
    val structuralMove  = if (toStructural > 0) s"; $toStructural moved onto a structural model (real telemetry)" else ""
    val statisticalMove = if (toStatistical > 0) s"; $toStatistical reverted to a statistical model" else ""
    val championLine =
      if (changes.isEmpty) "No account changed its champion policy."
      else s"${changes.size} account(s) switched champion$structuralMove$statisticalMove."
    val fromShare      = (fs.structuralShare * 100).setScale(0, RoundingMode.HALF_UP)
    val toShare        = (ts.structuralShare * 100).setScale(0, RoundingMode.HALF_UP)
    val structuralLine = s"Structural-champion share: $fromShare% → $toShare% of accounts."
    List(coverageLine, errorLine, championLine, structuralLine)
  }
}
