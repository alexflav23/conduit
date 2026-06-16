package com.hypervolt.conduit.treasury

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID

// Persistence for the FX hedging program. Contracts live in fx_hedge (extended with program columns in V1_0_84);
// the facility/policy/exposure/approval tables are net-new.
object HedgeProgramRepo {

  def providerByCode(code: String): ConnectionIO[Option[HedgeProvider]] =
    sql"""SELECT id, code, name, adapter, active FROM hedge_provider WHERE code = $code"""
      .query[HedgeProvider]
      .option

  def facilities(entityId: UUID): ConnectionIO[List[HedgeFacility]] =
    sql"""SELECT id, provider_id, entity_id, pair_from, pair_to, credit_limit, limit_currency, interest_free,
                 margin_variation_pct, margin_call_pct, opened_on, status, doc_ref
          FROM hedge_facility WHERE entity_id = $entityId AND status = 'active' ORDER BY opened_on"""
      .query[HedgeFacility]
      .to[List]

  def insertFacility(f: HedgeFacility): ConnectionIO[UUID] =
    sql"""INSERT INTO hedge_facility (provider_id, entity_id, pair_from, pair_to, credit_limit, limit_currency,
            interest_free, margin_variation_pct, margin_call_pct, opened_on, status, doc_ref)
          VALUES (${f.providerId}, ${f.entityId}, ${f.pairFrom}, ${f.pairTo}, ${f.creditLimit}, ${f.limitCurrency},
            ${f.interestFree}, ${f.marginVariationPct}, ${f.marginCallPct}, ${f.openedOn}, ${f.status}, ${f.docRef})
          RETURNING id""".query[UUID].unique

  def upsertPolicy(p: HedgePolicy): ConnectionIO[Int] =
    sql"""INSERT INTO hedge_policy (entity_id, exposure_type, hedge_ratio, tenor_months, payment_terms_days,
            effective_from, effective_to, note)
          VALUES (${p.entityId}, ${p.exposureType}, ${p.hedgeRatio}, ${p.tenorMonths}, ${p.paymentTermsDays},
            ${p.effectiveFrom}, ${p.effectiveTo}, ${p.note})
          ON CONFLICT (entity_id, exposure_type, effective_from) DO UPDATE SET
            hedge_ratio = EXCLUDED.hedge_ratio, tenor_months = EXCLUDED.tenor_months,
            payment_terms_days = EXCLUDED.payment_terms_days, effective_to = EXCLUDED.effective_to,
            note = EXCLUDED.note""".update.run

  def policies(entityId: UUID, asOf: LocalDate): ConnectionIO[List[HedgePolicy]] =
    sql"""SELECT id, entity_id, exposure_type, hedge_ratio, tenor_months, payment_terms_days, effective_from,
                 effective_to, note
          FROM hedge_policy WHERE entity_id = $entityId AND effective_from <= $asOf
            AND (effective_to IS NULL OR effective_to >= $asOf)"""
      .query[HedgePolicy]
      .to[List]

  def upsertExposure(e: ExposureForecast): ConnectionIO[Int] =
    sql"""INSERT INTO hedge_exposure_forecast (entity_id, supplier, exposure_type, period_month, amount_usd, source)
          VALUES (${e.entityId}, ${e.supplier}, ${e.exposureType}, ${e.periodMonth}, ${e.amountUsd}, ${e.source})
          ON CONFLICT (entity_id, supplier, exposure_type, period_month) DO UPDATE SET
            amount_usd = EXCLUDED.amount_usd, source = EXCLUDED.source""".update.run

  def exposures(entityId: UUID, from: LocalDate, to: LocalDate): ConnectionIO[List[ExposureForecast]] =
    sql"""SELECT entity_id, supplier, exposure_type, period_month, amount_usd, source
          FROM hedge_exposure_forecast WHERE entity_id = $entityId AND period_month >= $from AND period_month < $to
          ORDER BY period_month""".query[ExposureForecast].to[List]

  private val contractCols =
    fr"""id, facility_id, provider_id, contract_no, instrument, pair_from, pair_to, contracted_rate, notional,
         notional_used, valid_from, valid_to, status, hedge_ratio, supplier, exposure_type, parent_hedge_id"""

  def contracts(entityId: UUID): ConnectionIO[List[HedgeContract]] =
    (fr"SELECT" ++ contractCols ++ fr"FROM fx_hedge WHERE entity_id = $entityId AND contract_no IS NOT NULL ORDER BY valid_from")
      .query[HedgeContract]
      .to[List]

  def insertContract(entityId: UUID, n: NewHedgeContract, status: String, createdBy: Option[UUID]): ConnectionIO[UUID] =
    sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, notional_used, valid_from,
            valid_to, status, designation, facility_id, provider_id, contract_no, instrument, hedge_ratio, supplier,
            exposure_type, parent_hedge_id, created_by)
          VALUES ($entityId, ${n.pairFrom}, ${n.pairTo}, ${n.contractedRate}, ${n.notional}, 0, ${n.validFrom},
            ${n.validTo}, $status, 'economic', ${n.facilityId}, ${n.providerId}, ${n.contractNo}, ${n.instrument},
            ${n.hedgeRatio}, ${n.supplier}, ${n.exposureType}, ${n.parentHedgeId}, $createdBy)
          RETURNING id""".query[UUID].unique

  def setStatus(hedgeId: UUID, status: String): ConnectionIO[Int] =
    sql"UPDATE fx_hedge SET status = $status WHERE id = $hedgeId".update.run

  def setRateAndMaturity(hedgeId: UUID, rate: BigDecimal, validTo: LocalDate): ConnectionIO[Int] =
    sql"UPDATE fx_hedge SET contracted_rate = $rate, valid_to = $validTo WHERE id = $hedgeId".update.run

  def addApproval(hedgeId: UUID, decision: String, role: String, approverName: Option[String]): ConnectionIO[Int] =
    sql"""INSERT INTO hedge_approval (hedge_id, decision, required_role, approver_name)
          VALUES ($hedgeId, $decision, $role, $approverName)""".update.run

  def sign(approvalId: UUID, userId: UUID): ConnectionIO[Int] =
    sql"""UPDATE hedge_approval SET status = 'signed', approver_user_id = $userId, signed_at = now()
          WHERE id = $approvalId AND status = 'pending'""".update.run

  def approvals(hedgeId: UUID, decision: String): ConnectionIO[List[HedgeApproval]] =
    sql"""SELECT id, hedge_id, decision, required_role, approver_user_id, approver_name, status
          FROM hedge_approval WHERE hedge_id = $hedgeId AND decision = $decision"""
      .query[HedgeApproval]
      .to[List]
}
