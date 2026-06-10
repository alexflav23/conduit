package com.hypervolt.conduit.forecast

import java.time.LocalDate
import weaver.SimpleIOSuite

// doc 26 §4a — the MRPeasy open book: un-dispatched orders at origin ARE next month's dispatch floor (the
// measured order→dispatch lag), with organic flow scaled by the measured book-coverage ratio so booked demand
// isn't counted twice. Months beyond the book's reach fall back to the run rate.
object MrpOrderBookSpec extends SimpleIOSuite {

  private def hist(
      qty: Vector[Int],
      book: Option[BigDecimal] = None,
      ratio: Option[BigDecimal] = None
  ): DemandHistory = {
    val start = LocalDate.of(2025, 1, 1)
    DemandHistory(
      Vector.tabulate(qty.length)(i => start.plusMonths(i.toLong)),
      qty.map(BigDecimal(_)),
      mrpOpenBook = book,
      mrpBookRatio = ratio
    )
  }

  pureTest("month 1 = open book + the unbooked share of organic flow; months 2+ revert to the run rate") {
    // run rate 100; book 250; measured 40% of a month's dispatches are pre-booked → organic 100·0.6 = 60
    val h = hist(Vector(100, 100, 100), Some(BigDecimal(250)), Some(BigDecimal("0.4")))
    val p = DemandModel.MrpOrderBook.predict(h, 3)
    expect(p.head.toInt == 310) and expect(p.drop(1).map(_.toInt) == Vector(100, 100))
  }

  pureTest("an unmeasured ratio defaults conservatively low — the book is mostly additive") {
    val h = hist(Vector(100, 100, 100), Some(BigDecimal(50)), None)
    expect(DemandModel.MrpOrderBook.predict(h, 1).head.toInt == 140) // 50 + 100·0.9
  }

  pureTest("a fully-booked account adds no organic flow on top of the book") {
    val h = hist(Vector(100, 100, 100), Some(BigDecimal(120)), Some(BigDecimal(1)))
    expect(DemandModel.MrpOrderBook.predict(h, 1).head.toInt == 120)
  }

  pureTest("no open book degrades to the run-rate") {
    val h = hist(Vector(100, 120, 80))
    expect(DemandModel.MrpOrderBook.predict(h, 2) == DemandModel.RunRate3.predict(h, 2))
  }
}
