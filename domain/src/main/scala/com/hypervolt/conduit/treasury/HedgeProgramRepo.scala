package com.hypervolt.conduit.treasury

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID

// Persistence for the FX hedging program. Contracts live in fx_hedge (extended with program columns in V1_0_84);
// the facility/policy/exposure/approval tables are net-new.
object HedgeProgramRepo {

  def operatingEntity: ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM entity WHERE entity_type = 'operating' ORDER BY created_at LIMIT 1".query[UUID].option

  def policiesAll(entityId: UUID): ConnectionIO[List[HedgePolicy]] =
    sql"""SELECT id, entity_id, exposure_type, hedge_ratio, tenor_months, payment_terms_days, effective_from, effective_to, note
          FROM hedge_policy WHERE entity_id = $entityId ORDER BY exposure_type"""
      .query[HedgePolicy]
      .to[List]

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

  // Rebuild the exposure forecast from real data: monthly USD payables = Σ forecast units × the supplier band cost,
  // bridging the forecast variants (HV-5M-W, v3) to the cost SKUs (HV3PROAAUW050T2, mrp) by colour × length, with
  // the band selected by that quarter's total volume. Supplier flips Volex→Luxshare at `transition` (Dec 2026).
  // Idempotent (upsert per month). Recompute whenever the forecast or cost master changes.
  def rebuildExposureForecast(entityId: UUID, transition: LocalDate): ConnectionIO[Int] =
    sql"""WITH fc AS (
            SELECT pc.period_month AS m,
                   (CASE right(v.sku,1) WHEN 'B' THEN 'Black' WHEN 'G' THEN 'Grey' WHEN 'W' THEN 'White' END) AS colour,
                   (CASE substring(v.sku from 'HV-([0-9]+)M') WHEN '5' THEN 5.0 WHEN '75' THEN 7.5 WHEN '10' THEN 10.0 END) AS len,
                   SUM(pc.forecast_qty) AS qty
            FROM pipeline_coverage pc JOIN product_variant v ON v.id = pc.product_variant_id
            WHERE pc.level = 'market' AND pc.product_variant_id IS NOT NULL GROUP BY 1,2,3
          ),
          qtr AS (SELECT date_trunc('quarter', m) q, SUM(qty) qvol FROM fc GROUP BY 1),
          cost AS (
            SELECT (CASE substring(sku,9,2) WHEN 'UB' THEN 'Black' WHEN 'SG' THEN 'Grey' WHEN 'UW' THEN 'White' END) colour,
                   (CASE substring(sku,11,3) WHEN '050' THEN 5.0 WHEN '075' THEN 7.5 WHEN '100' THEN 10.0 END) len,
                   min_qty_per_quarter band, unit_cost
            FROM supplier_cost WHERE supplier = 'Volex'
          ),
          priced AS (
            SELECT fc.m, fc.qty,
                   (SELECT c.unit_cost FROM cost c JOIN qtr ON date_trunc('quarter', fc.m) = qtr.q
                    WHERE c.colour = fc.colour AND c.len = fc.len AND c.band <= qtr.qvol
                    ORDER BY c.band DESC LIMIT 1) unit_usd
            FROM fc
          )
          INSERT INTO hedge_exposure_forecast (entity_id, supplier, exposure_type, period_month, amount_usd, source)
          SELECT $entityId, CASE WHEN p.m < $transition THEN 'Volex' ELSE 'Luxshare' END, 'cm_payment', p.m,
                 round(SUM(p.qty * p.unit_usd), 2), 'forecast x supplier cost'
          FROM priced p GROUP BY p.m
          ON CONFLICT (entity_id, supplier, exposure_type, period_month)
            DO UPDATE SET amount_usd = EXCLUDED.amount_usd, source = EXCLUDED.source""".update.run

  def exposures(entityId: UUID, from: LocalDate, to: LocalDate): ConnectionIO[List[ExposureForecast]] =
    sql"""SELECT entity_id, supplier, exposure_type, period_month, amount_usd, source
          FROM hedge_exposure_forecast WHERE entity_id = $entityId AND period_month >= $from AND period_month < $to
          ORDER BY period_month""".query[ExposureForecast].to[List]

  // Recompute the effectiveness stream: hedged (blended) vs counterfactual all-spot GBP for each exposure month
  // covered by an executed/extended contract, using the real FX-register spot. Idempotent (upsert per month).
  def rebuildEffectiveness(entityId: UUID): ConnectionIO[Int] =
    sql"""WITH spot AS (
            SELECT as_of m, rate FROM exchange_rate WHERE base = 'GBP' AND quote = 'USD' AND rate_type = 'spot'
          ),
          cov AS (
            SELECT hef.period_month m, hef.supplier, hef.amount_usd, h.contracted_rate hedge_rate, h.hedge_ratio ratio, h.contract_no
            FROM hedge_exposure_forecast hef
            JOIN fx_hedge h ON h.entity_id = hef.entity_id AND h.status IN ('executed','extended')
              AND hef.period_month BETWEEN h.valid_from AND h.valid_to
            WHERE hef.entity_id = $entityId
          )
          INSERT INTO hedge_effectiveness (entity_id, period_month, supplier, exposure_usd, hedge_ratio, hedge_rate,
            spot_rate, effective_rate, hedged_gbp, spot_gbp, saving_gbp, contract_no)
          SELECT $entityId, cov.m, cov.supplier, cov.amount_usd, cov.ratio, cov.hedge_rate, s.rate,
            round(1 / (cov.ratio / cov.hedge_rate + (1 - cov.ratio) / s.rate), 8),
            round(cov.amount_usd * (cov.ratio / cov.hedge_rate + (1 - cov.ratio) / s.rate), 2),
            round(cov.amount_usd / s.rate, 2),
            round(cov.amount_usd / s.rate - cov.amount_usd * (cov.ratio / cov.hedge_rate + (1 - cov.ratio) / s.rate), 2),
            cov.contract_no
          FROM cov JOIN spot s ON s.m = cov.m
          ON CONFLICT (entity_id, period_month, supplier) DO UPDATE SET
            exposure_usd = EXCLUDED.exposure_usd, hedge_ratio = EXCLUDED.hedge_ratio, hedge_rate = EXCLUDED.hedge_rate,
            spot_rate = EXCLUDED.spot_rate, effective_rate = EXCLUDED.effective_rate, hedged_gbp = EXCLUDED.hedged_gbp,
            spot_gbp = EXCLUDED.spot_gbp, saving_gbp = EXCLUDED.saving_gbp, contract_no = EXCLUDED.contract_no,
            computed_at = now()""".update.run

  def effectiveness(entityId: UUID): ConnectionIO[List[EffectivenessRow]] =
    sql"""SELECT period_month, supplier, exposure_usd, hedge_ratio, hedge_rate, spot_rate, effective_rate,
                 hedged_gbp, spot_gbp, saving_gbp, contract_no
          FROM hedge_effectiveness WHERE entity_id = $entityId ORDER BY period_month"""
      .query[EffectivenessRow]
      .to[List]

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
