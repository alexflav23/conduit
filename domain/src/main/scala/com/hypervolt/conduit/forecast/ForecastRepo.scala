package com.hypervolt.conduit.forecast

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// One forecast line submitted for an account: a quantity for (variant, month, scenario).
final case class ForecastLine(productVariantId: UUID, periodMonth: LocalDate, scenarioId: UUID, qty: Int)

// The dimensions an account/branch carries, for stamping onto a forecast_entry leaf.
final case class AccountDims(
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    marketId: Option[UUID],
    enclosingCustomerId: UUID,
    branchId: UUID
)

// Pure doobie so the cycle/capture writes compose into one transaction with the outbox append (doc 12 §3.2).
object ForecastRepo {

  // Upsert the weekly cycle, returning (id, statusWasOpen). Never reopens a closed cycle (doc 12 §2.2).
  def upsertCycle(code: String, cadence: String, start: LocalDate, end: LocalDate, refTz: String): ConnectionIO[UUID] =
    sql"""INSERT INTO forecast_cycle (code, cadence, period_start, period_end, reference_tz, status, opened_at)
          VALUES ($code, $cadence, $start, $end, $refTz, 'open', now())
          ON CONFLICT (code) DO UPDATE SET code = EXCLUDED.code
          RETURNING id""".query[UUID].unique

  def cycleStatus(cycleId: UUID): ConnectionIO[Option[String]] =
    sql"SELECT status FROM forecast_cycle WHERE id = $cycleId".query[String].option

  def openCycleId(cadence: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM forecast_cycle WHERE cadence = $cadence AND status = 'open' ORDER BY period_start DESC LIMIT 1"
      .query[UUID]
      .option

  // Idempotent generation of the outstanding set (doc 12 §2.2/§2.3): one submission per forecastable LEAF
  // account owned by someone — a master with forecastable branches is excluded (it rolls up from the branches).
  def generateOutstanding(cycleId: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO forecast_submission (cycle_id, forecaster_user_id, company_id, status)
          SELECT $cycleId, COALESCE(p.account_manager_user_id, p.owner_user_id), p.id, 'outstanding'
          FROM party p
          WHERE 'forecastable' = ANY(p.roles) AND p.status = 'active'
            AND COALESCE(p.account_manager_user_id, p.owner_user_id) IS NOT NULL
            AND NOT EXISTS (
              SELECT 1 FROM party c
              WHERE c.parent_party_id = p.id AND 'forecastable' = ANY(c.roles) AND c.status = 'active')
          ON CONFLICT (cycle_id, forecaster_user_id, company_id) DO NOTHING""".update.run

  def closeCycle(cycleId: UUID): ConnectionIO[Int] =
    sql"UPDATE forecast_cycle SET status = 'closed', closed_at = now() WHERE id = $cycleId AND status = 'open'".update.run

  // The submission FOR UPDATE — exists iff this owner was asked for this account at open (else not-owner).
  def submissionFor(cycleId: UUID, owner: UUID, account: UUID): ConnectionIO[Option[(UUID, String)]] =
    sql"""SELECT id, status FROM forecast_submission
          WHERE cycle_id = $cycleId AND forecaster_user_id = $owner AND company_id = $account FOR UPDATE"""
      .query[(UUID, String)]
      .option

  def accountDims(account: UUID): ConnectionIO[Option[AccountDims]] =
    sql"""SELECT p.channel_id, NULL::uuid, p.segment, p.market_id, COALESCE(p.parent_party_id, p.id), p.id
          FROM party p WHERE p.id = $account"""
      .query[AccountDims]
      .option

  // The current (latest non-superseded) estimate for the key — drives no-op suppression + supersession.
  def currentEntry(
      branch: UUID,
      variant: UUID,
      month: LocalDate,
      scenario: UUID,
      source: String
  ): ConnectionIO[Option[(UUID, Int)]] =
    sql"""SELECT id, qty FROM forecast_entry
          WHERE branch_company_id = $branch AND product_variant_id = $variant AND period_month = $month
            AND scenario_id = $scenario AND source = $source AND superseded_by IS NULL
          ORDER BY created_at DESC LIMIT 1""".query[(UUID, Int)].option

  def insertEntry(
      submissionId: Option[UUID],
      cycleId: Option[UUID],
      forecaster: Option[UUID],
      dims: AccountDims,
      variant: UUID,
      month: LocalDate,
      scenario: UUID,
      qty: Int,
      source: String,
      modelVersion: Option[String]
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO forecast_entry
            (submission_id, cycle_id, forecaster_user_id, channel_id, sub_channel_id, segment, market_id,
             company_id, branch_company_id, product_variant_id, period_month, scenario_id, qty, source, model_version)
          VALUES ($submissionId, $cycleId, $forecaster, ${dims.channelId}, ${dims.subChannelId}, ${dims.segment},
             ${dims.marketId}, ${dims.enclosingCustomerId}, ${dims.branchId}, $variant, $month, $scenario, $qty,
             $source, $modelVersion)
          RETURNING id""".query[UUID].unique

  def supersede(priorId: UUID, newId: UUID): ConnectionIO[Int] =
    sql"UPDATE forecast_entry SET superseded_by = $newId WHERE id = $priorId".update.run

  def markSubmitted(submissionId: UUID, device: Option[String], at: Instant): ConnectionIO[Int] =
    sql"""UPDATE forecast_submission SET status = 'submitted', submitted_at = $at, device = $device
          WHERE id = $submissionId""".update.run

  def markSkipped(cycleId: UUID, owner: UUID, account: UUID, reason: String): ConnectionIO[Int] =
    sql"""UPDATE forecast_submission SET status = 'skipped', skip_reason = $reason
          WHERE cycle_id = $cycleId AND forecaster_user_id = $owner AND company_id = $account""".update.run

  // "Who still owes" (doc 12 §8.2) — per owner for a cycle: outstanding / submitted / skipped counts.
  def outstanding(cycleId: UUID): ConnectionIO[List[(UUID, Long, Long, Long)]] =
    sql"""SELECT forecaster_user_id,
                 count(*) FILTER (WHERE status='outstanding'),
                 count(*) FILTER (WHERE status='submitted'),
                 count(*) FILTER (WHERE status='skipped')
          FROM forecast_submission WHERE cycle_id = $cycleId
          GROUP BY forecaster_user_id""".query[(UUID, Long, Long, Long)].to[List]
}
