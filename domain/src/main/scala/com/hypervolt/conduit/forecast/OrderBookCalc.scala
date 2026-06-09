package com.hypervolt.conduit.forecast

import java.time.LocalDate

// One deal as the forecaster could see it at the origin: `closed`/`won` reflect ONLY closures that happened
// strictly before the origin (the repo censors them); an open deal carries no closure information.
final case class DealRow(
    created: LocalDate,
    closed: Option[LocalDate],
    won: Boolean,
    amount: BigDecimal,
    payment: Option[String] = None
)

// The censored order-book features (doc 26 §4a). For every prior quarter start q (with q+3m ≤ origin) the
// cohort of deals OPEN at q is reconstructed and followed for the quarter that ensued — all strictly before
// the origin, so nothing leaks. Both conversion (amount-weighted: Σ won-from-book / Σ book) and the
// created-and-won new-business run-rate pool only the trailing four cohorts: pipeline discipline and deal mix
// drift, and 2021 conversion rates poison a 2025 forecast (measured: all-history pooling under-forecast 7×).
object OrderBookCalc {

  private val CohortWindow = 4

  // Conversion is AGE-BUCKETED (fresh <90d, mid <365d, stale ≥365d): a book stuffed with year-old zombie
  // deals is not a book — stale open amount converts at its own (near-zero) measured rate instead of
  // inflating the forecast (measured on Installers: flat conversion over-forecast every 2023-24 origin).
  def context(
      deals: List[DealRow],
      origin: LocalDate
  ): (Option[BigDecimal], Option[BigDecimal], Option[BigDecimal]) =
    cohortQuarters(deals, origin, CohortWindow) match {
      case Nil => (None, None, None)
      case quarters =>
        val books   = quarters.map(q => bookByBucket(deals, q)).foldLeft(Map.empty[Int, BigDecimal])(merge)
        val wins    = quarters.map(q => winsByBucket(deals, q)).foldLeft(Map.empty[Int, BigDecimal])(merge)
        val totBook = books.values.foldLeft(BigDecimal(0))(_ + _)
        if (quarters.isEmpty || totBook <= 0) (None, None, None)
        else {
          val overallConv = wins.values.foldLeft(BigDecimal(0))(_ + _) / totBook
          val originBook  = bookByBucket(deals, origin)
          val expected = originBook.toList
            .map {
              case (b, amount) =>
                amount * books.get(b).filter(_ > 0).fold(overallConv)(bk => wins.getOrElse(b, BigDecimal(0)) / bk)
            }
            .foldLeft(BigDecimal(0))(_ + _)
          val openTotal = originBook.values.foldLeft(BigDecimal(0))(_ + _)
          val newBiz    = quarters.map(newBusinessIn(deals, _))
          (
            Some(openTotal),
            Some(if (openTotal > 0) expected / openTotal else BigDecimal(0)),
            Some(newBiz.foldLeft(BigDecimal(0))(_ + _) / newBiz.length)
          )
        }
    }

  private def merge(a: Map[Int, BigDecimal], b: Map[Int, BigDecimal]): Map[Int, BigDecimal] =
    (a.keySet ++ b.keySet)
      .map(k => k -> (a.getOrElse(k, BigDecimal(0)) + b.getOrElse(k, BigDecimal(0))))
      .toMap

  private[forecast] def ageBucket(created: LocalDate, at: LocalDate): Int = {
    val days = java.time.temporal.ChronoUnit.DAYS.between(created, at)
    if (days < 90) 0 else if (days < 365) 1 else 2
  }

  private[forecast] def quarterStart(d: LocalDate): LocalDate =
    d.withDayOfMonth(1).withMonth(((d.getMonthValue - 1) / 3) * 3 + 1)

  private[forecast] def cohortQuarters(deals: List[DealRow], origin: LocalDate, window: Int): List[LocalDate] =
    deals
      .map(_.created)
      .minOption
      .fold(List.empty[LocalDate])(first =>
        Iterator
          .iterate(quarterStart(first).plusMonths(3))(_.plusMonths(3))
          .takeWhile(q => !q.plusMonths(3).isAfter(origin))
          .toList
          .takeRight(window)
      )

  private def bookByBucket(deals: List[DealRow], at: LocalDate): Map[Int, BigDecimal] =
    deals
      .filter(d => d.created.isBefore(at) && d.closed.forall(!_.isBefore(at)))
      .groupBy(d => ageBucket(d.created, at))
      .map { case (b, ds) => b -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  private def winsByBucket(deals: List[DealRow], q: LocalDate): Map[Int, BigDecimal] =
    deals
      .filter(d =>
        d.created.isBefore(q) && d.won &&
          d.closed.exists(c => !c.isBefore(q) && c.isBefore(q.plusMonths(3)))
      )
      .groupBy(d => ageBucket(d.created, q))
      .map { case (b, ds) => b -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  private def newBusinessIn(deals: List[DealRow], q: LocalDate): BigDecimal =
    deals
      .filter(d =>
        !d.created.isBefore(q) && d.won &&
          d.closed.exists(c => !c.isBefore(q) && c.isBefore(q.plusMonths(3)))
      )
      .map(_.amount)
      .foldLeft(BigDecimal(0))(_ + _)
}
