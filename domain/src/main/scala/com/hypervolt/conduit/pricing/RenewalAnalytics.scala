package com.hypervolt.conduit.pricing

import cats.effect.Async
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant

// Term / renewal-rate analytics (doc 24 §5.8) — DERIVED from the agreement validity timestamps + the renews_from
// links, never a stored lifecycle status. "Due" = an agreement whose validity ends in the window; "renewed" = a due
// agreement that a successor agreement points at via renews_from. Logo retention = renewed ÷ due, broken down by the
// customers' governed sector. Feeds the desk renewals worklist + Horizons (doc 21) — no parallel store.
final class RenewalAnalytics[F[_]: Async](xa: Transactor[F]) {

  // (due, renewed) overall in the window.
  def logoRetention(start: Instant, end: Instant): F[(Long, Long)] =
    sql"""SELECT
            count(*) FILTER (WHERE pa.valid_to >= $start AND pa.valid_to < $end),
            count(*) FILTER (WHERE pa.valid_to >= $start AND pa.valid_to < $end
                             AND EXISTS (SELECT 1 FROM price_agreement s WHERE s.renews_from = pa.id))
          FROM price_agreement pa
          WHERE pa.surface = 'customer'"""
      .query[(Long, Long)]
      .unique
      .transact(xa)

  // (sector, due, renewed) — the renewals worklist broken down by the customer's sector.
  def bySector(start: Instant, end: Instant): F[List[(String, Long, Long)]] =
    sql"""SELECT COALESCE(p.sector, 'unknown'),
            count(DISTINCT pa.id) FILTER (WHERE pa.valid_to >= $start AND pa.valid_to < $end),
            count(DISTINCT pa.id) FILTER (WHERE pa.valid_to >= $start AND pa.valid_to < $end
                             AND EXISTS (SELECT 1 FROM price_agreement s WHERE s.renews_from = pa.id))
          FROM price_agreement pa
            JOIN price_agreement_customer pac ON pac.agreement_id = pa.id
            JOIN party p ON p.id = pac.party_id
          WHERE pa.surface = 'customer'
          GROUP BY COALESCE(p.sector, 'unknown')"""
      .query[(String, Long, Long)]
      .to[List]
      .transact(xa)
}
