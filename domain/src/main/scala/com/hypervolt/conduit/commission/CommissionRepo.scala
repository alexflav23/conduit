package com.hypervolt.conduit.commission

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

object CommissionRepo {

  private type Row =
    (UUID, String, BigDecimal, String, Instant, Option[Instant], Option[UUID], Option[UUID], Option[UUID], Option[UUID])

  def candidates: ConnectionIO[List[ResolvableScheme]] =
    sql"""SELECT s.id, s.basis, s.rate_pct, s.exception_treatment, s.valid_from, s.valid_to,
                 a.team_id, a.channel_id, a.market_id, a.entity_id
          FROM commission_scheme s JOIN commission_scheme_assignment a ON a.scheme_id = s.id
          WHERE s.status = 'active'"""
      .query[Row]
      .to[List]
      .map(_.map { case (id, basis, rate, exc, vf, vt, team, ch, mk, en) =>
        ResolvableScheme(CommissionScheme(id, basis, rate, exc, vf, vt), SchemeAssignment(id, team, ch, mk, en))
      })

  def insertEntry(
      entryId: UUID,
      agentId: UUID,
      schemeId: UUID,
      orderId: Option[UUID],
      basis: BigDecimal,
      rate: BigDecimal,
      amount: BigDecimal,
      currency: String,
      status: String,
      tbTransferId: String
  ): ConnectionIO[Int] =
    sql"""INSERT INTO commission_entry
            (id, agent_id, scheme_id, order_id, basis_amount, rate_applied, amount, currency, kind, status, tb_transfer_id)
          VALUES ($entryId, $agentId, $schemeId, $orderId, $basis, $rate, $amount, $currency, 'accrual', $status, $tbTransferId)""".update.run

  def setStatus(entryId: UUID, status: String): ConnectionIO[Int] =
    sql"UPDATE commission_entry SET status = $status WHERE id = $entryId".update.run

  def postedTotal(agentId: UUID): ConnectionIO[BigDecimal] =
    sql"SELECT COALESCE(SUM(amount), 0) FROM commission_entry WHERE agent_id = $agentId AND status = 'posted'"
      .query[BigDecimal].unique
}
