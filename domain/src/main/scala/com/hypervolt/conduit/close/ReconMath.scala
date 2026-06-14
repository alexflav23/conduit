package com.hypervolt.conduit.close

import scala.math.BigDecimal.RoundingMode

// The pure core of an automated reconciliation (doc 14 §5): minor-unit money rounding and the
// expected/actual → variance/status verdict. Lifted out of ReconciliationService so it unit-tests with no
// Postgres/TigerBeetle; the service delegates here.
object ReconMath {

  def money(minor: BigDecimal): BigDecimal = (minor / 100).setScale(2, RoundingMode.HALF_UP)

  final case class Eval(variance: BigDecimal, status: String)

  // matched iff actual ties expected to the penny; any non-zero variance is an exception that blocks the lock.
  def evaluate(expected: BigDecimal, actual: BigDecimal): Eval = {
    val variance = (actual - expected).setScale(2, RoundingMode.HALF_UP)
    Eval(variance, if (variance.signum == 0) "matched" else "exception")
  }
}
