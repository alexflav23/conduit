package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

// Materialises sell-in vs sell-through vs overhang per account/period (doc 12 §7). Sell-in counts dispatched
// units regardless of generation; sell-through and overhang count v3 only (the V2/V3 rule — v2 units would
// otherwise read as forever-on-shelf). Overhang is the CUMULATIVE shipped-not-activated at each period end.
// Rebuilt for one account at a time (the dispatch.* / activation.recorded consumer's effect); delete+insert
// makes it idempotent and replayable.
final class SellThroughProjector[F[_]: Async](xa: Transactor[F]) {

  def recompute(company: UUID): F[Int] =
    (for {
      channel <- companyChannel(company)
      sellIn  <- sellInByMonth(company)
      sellTh  <- sellThroughByMonth(company)
      _       <- sql"DELETE FROM sell_through WHERE company_id = $company".update.run
      months = (sellIn.keySet ++ sellTh.keySet).toList.sorted
      n <- writeCumulative(company, channel, months, sellIn, sellTh)
    } yield n).transact(xa)

  private def companyChannel(company: UUID): ConnectionIO[Option[UUID]] =
    sql"SELECT channel_id FROM party WHERE id = $company".query[Option[UUID]].option.map(_.flatten)

  private def sellInByMonth(company: UUID): ConnectionIO[Map[LocalDate, Int]] =
    sql"""SELECT date_trunc('month', d.date)::date, COALESCE(SUM(dl.qty),0)::int
          FROM dispatch d JOIN "order" o ON o.id = d.order_id JOIN dispatch_line dl ON dl.dispatch_id = d.id
          WHERE o.sold_to_party_id = $company
          GROUP BY 1""".query[(LocalDate, Int)].to[List].map(_.toMap)

  private def sellThroughByMonth(company: UUID): ConnectionIO[Map[LocalDate, Int]] =
    sql"""SELECT date_trunc('month', a.activated_at)::date, COUNT(*)::int
          FROM activation a JOIN serial_unit s ON s.serial_no = a.serial
          WHERE s.generation = 'v3' AND s.company_id = $company
          GROUP BY 1""".query[(LocalDate, Int)].to[List].map(_.toMap)

  private def writeCumulative(
      company: UUID,
      channel: Option[UUID],
      months: List[LocalDate],
      sellIn: Map[LocalDate, Int],
      sellTh: Map[LocalDate, Int]
  ): ConnectionIO[Int] = {
    val rows = months
      .foldLeft((0, 0, List.empty[(LocalDate, Int, Int, Int)])) {
        case ((cumIn, cumTh, acc), m) =>
          val in     = sellIn.getOrElse(m, 0)
          val th     = sellTh.getOrElse(m, 0)
          val nextIn = cumIn + in
          val nextTh = cumTh + th
          (nextIn, nextTh, (m, in, th, nextIn - nextTh) :: acc)
      }
      ._3
      .reverse
    rows
      .traverse_ {
        case (m, in, th, overhang) =>
          sql"""INSERT INTO sell_through (company_id, channel_id, period_month, sell_in_qty, sell_through_qty, overhang_qty, generation_scope)
            VALUES ($company, $channel, $m, $in, $th, $overhang, 'v3')""".update.run.void
      }
      .as(rows.size)
  }
}
