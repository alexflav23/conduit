package com.hypervolt.conduit.forecast

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Read-side queries for the H6Q surface (doc 12 §11). Returns Json so the routes stay thin and the layer
// projection (doc 05 §3) is applied uniformly at the route boundary.
object ForecastQueryRepo {

  def cycles(status: Option[String]): ConnectionIO[List[Json]] = {
    val base = fr"SELECT id, code, cadence, period_start, period_end, status FROM forecast_cycle"
    val filt = status.fold(Fragment.empty)(s => fr"WHERE status = $s")
    (base ++ filt ++ fr"ORDER BY period_start DESC LIMIT 52")
      .query[(UUID, String, String, LocalDate, LocalDate, String)]
      .to[List]
      .map(_.map {
        case (id, code, cadence, ps, pe, st) =>
          Json.obj(
            "id"           -> id.toString.asJson,
            "code"         -> code.asJson,
            "cadence"      -> cadence.asJson,
            "period_start" -> ps.toString.asJson,
            "period_end"   -> pe.toString.asJson,
            "status"       -> st.asJson
          )
      })
  }

  def scenarioByType(scType: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM forecast_scenario WHERE type = $scType AND toggle_basis IS NULL".query[UUID].option

  def scenarios: ConnectionIO[List[Json]] =
    sql"SELECT id, type, name, toggle_basis, is_default FROM forecast_scenario ORDER BY type, toggle_basis NULLS FIRST"
      .query[(UUID, String, String, Option[String], Boolean)]
      .to[List]
      .map(_.map {
        case (id, t, n, tb, d) =>
          Json.obj(
            "id"           -> id.toString.asJson,
            "type"         -> t.asJson,
            "name"         -> n.asJson,
            "toggle_basis" -> tb.asJson,
            "is_default"   -> d.asJson
          )
      })

  // The owner's accounts this cycle, prefilled with the current (non-superseded) estimates — the capture grid
  // (doc 12 §11 GET /h6q/my-forecasts). New SKUs are picked up live by the UI from the catalogue endpoint.
  def myForecasts(owner: UUID, cycleId: UUID): ConnectionIO[List[Json]] =
    for {
      accounts <-
        sql"""SELECT s.company_id, p.display_name, s.status, s.submitted_at
                        FROM forecast_submission s JOIN party p ON p.id = s.company_id
                        WHERE s.cycle_id = $cycleId AND s.forecaster_user_id = $owner
                        ORDER BY p.display_name"""
          .query[(UUID, String, String, Option[Instant])]
          .to[List]
      entries <-
        sql"""SELECT branch_company_id, product_variant_id, period_month, scenario_id, qty
                        FROM forecast_entry
                        WHERE forecaster_user_id = $owner AND cycle_id = $cycleId AND superseded_by IS NULL
                          AND branch_company_id IS NOT NULL AND product_variant_id IS NOT NULL"""
          .query[(UUID, UUID, LocalDate, UUID, Int)]
          .to[List]
    } yield accounts.map {
      case (acct, name, status, submittedAt) =>
        val lines = entries.filter(_._1 == acct).map {
          case (_, v, m, sc, qty) =>
            Json.obj(
              "variant"  -> v.toString.asJson,
              "period"   -> m.toString.asJson,
              "scenario" -> sc.toString.asJson,
              "qty"      -> qty.asJson
            )
        }
        Json.obj(
          "company_id"        -> acct.toString.asJson,
          "name"              -> name.asJson,
          "status"            -> status.asJson,
          "last_submitted_at" -> submittedAt.map(_.toString).asJson,
          "lines"             -> lines.asJson
        )
    }

  def outstanding(cycleId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT s.forecaster_user_id, u.name,
                 count(*) FILTER (WHERE s.status='outstanding'),
                 count(*) FILTER (WHERE s.status='submitted'),
                 count(*) FILTER (WHERE s.status='skipped')
          FROM forecast_submission s LEFT JOIN app_user u ON u.id = s.forecaster_user_id
          WHERE s.cycle_id = $cycleId GROUP BY s.forecaster_user_id, u.name ORDER BY 3 DESC"""
      .query[(UUID, Option[String], Long, Long, Long)]
      .to[List]
      .map(_.map {
        case (uid, name, out, sub, skip) =>
          Json.obj(
            "forecaster"           -> uid.toString.asJson,
            "name"                 -> name.asJson,
            "accounts_outstanding" -> out.asJson,
            "submitted"            -> sub.asJson,
            "skipped"              -> skip.asJson
          )
      })

  // The coverage board at one level (doc 12 §8.2). Only unit fields are materialised — commercial/profitability
  // overlays are derived at read time when the layer is present (doc 12 §8.3), so a volume-only viewer's payload
  // simply has no money (absent, not zeroed).
  def coverage(market: UUID, period: LocalDate, scenario: UUID, level: String): ConnectionIO[List[Json]] =
    sql"""SELECT level, market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id, agent_user_id,
                 forecast_qty, weighted_pipeline_qty, shipped_qty, activated_qty, coverage_pct,
                 coverage_ex_account_pct, wow_delta, forecast_source
          FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario AND level = $level
          ORDER BY forecast_qty DESC"""
      .query[CoverageJsonRow]
      .to[List]
      .map(_.map(_.json))

  // Reconcile (doc 12 §11 GET /h6q/coverage/reconcile): the branch axis and the agent axis must tie.
  def reconcile(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Json] =
    sql"""SELECT level, COALESCE(SUM(forecast_qty),0), COALESCE(SUM(weighted_pipeline_qty),0),
                 COALESCE(SUM(shipped_qty),0), COALESCE(SUM(activated_qty),0)
          FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario AND level IN ('branch','agent')
          GROUP BY level"""
      .query[(String, Long, BigDecimal, Long, Long)]
      .to[List]
      .map { rows =>
        val byLevel = rows.map(r => r._1 -> r).toMap
        def axis(l: String): Json =
          byLevel.get(l).fold(Json.obj()) {
            case (_, f, wp, sh, av) =>
              Json.obj(
                "forecast"          -> f.asJson,
                "weighted_pipeline" -> wp.asJson,
                "shipped"           -> sh.asJson,
                "activated"         -> av.asJson
              )
          }
        val branchF = byLevel.get("branch").map(_._2).getOrElse(0L)
        val agentF  = byLevel.get("agent").map(_._2).getOrElse(0L)
        Json.obj("branch_axis" -> axis("branch"), "agent_axis" -> axis("agent"), "ties" -> (branchF == agentF).asJson)
      }
}

private final case class CoverageJsonRow(
    level: String,
    marketId: Option[UUID],
    channelId: Option[UUID],
    subChannelId: Option[UUID],
    segment: Option[String],
    companyId: Option[UUID],
    branchId: Option[UUID],
    agentUserId: Option[UUID],
    forecastQty: Int,
    weightedPipelineQty: BigDecimal,
    shippedQty: Int,
    activatedQty: Int,
    coveragePct: Option[BigDecimal],
    coverageExAccountPct: Option[BigDecimal],
    wowDelta: Option[BigDecimal],
    forecastSource: Option[String]
) {
  def json: Json =
    Json.obj(
      "level"                   -> level.asJson,
      "market_id"               -> marketId.map(_.toString).asJson,
      "channel_id"              -> channelId.map(_.toString).asJson,
      "sub_channel_id"          -> subChannelId.map(_.toString).asJson,
      "segment"                 -> segment.asJson,
      "company_id"              -> companyId.map(_.toString).asJson,
      "branch_company_id"       -> branchId.map(_.toString).asJson,
      "agent_user_id"           -> agentUserId.map(_.toString).asJson,
      "forecast_qty"            -> forecastQty.asJson,
      "weighted_pipeline_qty"   -> weightedPipelineQty.asJson,
      "shipped_qty"             -> shippedQty.asJson,
      "activated_qty"           -> activatedQty.asJson,
      "coverage_pct"            -> coveragePct.asJson,
      "coverage_ex_account_pct" -> coverageExAccountPct.asJson,
      "wow_delta"               -> wowDelta.asJson,
      "forecast_source"         -> forecastSource.asJson
    )
}
