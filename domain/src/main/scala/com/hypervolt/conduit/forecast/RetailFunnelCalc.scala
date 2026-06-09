package com.hypervolt.conduit.forecast

import java.time.LocalDate

// The retail funnel decomposition (doc 26 §4a, the user's construction): each component is measured SINGLY
// from pre-origin cohorts, then cumulated —
//   • the open book converts per (payment channel × age) — pay_later converts very differently from card,
//     and the sheer passage of time is a measured curve, not an assumption;
//   • created volume runs at its trailing rate and converts at the measured created-and-won-within-quarter
//     rate per payment channel.
// Refund rates (direct_buy / paid_install, Stripe) and abandoned-cart recovery (Crassus) are the two
// remaining subtractive/additive terms — unmeasured until those feeds land, so deliberately absent rather
// than guessed.
object RetailFunnelCalc {

  private val CohortWindow = 4

  // `momentum=false` runs created volume at the trailing quarter's rate; `momentum=true` at the LAST MONTH × 3 —
  // a steeply ramping channel (Retail-Direct grew 160k→304k across four quarters) outruns any quarter-trailing
  // window. Which window is right per channel is the tournament's call, never ours.
  def expectedQuarter(deals: List[DealRow], origin: LocalDate, momentum: Boolean = false): Option[BigDecimal] =
    OrderBookCalc.cohortQuarters(deals, origin, CohortWindow) match {
      case Nil => None
      case quarters =>
        val books   = quarters.map(q => bookBy(deals, q)).foldLeft(Map.empty[(String, Int), BigDecimal])(merge)
        val wins    = quarters.map(q => winsBy(deals, q)).foldLeft(Map.empty[(String, Int), BigDecimal])(merge)
        val totBook = books.values.foldLeft(BigDecimal(0))(_ + _)
        if (totBook <= 0) None
        else {
          val overallConv = wins.values.foldLeft(BigDecimal(0))(_ + _) / totBook
          val bookComponent = bookBy(deals, origin).toList
            .map { case (pb, amount) => amount * convFor(pb, books, wins, overallConv) }
            .foldLeft(BigDecimal(0))(_ + _)
          val created    = quarters.map(q => createdBy(deals, q)).foldLeft(Map.empty[String, BigDecimal])(merge)
          val createdWon = quarters.map(q => createdWonBy(deals, q)).foldLeft(Map.empty[String, BigDecimal])(merge)
          val totCreated = created.values.foldLeft(BigDecimal(0))(_ + _)
          val overallInQ =
            if (totCreated > 0) createdWon.values.foldLeft(BigDecimal(0))(_ + _) / totCreated else BigDecimal(0)
          val createdComponent = createdRate(deals, origin, momentum).toList
            .map {
              case (p, rate) =>
                rate * created.get(p).filter(_ > 0).fold(overallInQ)(c => createdWon.getOrElse(p, BigDecimal(0)) / c)
            }
            .foldLeft(BigDecimal(0))(_ + _)
          Some(bookComponent + createdComponent)
        }
    }

  private def merge[K](a: Map[K, BigDecimal], b: Map[K, BigDecimal]): Map[K, BigDecimal] =
    (a.keySet ++ b.keySet)
      .map(k => k -> (a.getOrElse(k, BigDecimal(0)) + b.getOrElse(k, BigDecimal(0))))
      .toMap

  // bucket conversion falls back to the payment channel's pooled rate, then to the overall book rate —
  // a thin bucket borrows strength instead of inventing a number
  private def convFor(
      pb: (String, Int),
      books: Map[(String, Int), BigDecimal],
      wins: Map[(String, Int), BigDecimal],
      overall: BigDecimal
  ): BigDecimal =
    books.get(pb).filter(_ > 0).map(b => wins.getOrElse(pb, BigDecimal(0)) / b).getOrElse {
      val pBook = books.collect { case ((p, _), v) if p == pb._1 => v }.foldLeft(BigDecimal(0))(_ + _)
      val pWins = wins.collect { case ((p, _), v) if p == pb._1 => v }.foldLeft(BigDecimal(0))(_ + _)
      if (pBook > 0) pWins / pBook else overall
    }

  private def payment(d: DealRow): String = d.payment.getOrElse("other")

  private def bookBy(deals: List[DealRow], at: LocalDate): Map[(String, Int), BigDecimal] =
    deals
      .filter(d => d.created.isBefore(at) && d.closed.forall(!_.isBefore(at)))
      .groupBy(d => (payment(d), OrderBookCalc.ageBucket(d.created, at)))
      .map { case (k, ds) => k -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  private def winsBy(deals: List[DealRow], q: LocalDate): Map[(String, Int), BigDecimal] =
    deals
      .filter(d =>
        d.created.isBefore(q) && d.won &&
          d.closed.exists(c => !c.isBefore(q) && c.isBefore(q.plusMonths(3)))
      )
      .groupBy(d => (payment(d), OrderBookCalc.ageBucket(d.created, q)))
      .map { case (k, ds) => k -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  private def createdBy(deals: List[DealRow], q: LocalDate): Map[String, BigDecimal] =
    deals
      .filter(d => !d.created.isBefore(q) && d.created.isBefore(q.plusMonths(3)))
      .groupBy(payment)
      .map { case (p, ds) => p -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  private def createdWonBy(deals: List[DealRow], q: LocalDate): Map[String, BigDecimal] =
    deals
      .filter(d =>
        !d.created.isBefore(q) && d.created.isBefore(q.plusMonths(3)) && d.won &&
          d.closed.exists(c => !c.isBefore(q) && c.isBefore(q.plusMonths(3)))
      )
      .groupBy(payment)
      .map { case (p, ds) => p -> ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _) }

  // the created-volume run-rate per payment channel: the trailing quarter's amount, or (momentum) the last
  // month's × 3
  private def createdRate(deals: List[DealRow], origin: LocalDate, momentum: Boolean): Map[String, BigDecimal] = {
    val from = if (momentum) origin.minusMonths(1) else origin.minusMonths(3)
    deals
      .filter(d => !d.created.isBefore(from) && d.created.isBefore(origin))
      .groupBy(payment)
      .map {
        case (p, ds) =>
          val total = ds.map(_.amount).foldLeft(BigDecimal(0))(_ + _)
          p -> (if (momentum) total * 3 else total)
      }
  }
}
