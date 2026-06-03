package com.hypervolt.conduit.money

import java.time.LocalDate
import weaver.SimpleIOSuite

object MoneySpec extends SimpleIOSuite {
  import Currency._

  pureTest("addition stays in the same currency and conserves value") {
    val r = Money.of(BigDecimal("10.50"), GBP) + Money.of(BigDecimal("0.25"), GBP)
    expect(r.amount == BigDecimal("10.75")) and expect(r.currency == GBP)
  }

  pureTest("times rounds at the boundary with the supplied policy") {
    val r = Money.of(BigDecimal("1.00"), GBP).times(BigDecimal("0.333"), RoundingPolicy.HalfUp)
    expect(r.amount == BigDecimal("0.33"))
  }

  pureTest("convert applies a provenanced rate and rounds to the target's minor units") {
    val usd  = Money.of(BigDecimal("100.00"), USD)
    val rate = FxRate(USD, GBP, BigDecimal("0.7842"), FxRateType.Spot, "ecb", LocalDate.parse("2026-06-03"))
    val gbp  = usd.convert(rate, RoundingPolicy.HalfUp)
    expect(gbp.currency == GBP) and expect(gbp.amount == BigDecimal("78.42"))
  }

  pureTest("JPY carries zero minor units") {
    val r = Money.of(BigDecimal("1234"), JPY)
    expect(r.amount.scale == 0) and expect(r.amount == BigDecimal(1234))
  }

  pureTest("negation and sign helpers") {
    val r = -Money.of(BigDecimal("5.00"), EUR)
    expect(r.isNegative) and expect(r.amount == BigDecimal("-5.00"))
  }
}
