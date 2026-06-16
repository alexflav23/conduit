package com.hypervolt.conduit.order

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// M4 — the sales backlog projection. order.placed records the committed obligation (from the authoritative order
// lines, not the event payload — the consumer trusts the row, not the wire); recognition draws it down. No GL
// posting (ASC 606 books at dispatch). Idempotent on order_id, so replaying the full historical order book to
// rebuild the baseline is a no-op on re-run. open = committed_ex_vat − Σ recognised revenue for the order.
final class OrderCommitmentService[F[_]: Async](xa: Transactor[F]) {

  // Year-1 UK standard rate; the committed figure is an ex-VAT baseline (matches the revenue_ex_vat basis the
  // recognition draws it down against), VAT shown for the gross commitment.
  private val vatRate = BigDecimal("0.20")

  def record(orderId: UUID): F[Int] =
    sql"""INSERT INTO order_commitment (order_id, entity_id, currency, committed_ex_vat, committed_vat, committed_inc_vat, placed_at)
          SELECT o.id, o.entity_id, o.txn_currency,
                 c.ex, round(c.ex * $vatRate, 2), round(c.ex * (1 + $vatRate), 2), COALESCE(o.created_at, now())
          FROM "order" o
          CROSS JOIN LATERAL (
            SELECT COALESCE(SUM(ol.qty * ol.unit_price_ex_vat * (1 - COALESCE(ol.discount_pct, 0) / 100)), 0) AS ex
            FROM order_line ol WHERE ol.order_id = o.id) c
          WHERE o.id = $orderId
          ON CONFLICT (order_id) DO NOTHING""".update.run.transact(xa)

  def forOrder(orderId: UUID): F[Option[Json]] =
    sql"""SELECT oc.committed_ex_vat, oc.committed_inc_vat, oc.currency, oc.status,
                 COALESCE((SELECT SUM(rr.revenue_ex_vat) FROM revenue_recognition rr WHERE rr.order_id = oc.order_id), 0)
          FROM order_commitment oc WHERE oc.order_id = $orderId"""
      .query[(BigDecimal, BigDecimal, String, String, BigDecimal)]
      .option
      .transact(xa)
      .map(_.map {
        case (ex, inc, ccy, status, recognised) =>
          Json.obj(
            "order_id"          -> orderId.toString.asJson,
            "currency"          -> ccy.asJson,
            "committed_ex_vat"  -> ex.asJson,
            "committed_inc_vat" -> inc.asJson,
            "recognised_ex_vat" -> recognised.asJson,
            "open_ex_vat"       -> (ex - recognised).asJson,
            "status"            -> status.asJson
          )
      })

  // Backlog rollup for shadow validation: committed vs recognised vs open, per entity.
  def backlog: F[List[Json]] =
    sql"""SELECT oc.entity_id, oc.currency, SUM(oc.committed_ex_vat),
                 COALESCE(SUM((SELECT SUM(rr.revenue_ex_vat) FROM revenue_recognition rr WHERE rr.order_id = oc.order_id)), 0)
          FROM order_commitment oc GROUP BY oc.entity_id, oc.currency ORDER BY oc.entity_id"""
      .query[(Option[UUID], String, BigDecimal, BigDecimal)]
      .to[List]
      .transact(xa)
      .map(_.map {
        case (entity, ccy, committed, recognised) =>
          Json.obj(
            "entity_id"         -> entity.map(_.toString).asJson,
            "currency"          -> ccy.asJson,
            "committed_ex_vat"  -> committed.asJson,
            "recognised_ex_vat" -> recognised.asJson,
            "open_ex_vat"       -> (committed - recognised).asJson
          )
      })
}
