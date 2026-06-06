package com.hypervolt.conduit.credit

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

final case class CreditTerms(paymentTermsDays: Int, creditLimit: Option[BigDecimal], currency: Option[String])

// Per-invoice-contact credit terms (doc 02 §C). The contractual payment terms drive the invoice due date (set at
// dispatch) and therefore the cash waterfall. Terms resolve from billing_profile.payment_terms_days, then
// credit_profile.terms_days, then a 30-day default. Admins (finance) edit them via CreditRoutes.
final class CreditTermsService[F[_]: Async](xa: Transactor[F]) {

  def get(party: UUID): F[CreditTerms] =
    sql"""SELECT
            COALESCE(
              (SELECT bp.payment_terms_days FROM billing_profile bp WHERE bp.party_id = $party AND bp.status='active' ORDER BY bp.id LIMIT 1),
              (SELECT cp.terms_days FROM credit_profile cp WHERE cp.party_id = $party ORDER BY cp.id LIMIT 1),
              30),
            (SELECT cp.credit_limit FROM credit_profile cp WHERE cp.party_id = $party ORDER BY cp.id LIMIT 1),
            (SELECT cp.currency FROM credit_profile cp WHERE cp.party_id = $party ORDER BY cp.id LIMIT 1)"""
      .query[CreditTerms]
      .unique
      .transact(xa)

  // Upsert the contact's terms: billing_profile carries the invoice payment terms; credit_profile carries the
  // limit (+ a mirrored terms_days). Both rows are created on first set so a brand-new contact is admin-able.
  def set(
      party: UUID,
      paymentTermsDays: Int,
      creditLimit: Option[BigDecimal],
      currency: Option[String]
  ): F[Either[String, Unit]] =
    if (paymentTermsDays < 0) "payment_terms_days must be >= 0".asLeft[Unit].pure[F]
    else
      (upsertBilling(party, paymentTermsDays, currency) *> upsertCredit(party, paymentTermsDays, creditLimit, currency))
        .transact(xa)
        .as(().asRight[String])

  private def upsertBilling(party: UUID, days: Int, currency: Option[String]): ConnectionIO[Int] =
    sql"UPDATE billing_profile SET payment_terms_days = $days WHERE party_id = $party".update.run.flatMap {
      case 0 =>
        sql"""INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days)
              SELECT $party, p.display_name, ${currency.getOrElse(
          "GBP"
        )}, $days FROM party p WHERE p.id = $party""".update.run
      case n => n.pure[ConnectionIO]
    }

  private def upsertCredit(
      party: UUID,
      days: Int,
      creditLimit: Option[BigDecimal],
      currency: Option[String]
  ): ConnectionIO[Int] =
    sql"""UPDATE credit_profile SET terms_days = $days,
            credit_limit = COALESCE($creditLimit, credit_limit) WHERE party_id = $party""".update.run.flatMap {
      case 0 =>
        sql"""INSERT INTO credit_profile (party_id, credit_limit, currency, terms_days)
              VALUES ($party, ${creditLimit.getOrElse(BigDecimal(0))}, ${currency.getOrElse(
          "GBP"
        )}, $days)""".update.run
      case n => n.pure[ConnectionIO]
    }
}
