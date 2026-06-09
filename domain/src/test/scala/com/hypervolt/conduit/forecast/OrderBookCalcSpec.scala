package com.hypervolt.conduit.forecast

import java.time.LocalDate
import weaver.SimpleIOSuite

// doc 26 §4a — the order-book features are reconstructed from pre-origin cohorts only: the book at each prior
// quarter start, the share of it that won within the quarter that ensued, and the created-and-won run-rate.
// Nothing after the origin is visible to any of the three numbers.
object OrderBookCalcSpec extends SimpleIOSuite {

  private val origin = LocalDate.of(2025, 4, 1)

  private def d(created: String, closed: Option[String], won: Boolean, amount: Int): DealRow =
    DealRow(LocalDate.parse(created), closed.map(LocalDate.parse), won, BigDecimal(amount))

  private val deals = List(
    d("2024-07-15", Some("2024-11-15"), won = true, 1000),  // in the Oct cohort, won within it
    d("2024-08-01", Some("2024-12-01"), won = false, 1000), // in the Oct cohort, lost
    d("2024-12-15", Some("2025-02-10"), won = true, 500),   // in the Jan cohort, won within it
    d("2025-01-10", Some("2025-02-20"), won = true, 300),   // created-and-won in Q1'25 = new business
    d("2025-03-01", None, won = false, 2000)                // open at the origin = the book to convert
  )

  pureTest("conversion pools pre-origin cohorts amount-weighted; the open book and run-rate are as-of origin") {
    val (book, conv, newBiz) = OrderBookCalc.context(deals, origin)
    expect(book.contains(BigDecimal(2000))) and    // only the still-open deal
      expect(conv.contains(BigDecimal("0.6"))) and // (1000 + 500) won of (2000 + 500) booked
      expect(newBiz.contains(BigDecimal(150)))     // (0 + 300) / 2 quarters
  }

  pureTest("the order_book model converts the book and spreads the quarter evenly") {
    val h = DemandHistory(
      Vector(LocalDate.of(2025, 1, 1)),
      Vector(BigDecimal(999)),
      openBook = Some(BigDecimal(2000)),
      bookConversion = Some(BigDecimal("0.6")),
      newBusinessQ = Some(BigDecimal(150))
    )
    expect(DemandModel.OrderBook.predict(h, 3) == Vector.fill(3)(BigDecimal(450).setScale(4)))
  }

  pureTest("a stale zombie book converts at its own measured (zero) rate — it cannot inflate the forecast") {
    val zombie               = d("2023-06-01", None, won = false, 5000) // open for years, never converts
    val (book, conv, newBiz) = OrderBookCalc.context(zombie :: deals, origin)
    val h = DemandHistory(
      Vector(LocalDate.of(2025, 1, 1)),
      Vector(BigDecimal(0)),
      openBook = book,
      bookConversion = conv,
      newBusinessQ = newBiz
    )
    // book grows 2000 → 7000 but expected stays 2000×0.6; new business now averages 4 cohorts (300/4)
    expect(book.contains(BigDecimal(7000))) and
      expect(DemandModel.OrderBook.predict(h, 3) == Vector.fill(3)(BigDecimal(425).setScale(4)))
  }

  pureTest("no deal pipeline means no context, and the model degrades to the run-rate") {
    val (book, conv, newBiz) = OrderBookCalc.context(Nil, origin)
    val h = DemandHistory(
      Vector.tabulate(3)(i => LocalDate.of(2025, 1, 1).plusMonths(i.toLong)),
      Vector(BigDecimal(90), BigDecimal(100), BigDecimal(110))
    )
    expect(book.isEmpty) and expect(conv.isEmpty) and expect(newBiz.isEmpty) and
      expect(DemandModel.OrderBook.predict(h, 2) == DemandModel.RunRate3.predict(h, 2))
  }
}
