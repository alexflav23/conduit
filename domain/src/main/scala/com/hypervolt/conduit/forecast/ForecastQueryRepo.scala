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

  // Catalogue-live (doc 12 §1.2.4): the capture grid reads the live variants, so a new SKU is forecastable the
  // moment it exists — no schema/config change.
  def variants: ConnectionIO[List[Json]] =
    sql"""SELECT v.id, v.sku, f.name FROM product_variant v JOIN product_family f ON f.id = v.family_id
          ORDER BY f.name, v.sku LIMIT 200"""
      .query[(UUID, String, String)]
      .to[List]
      .map(_.map {
        case (id, sku, fam) =>
          Json.obj("id" -> id.toString.asJson, "sku" -> sku.asJson, "family" -> fam.asJson)
      })

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

  // Accuracy rows for an owner/account (doc 12 §9). mape vs the 0.20 Volex margin is the discipline metric.
  def accuracy(company: UUID, period: LocalDate, basis: String): ConnectionIO[List[Json]] =
    sql"""SELECT forecaster_user_id, product_variant_id, forecast_qty, actual_qty, error, bias, mape, actual_basis
          FROM forecast_accuracy
          WHERE company_id = $company AND period_month = $period AND actual_basis = $basis
          ORDER BY abs(error) DESC"""
      .query[(Option[UUID], Option[UUID], Int, Int, Int, Option[BigDecimal], Option[BigDecimal], String)]
      .to[List]
      .map(_.map {
        case (f, v, fq, aq, err, bias, mape, b) =>
          Json.obj(
            "forecaster"    -> f.map(_.toString).asJson,
            "variant"       -> v.map(_.toString).asJson,
            "forecast_qty"  -> fq.asJson,
            "actual_qty"    -> aq.asJson,
            "error"         -> err.asJson,
            "bias"          -> bias.map(_.toString).asJson,
            "mape"          -> mape.map(_.toString).asJson,
            "actual_basis"  -> b.asJson,
            "within_margin" -> mape.map(_ <= BigDecimal("0.20")).asJson
          )
      })

  // The forecast total CURRENT AS OF an instant, reconstructed from append-only history (doc 12 §4.5): an entry
  // counts if it was created on/before `asOf` and was not yet superseded then. This is what makes WoW a query
  // over history rather than a stored snapshot.
  def forecastAsOf(market: UUID, period: LocalDate, scenario: UUID, asOf: Instant): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(e.qty),0)::int FROM forecast_entry e
          WHERE e.market_id = $market AND e.period_month = $period AND e.scenario_id = $scenario
            AND e.source = 'manual' AND e.created_at <= $asOf
            AND (e.superseded_by IS NULL
                 OR EXISTS (SELECT 1 FROM forecast_entry n WHERE n.id = e.superseded_by AND n.created_at > $asOf))"""
      .query[Int]
      .unique

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
  // variant = None returns the all-SKU total rows (product_variant_id IS NULL — the default board); a value
  // returns the per-SKU breakdown for that variant. Per-SKU granularity is load-bearing (different SKUs don't
  // equate), so the projector materialises both.
  def coverage(
      market: UUID,
      period: LocalDate,
      scenario: UUID,
      level: String,
      variant: Option[UUID]
  ): ConnectionIO[List[Json]] = {
    val variantF = variant.fold(fr"AND product_variant_id IS NULL")(v => fr"AND product_variant_id = $v")
    (fr"""SELECT level, market_id, channel_id, sub_channel_id, segment, company_id, branch_company_id, agent_user_id,
                 product_variant_id, forecast_qty, weighted_pipeline_qty, shipped_qty, activated_qty, coverage_pct,
                 coverage_ex_account_pct, wow_delta, forecast_source
          FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario AND level = $level """
      ++ variantF ++ fr"ORDER BY forecast_qty DESC")
      .query[CoverageJsonRow]
      .to[List]
      .map(_.map(_.json))
  }

  // The per-SKU breakdown at a level (the Quarterly-Forecast-Dashboard view: the total, split by SKU).
  def coverageBySku(market: UUID, period: LocalDate, scenario: UUID, level: String): ConnectionIO[List[Json]] =
    sql"""SELECT pc.level, pc.market_id, pc.channel_id, pc.sub_channel_id, pc.segment, pc.company_id,
                 pc.branch_company_id, pc.agent_user_id, pc.product_variant_id, pc.forecast_qty,
                 pc.weighted_pipeline_qty, pc.shipped_qty, pc.activated_qty, pc.coverage_pct,
                 pc.coverage_ex_account_pct, pc.wow_delta, pc.forecast_source, v.sku
          FROM pipeline_coverage pc LEFT JOIN product_variant v ON v.id = pc.product_variant_id
          WHERE pc.market_id = $market AND pc.period_month = $period AND pc.scenario_id = $scenario
            AND pc.level = $level AND pc.product_variant_id IS NOT NULL
          ORDER BY pc.forecast_qty DESC"""
      .query[
        (
            String,
            Option[UUID],
            Option[UUID],
            Option[UUID],
            Option[String],
            Option[UUID],
            Option[UUID],
            Option[UUID],
            Option[UUID],
            Int,
            BigDecimal,
            Int,
            Int,
            Option[BigDecimal],
            Option[BigDecimal],
            Option[BigDecimal],
            Option[String],
            Option[String]
        )
      ]
      .to[List]
      .map(_.map { t =>
        CoverageJsonRow(
          t._1,
          t._2,
          t._3,
          t._4,
          t._5,
          t._6,
          t._7,
          t._8,
          t._9,
          t._10,
          t._11,
          t._12,
          t._13,
          t._14,
          t._15,
          t._16,
          t._17
        ).json
          .deepMerge(Json.obj("sku" -> t._18.asJson))
      })

  // The full demand matrix: every SKU (row) across every month (column) at once — the spreadsheet view.
  // Market-level per-SKU rows for one scenario; the desk pivots into SKU x month with totals.
  def coverageMatrix(market: UUID, scenario: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT v.sku, f.name, to_char(pc.period_month, 'YYYY-MM'),
                 pc.forecast_qty, pc.shipped_qty, pc.activated_qty
          FROM pipeline_coverage pc
          JOIN product_variant v ON v.id = pc.product_variant_id
          LEFT JOIN product_family f ON f.id = v.family_id
          WHERE pc.market_id = $market AND pc.scenario_id = $scenario
            AND pc.level = 'market' AND pc.product_variant_id IS NOT NULL
          ORDER BY v.sku, pc.period_month"""
      .query[(String, Option[String], String, Int, Int, Int)]
      .to[List]
      .map(_.map {
        case (sku, fam, month, fc, sh, act) =>
          Json.obj(
            "sku"      -> sku.asJson,
            "family"   -> fam.asJson,
            "month"    -> month.asJson,
            "forecast" -> fc.asJson,
            "shipped"  -> sh.asJson,
            "activated" -> act.asJson
          )
      })

  // Reconcile (doc 12 §11 GET /h6q/coverage/reconcile): the branch axis and the agent axis must tie.
  def reconcile(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[Json] =
    sql"""SELECT level, COALESCE(SUM(forecast_qty),0), COALESCE(SUM(weighted_pipeline_qty),0),
                 COALESCE(SUM(shipped_qty),0), COALESCE(SUM(activated_qty),0)
          FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario
            AND level IN ('branch','agent') AND product_variant_id IS NULL
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
    productVariantId: Option[UUID],
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
      "product_variant_id"      -> productVariantId.map(_.toString).asJson,
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
