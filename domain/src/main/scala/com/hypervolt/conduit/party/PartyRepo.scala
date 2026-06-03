package com.hypervolt.conduit.party

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

object PartyRepo {

  def create(
      displayName: String,
      partyType: String,
      isOrganization: Boolean,
      channelId: Option[UUID],
      marketId: Option[UUID],
      customerPoRequired: Boolean
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO party (display_name, party_type, is_organization, channel_id, market_id, customer_po_required)
          VALUES ($displayName, $partyType, $isOrganization, $channelId, $marketId, $customerPoRequired)
          RETURNING id""".query[UUID].unique

  def addBillingProfile(
      partyId: UUID,
      billingName: String,
      currency: String,
      paymentTermsDays: Int,
      taxRegimeDefault: Option[String]
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, tax_regime_default)
          VALUES ($partyId, $billingName, $currency, $paymentTermsDays, $taxRegimeDefault)
          RETURNING id""".query[UUID].unique

  def addCreditProfile(
      partyId: UUID,
      creditLimit: BigDecimal,
      currency: String,
      termsDays: Int,
      policy: String,
      scope: String
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO credit_profile (party_id, credit_limit, currency, terms_days, policy, scope)
          VALUES ($partyId, $creditLimit, $currency, $termsDays, $policy, $scope)
          RETURNING id""".query[UUID].unique
}
