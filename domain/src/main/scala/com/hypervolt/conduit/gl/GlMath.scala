package com.hypervolt.conduit.gl

import com.hypervolt.conduit.ledger.LedgerAccountCode
import scala.math.BigDecimal.RoundingMode

// The pure arithmetic of the gl_entry mirror + ASC-830 consolidation (doc 14 §2.4 / §5), lifted out of the
// ConnectionIO services so it unit-tests with no Postgres/TigerBeetle. ConsolidationService / GlProjectionService
// delegate here, so the rule and its test are the single source of truth.
object GlMath {

  import LedgerAccountCode._

  // minor units (integer pence) → presentation BigDecimal, HALF_UP to 2dp — the one money rounding for the mirror.
  def money(minor: BigDecimal): BigDecimal = (minor / 100).setScale(2, RoundingMode.HALF_UP)

  // ASC-830 translation class of a GL role: inventory/equity hold at historic (non-monetary), P&L lines translate
  // at the period rate, everything else (AR/AP/cash/VAT/clearing) at the closing rate.
  def rateClass(role: Int): String =
    if (role == Inv || role == OpeningEquity || role == InvWriteOff) "non_monetary"
    else if (
      role == Revenue || role == CosClearing || role == FeeExpense || role == CarriageExpense ||
      role == CommissionExpense || role == IcMargin
    ) "pnl"
    else "monetary"

  // One account's native (functional) balance and its presentation balance at the resolved rate — both HALF_UP 2dp.
  def translate(netMinor: BigDecimal, rate: BigDecimal): (BigDecimal, BigDecimal) = {
    val func = money(netMinor)
    (func, (func * rate).setScale(2, RoundingMode.HALF_UP))
  }

  // The trial balance ties iff Σ posted debits == Σ posted credits (double-entry, by construction).
  def balanced(totalDebits: BigDecimal, totalCredits: BigDecimal): Boolean = totalDebits == totalCredits

  // The rolled-up consolidation figures + the two translation-integrity verdicts.
  final case class ConsSummary(
      assets: BigDecimal,
      liabilities: BigDecimal,
      equity: BigDecimal,
      cta: BigDecimal,
      fxResidual: BigDecimal,
      nativeSound: Boolean,
      fxClean: Boolean
  )

  def summarise(lines: List[ConsLine]): ConsSummary = {
    val assets      = lines.filter(_.balancePresentation > 0).map(_.balancePresentation).sum
    val liabilities = lines.filter(_.balancePresentation < 0).map(l => -l.balancePresentation).sum
    val equity      = (assets - liabilities).setScale(2, RoundingMode.HALF_UP)
    val cta         = (-equity).setScale(2, RoundingMode.HALF_UP)
    val fxResidual =
      lines
        .filter(_.accountKey.startsWith("FX_CLEARING:"))
        .map(_.balancePresentation)
        .sum
        .setScale(2, RoundingMode.HALF_UP)
    // CTA is sound when every entity's native books balance (Σ functional == 0) — nothing lost in translation.
    val nativeSound =
      lines
        .groupBy(_.entity)
        .values
        .forall(_.map(_.balanceFunctional).sum.setScale(2, RoundingMode.HALF_UP).signum == 0)
    val fxClean = fxResidual.abs < BigDecimal("0.01")
    ConsSummary(assets, liabilities, equity, cta, fxResidual, nativeSound, fxClean)
  }
}
