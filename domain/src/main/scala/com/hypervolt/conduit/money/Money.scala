package com.hypervolt.conduit.money

import scala.math.BigDecimal.RoundingMode

// Currency-tagged decimal money (doc 14 §1.1). No Double/Float ever touches it. `Money` is phantom-typed
// by the currency singleton type, so `usd + eur` is a *compile* error — the only way across currencies is
// `convert`, which takes a provenanced FxRate. Stored as NUMERIC(18,4); presentation rounds to minorUnits.
final case class Money[C <: Currency](amount: BigDecimal, currency: C) {

  def +(that: Money[C]): Money[C] = copy(amount = amount + that.amount)
  def -(that: Money[C]): Money[C] = copy(amount = amount - that.amount)
  def unary_- : Money[C]          = copy(amount = -amount)

  // Scalar multiply (× qty, × rate, × pct) takes an explicit RoundingPolicy and rounds at this boundary.
  def times(factor: BigDecimal, policy: RoundingPolicy): Money[C] =
    copy(amount = (amount * factor).setScale(currency.minorUnits, policy.mode))

  def roundToMinorUnits(policy: RoundingPolicy): Money[C] =
    copy(amount = amount.setScale(currency.minorUnits, policy.mode))

  // Cross-currency conversion is the *only* way across currencies, and it is provenanced by the FxRate.
  def convert[D <: Currency](fx: FxRate[C, D], policy: RoundingPolicy): Money[D] =
    Money((amount * fx.rate).setScale(fx.to.minorUnits, policy.mode), fx.to)

  def isZero: Boolean        = amount.signum == 0
  def isPositive: Boolean    = amount.signum > 0
  def isNegative: Boolean    = amount.signum < 0
  def render: String =
    s"${amount.setScale(currency.minorUnits, currency.defaultRounding).bigDecimal.toPlainString} ${currency.code}"
}

object Money {

  def of[C <: Currency](amount: BigDecimal, currency: C): Money[C] =
    Money(amount.setScale(currency.minorUnits, currency.defaultRounding), currency)

  def zero[C <: Currency](currency: C): Money[C] =
    Money(BigDecimal(0).setScale(currency.minorUnits), currency)

  // Conserving allocation via largest-remainder (doc 14 §1.3): distribute floor shares in minor units,
  // then hand the leftover units one-by-one to the largest fractional remainders. Σ parts == total, EXACTLY.
  // Computed entirely in exact integer arithmetic on minor units — no floats, no division rounding error.
  def allocate[C <: Currency](total: Money[C], weights: Vector[BigDecimal]): Vector[Money[C]] = {
    require(weights.nonEmpty, "weights must be non-empty")
    require(weights.forall(_ >= 0), "weights must be non-negative")

    val scale = total.currency.minorUnits
    val unit  = BigInt(10).pow(scale)
    val totalMinor: BigInt =
      (total.amount.setScale(scale, RoundingMode.UNNECESSARY) * BigDecimal(unit)).toBigIntExact
        .getOrElse(throw new IllegalArgumentException(s"total ${total.amount} not exact at $scale dp"))

    // Promote weights to a common integer basis so all subsequent maths are exact.
    val maxScale  = weights.map(_.scale).foldLeft(0)(_ max _)
    val weightUnit = BigInt(10).pow(maxScale)
    val intWeights: Vector[BigInt] =
      weights.map(w => (w * BigDecimal(weightUnit)).toBigIntExact.getOrElse(BigInt(0)))
    val sumWeights = intWeights.sum
    require(sumWeights > 0, "weights must sum to > 0")

    val products   = intWeights.map(_ * totalMinor)
    val floored    = products.map(_ / sumWeights)
    val remainders = products.map(_ % sumWeights)
    val deficit    = (totalMinor - floored.sum).toInt // leftover minor units; 0 <= deficit < n

    // Indices that get one extra minor unit: largest remainder first, ties broken by lowest index.
    val bumped: Set[Int] =
      remainders.zipWithIndex.sortBy { case (r, i) => (-r, i) }.take(deficit).map(_._2).toSet

    floored.zipWithIndex.map { case (f, i) =>
      val minor = if (bumped.contains(i)) f + 1 else f
      Money((BigDecimal(minor) / BigDecimal(unit)).setScale(scale), total.currency)
    }
  }
}
