package com.hypervolt.conduit.commission

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID

private final case class OrderCommHead(
    agentId: Option[UUID],
    channelId: Option[UUID],
    marketId: Option[UUID],
    entityId: Option[UUID],
    currency: String
)
private final case class OrderCommLine(
    lineId: UUID,
    unitPriceExVat: BigDecimal,
    unitCost: BigDecimal,
    qty: Int,
    adlpCategory: String
)

// M5 — accrues sales commission for a placed order (doc 04 §Commission). Resolves the most-specific scheme for the
// order's (team, channel, market, entity) and books a PENDING TigerBeetle accrual per line at the PROVISIONAL
// std-cost margin (trued up to the actual batch margin at posting). Order-level idempotent: a redelivery re-runs
// nothing once accrual entries exist. Naturally dormant — an order with no agent, or no scheme matching, accrues 0.
final class CommissionAccrualService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  def accrueForOrder(orderId: UUID, asOf: Instant): F[Int] =
    (head(orderId), alreadyAccrued(orderId)).tupled.transact(xa).flatMap {
      case (None, _)                             => 0.pure[F]
      case (_, true)                             => 0.pure[F]
      case (Some(h), false) if h.agentId.isEmpty => 0.pure[F]
      case (Some(h), false) =>
        val agentId = h.agentId.get
        (CommissionRepo.candidates, teamOf(agentId)).tupled.transact(xa).flatMap {
          case (candidates, teamId) =>
            CommissionResolver
              .resolve(candidates, teamId, h.channelId.orNull, h.marketId.orNull, h.entityId, asOf) match {
              case None => 0.pure[F]
              case Some(scheme) =>
                val svc = new CommissionService[F](xa, ledger, expenseEntity = h.entityId.fold("uk")(_.toString))
                lines(orderId)
                  .transact(xa)
                  .flatMap(
                    _.traverse(l =>
                      svc.accrue(
                        agentId,
                        scheme.id,
                        Some(orderId),
                        h.currency,
                        scheme,
                        CommissionLineInput(
                          l.unitPriceExVat,
                          l.unitCost,
                          l.qty,
                          l.adlpCategory,
                          exceptionApproved = false
                        )
                      )
                    ).map(_.size)
                  )
            }
        }
    }

  private def head(orderId: UUID): ConnectionIO[Option[OrderCommHead]] =
    sql"""SELECT agent_id, channel_id, market_id, entity_id, txn_currency FROM "order" WHERE id = $orderId"""
      .query[OrderCommHead]
      .option

  private def alreadyAccrued(orderId: UUID): ConnectionIO[Boolean] =
    sql"SELECT count(*) FROM commission_entry WHERE order_id = $orderId AND kind = 'accrual'"
      .query[Int]
      .unique
      .map(_ > 0)

  private def teamOf(agentId: UUID): ConnectionIO[Option[UUID]] =
    sql"SELECT team_id FROM sales_agent WHERE id = $agentId".query[Option[UUID]].option.map(_.flatten)

  // Net unit price (after line discount) and the provisional std cost for the margin basis.
  private def lines(orderId: UUID): ConnectionIO[List[OrderCommLine]] =
    sql"""SELECT ol.id, ol.unit_price_ex_vat * (1 - COALESCE(ol.discount_pct,0)/100),
                 COALESCE(pv.std_cost, 0), ol.qty, COALESCE(ol.adlp_category, 'standard')
          FROM order_line ol JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE ol.order_id = $orderId"""
      .query[OrderCommLine]
      .to[List]
}
