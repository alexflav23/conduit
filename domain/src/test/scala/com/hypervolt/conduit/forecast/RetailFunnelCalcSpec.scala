package com.hypervolt.conduit.forecast

import java.time.LocalDate
import weaver.SimpleIOSuite

// doc 26 §4a — the funnel components are measured singly and cumulated: card and pay_later books convert at
// their own measured rates (1.0 vs 0.5 here), created volume runs at its trailing rate and converts at the
// measured created-and-won-within-quarter rate per channel. Everything pre-origin; nothing leaks.
object RetailFunnelCalcSpec extends SimpleIOSuite {

  private val origin = LocalDate.of(2025, 4, 1)

  private def d(created: String, closed: Option[String], won: Boolean, amount: Int, pay: String): DealRow =
    DealRow(LocalDate.parse(created), closed.map(LocalDate.parse), won, BigDecimal(amount), Some(pay))

  private val deals = List(
    d("2024-12-20", Some("2025-01-15"), won = true, 100, "card"),       // card book cohort: converts
    d("2024-12-20", Some("2025-01-20"), won = true, 100, "pay_later"),  // pay_later book cohort: half converts
    d("2024-12-20", Some("2025-02-01"), won = false, 100, "pay_later"), //   …the other half is lost
    d("2025-01-10", Some("2025-02-15"), won = true, 78, "card"),        // created-and-won in the cohort quarter
    d("2025-03-20", None, won = false, 50, "card"),                     // open card book at the origin
    d("2025-01-05", Some("2025-03-01"), won = true, 100, "pay_later"),  // created-and-won pay_later
    d("2025-01-05", None, won = false, 100, "pay_later")                // open pay_later book at the origin
  )

  pureTest("book × per-channel conversion plus created-volume × in-quarter conversion, cumulated") {
    val expected = RetailFunnelCalc.expectedQuarter(deals, origin)
    // book: 50×1.0 (card) + 100×0.5 (pay_later) = 100
    // created: 128×(78/128) (card) + 200×0.5 (pay_later) = 178
    expect(expected.contains(BigDecimal(278)))
  }

  pureTest("the retail_funnel model spreads the composed quarter over the horizon") {
    val h = DemandHistory(
      Vector(LocalDate.of(2025, 1, 1)),
      Vector(BigDecimal(0)),
      funnelExpectedQ = Some(BigDecimal(278))
    )
    expect(DemandModel.RetailFunnel.predict(h, 3) == Vector.fill(3)(BigDecimal("92.6667")))
  }

  pureTest("no pipeline deals means no funnel, and the model degrades to the run-rate") {
    val h = DemandHistory(
      Vector.tabulate(3)(i => LocalDate.of(2025, 1, 1).plusMonths(i.toLong)),
      Vector(BigDecimal(90), BigDecimal(100), BigDecimal(110))
    )
    expect(RetailFunnelCalc.expectedQuarter(Nil, origin) == None) and
      expect(DemandModel.RetailFunnel.predict(h, 2) == DemandModel.RunRate3.predict(h, 2))
  }
}
