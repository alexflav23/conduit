package com.hypervolt.conduit.supply

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

// The demand→revenue waterfall for a SKU in a month (the most vital mechanism). Each stage is a DISTINCT
// quantity — they do not equate — assembled from the system of record so the chain is traceable end to end:
//
//   sales_forecast → cm_committed → cm_produced → delivered → ordered (achieved sales) → shipped → revenue
//
// forecast = H6Q (pipeline_coverage, default scenario, all channels, this SKU); cm_committed/cm_produced from
// the firm PO + production actuals (supply side); delivered = received into our stock; ordered = actual customer
// orders; shipped = dispatched (sell-in); revenue = recognised, the same basis that posts to TigerBeetle — so
// the shipped→revenue tail is provable in the immutable ledger (doc 04 §Ledger).
object WaterfallRepo {

  def waterfall(variant: UUID, period: LocalDate): ConnectionIO[Json] = {
    val monthEnd = period.plusMonths(1)
    (
      forecast(variant, period),
      committed(variant, period, monthEnd),
      produced(variant, period, monthEnd),
      delivered(variant, period, monthEnd),
      ordered(variant, period, monthEnd),
      shipped(variant, period),
      revenue(variant, period)
    ).tupled.map {
      case (f, c, p, d, o, s, rev) =>
        def pct(n: Int, base: Int): Json =
          (if (base == 0) Json.Null
           else BigDecimal(n * 100.0 / base).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString.asJson)
        Json.obj(
          "product_variant_id" -> variant.toString.asJson,
          "period_month"       -> period.toString.asJson,
          "stages" -> Json.obj(
            "sales_forecast" -> f.asJson,
            "cm_committed"   -> c.asJson,
            "cm_produced"    -> p.asJson,
            "delivered"      -> d.asJson,
            "ordered"        -> o.asJson,
            "shipped"        -> s.asJson
          ),
          "revenue_ex_vat" -> rev.toString.asJson,
          "conversion" -> Json.obj(
            "commit_vs_forecast"    -> pct(c, f),
            "produced_vs_commit"    -> pct(p, c), // the CM attainment — shortfall extends to the next window
            "delivered_vs_produced" -> pct(d, p),
            "shipped_vs_ordered"    -> pct(s, o),
            "shipped_vs_forecast"   -> pct(s, f)
          )
        )
    }
  }

  private def forecast(variant: UUID, period: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(pc.forecast_qty),0)::int FROM pipeline_coverage pc
          JOIN forecast_scenario sc ON sc.id = pc.scenario_id AND sc.is_default
          WHERE pc.product_variant_id = $variant AND pc.period_month = $period AND pc.level = 'market'"""
      .query[Int]
      .unique

  private def committed(variant: UUID, period: LocalDate, monthEnd: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(qty),0)::int FROM supply_commitment
          WHERE product_variant_id = $variant AND target_date >= $period AND target_date < $monthEnd"""
      .query[Int]
      .unique

  private def produced(variant: UUID, period: LocalDate, monthEnd: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(produced_qty),0)::int FROM production_actual
          WHERE product_variant_id = $variant AND target_date >= $period AND target_date < $monthEnd"""
      .query[Int]
      .unique

  private def delivered(variant: UUID, period: LocalDate, monthEnd: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(qty),0)::int FROM lot_batch
          WHERE product_variant_id = $variant AND received_date >= $period AND received_date < $monthEnd"""
      .query[Int]
      .unique

  private def ordered(variant: UUID, period: LocalDate, monthEnd: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(ol.qty),0)::int FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE ol.product_variant_id = $variant
            AND o.order_date >= $period AND o.order_date < ($monthEnd::date + 1)"""
      .query[Int]
      .unique

  private def shipped(variant: UUID, period: LocalDate): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(dl.qty),0)::int
          FROM dispatch d JOIN dispatch_line dl ON dl.dispatch_id = d.id JOIN order_line ol ON ol.id = dl.order_line_id
          WHERE ol.product_variant_id = $variant
            AND date_trunc('month', d.date)::date = $period"""
      .query[Int]
      .unique

  private def revenue(variant: UUID, period: LocalDate): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(dl.qty * ol.unit_price_ex_vat * (1 - ol.discount_pct/100)),0)
          FROM dispatch d JOIN dispatch_line dl ON dl.dispatch_id = d.id JOIN order_line ol ON ol.id = dl.order_line_id
          WHERE ol.product_variant_id = $variant AND date_trunc('month', d.date)::date = $period"""
      .query[BigDecimal]
      .unique
}
