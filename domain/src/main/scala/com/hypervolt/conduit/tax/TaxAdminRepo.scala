package com.hypervolt.conduit.tax

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

// Read models for the tax admin desk (doc 16 §10). Rows are built as snake_case Json so the data-layer projection
// (Projection.projectFor) can drop amount fields for a volume-only principal and PII for a non-pii principal.
object TaxAdminRepo {

  def regimes: ConnectionIO[List[Json]] =
    sql"""SELECT code, rate_percent, jurisdiction, kind, tax_type, economic_zone, rounding_policy, provider
          FROM tax_regime ORDER BY code"""
      .query[(String, BigDecimal, Option[String], String, String, Option[String], String, String)]
      .to[List]
      .map(_.map {
        case (code, rate, jur, kind, tt, ez, rp, prov) =>
          Json.obj(
            "code"            -> code.asJson,
            "rate_percent"    -> rate.asJson,
            "jurisdiction"    -> jur.asJson,
            "kind"            -> kind.asJson,
            "tax_type"        -> tt.asJson,
            "economic_zone"   -> ez.asJson,
            "rounding_policy" -> rp.asJson,
            "provider"        -> prov.asJson
          )
      })

  def rates(jurisdiction: Option[String], taxType: Option[String], asOf: Option[LocalDate]): ConnectionIO[List[Json]] =
    sql"""SELECT id, tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind,
            recoverable, effective_from, effective_to, status, source
          FROM tax_rate
          WHERE (jurisdiction = $jurisdiction OR $jurisdiction IS NULL)
            AND (tax_type = $taxType OR $taxType IS NULL)
            AND ($asOf IS NULL OR (effective_from <= $asOf AND (effective_to IS NULL OR effective_to > $asOf)))
          ORDER BY jurisdiction, tax_type, level, region NULLS FIRST, effective_from DESC"""
      .query[
        (
            UUID,
            String,
            String,
            Option[String],
            Option[String],
            String,
            Option[String],
            String,
            BigDecimal,
            String,
            Boolean,
            LocalDate,
            Option[LocalDate],
            String,
            String
        )
      ]
      .to[List]
      .map(_.map {
        case (id, tt, jur, region, pc, level, cat, name, rate, kind, rec, from, to, status, source) =>
          Json.obj(
            "id"                -> id.toString.asJson,
            "tax_type"          -> tt.asJson,
            "jurisdiction"      -> jur.asJson,
            "region"            -> region.asJson,
            "postcode_prefix"   -> pc.asJson,
            "level"             -> level.asJson,
            "tax_category_code" -> cat.asJson,
            "name"              -> name.asJson,
            "rate_pct"          -> rate.asJson,
            "kind"              -> kind.asJson,
            "recoverable"       -> rec.asJson,
            "effective_from"    -> from.toString.asJson,
            "effective_to"      -> to.map(_.toString).asJson,
            "status"            -> status.asJson,
            "source"            -> source.asJson
          )
      })

  def routing: ConnectionIO[List[Json]] =
    sql"""SELECT id, market_id, jurisdiction, tax_type, provider, priority, status, effective_from
          FROM tax_routing ORDER BY priority, jurisdiction NULLS LAST"""
      .query[(UUID, Option[UUID], Option[String], Option[String], String, Int, String, LocalDate)]
      .to[List]
      .map(_.map {
        case (id, market, jur, tt, prov, prio, status, from) =>
          Json.obj(
            "id"             -> id.toString.asJson,
            "market_id"      -> market.map(_.toString).asJson,
            "jurisdiction"   -> jur.asJson,
            "tax_type"       -> tt.asJson,
            "provider"       -> prov.asJson,
            "priority"       -> prio.asJson,
            "status"         -> status.asJson,
            "effective_from" -> from.toString.asJson
          )
      })

  def categories: ConnectionIO[List[Json]] =
    sql"SELECT code, name, default_kind FROM tax_category ORDER BY code"
      .query[(String, String, String)]
      .to[List]
      .map(_.map {
        case (code, name, kind) => Json.obj("code" -> code.asJson, "name" -> name.asJson, "default_kind" -> kind.asJson)
      })

  def registrations(entityId: Option[UUID]): ConnectionIO[List[Json]] =
    sql"""SELECT id, entity_id, tax_type, number, jurisdiction, region, registration_kind, collects_tax, effective_from
          FROM tax_registration
          WHERE (entity_id = $entityId OR $entityId IS NULL)
          ORDER BY jurisdiction, region NULLS FIRST"""
      .query[(UUID, UUID, String, Option[String], String, Option[String], String, Boolean, Option[LocalDate])]
      .to[List]
      .map(_.map {
        case (id, e, tt, num, jur, region, kind, collects, from) =>
          Json.obj(
            "id"                -> id.toString.asJson,
            "entity_id"         -> e.toString.asJson,
            "tax_type"          -> tt.asJson,
            "number"            -> num.asJson,
            "jurisdiction"      -> jur.asJson,
            "region"            -> region.asJson,
            "registration_kind" -> kind.asJson,
            "collects_tax"      -> collects.asJson,
            "effective_from"    -> from.map(_.toString).asJson
          )
      })

  def nexus(entityId: Option[UUID], status: Option[String]): ConnectionIO[List[Json]] =
    sql"""SELECT id, entity_id, jurisdiction, region, threshold_amount, threshold_txn_count, sales_to_date,
            txn_count_to_date, status, crossed_at IS NOT NULL
          FROM nexus_profile
          WHERE (entity_id = $entityId OR $entityId IS NULL) AND (status = $status OR $status IS NULL)
          ORDER BY jurisdiction, region"""
      .query[(UUID, UUID, String, String, Option[BigDecimal], Option[Int], BigDecimal, Int, String, Boolean)]
      .to[List]
      .map(_.map {
        case (id, e, jur, region, ta, tc, sales, txns, status, crossed) =>
          Json.obj(
            "id"                  -> id.toString.asJson,
            "entity_id"           -> e.toString.asJson,
            "jurisdiction"        -> jur.asJson,
            "region"              -> region.asJson,
            "threshold_amount"    -> ta.asJson,
            "threshold_txn_count" -> tc.asJson,
            "sales_to_date"       -> sales.asJson,
            "txn_count_to_date"   -> txns.asJson,
            "status"              -> status.asJson,
            "crossed"             -> crossed.asJson
          )
      })

  def quotes(orderId: Option[UUID], context: Option[String]): ConnectionIO[List[Json]] =
    sql"""SELECT id, context, order_id, entity_id, ship_to_jurisdiction, ship_to_region, supply_kind, provider,
            reverse_charge, currency, total_tax, rounding_policy, rates_asof, superseded_by IS NOT NULL, determined_at::text
          FROM tax_quote
          WHERE (order_id = $orderId OR $orderId IS NULL) AND (context = $context OR $context IS NULL)
          ORDER BY determined_at DESC"""
      .query[
        (
            UUID,
            String,
            Option[UUID],
            UUID,
            String,
            Option[String],
            String,
            String,
            Boolean,
            String,
            BigDecimal,
            String,
            LocalDate,
            Boolean,
            String
        )
      ]
      .to[List]
      .map(_.map {
        case (id, ctx, ord, e, toJur, toRegion, kind, prov, rc, ccy, tax, rp, asof, superseded, at) =>
          Json.obj(
            "id"                   -> id.toString.asJson,
            "context"              -> ctx.asJson,
            "order_id"             -> ord.map(_.toString).asJson,
            "entity_id"            -> e.toString.asJson,
            "ship_to_jurisdiction" -> toJur.asJson,
            "ship_to_region"       -> toRegion.asJson,
            "supply_kind"          -> kind.asJson,
            "provider"             -> prov.asJson,
            "reverse_charge"       -> rc.asJson,
            "currency"             -> ccy.asJson,
            "total_tax"            -> tax.asJson,
            "rounding_policy"      -> rp.asJson,
            "rates_asof"           -> asof.toString.asJson,
            "superseded"           -> superseded.asJson,
            "determined_at"        -> at.asJson
          )
      })

  def quoteDetail(id: UUID): ConnectionIO[Option[Json]] =
    sql"SELECT response_snapshot, request_snapshot FROM tax_quote WHERE id = $id"
      .query[(Json, Json)]
      .option
      .map(_.map { case (resp, req) => Json.obj("response" -> resp, "request" -> req) })

  def addRegistration(
      entityId: UUID,
      taxType: String,
      number: Option[String],
      jurisdiction: String,
      region: Option[String],
      kind: String,
      effectiveFrom: LocalDate
  ): ConnectionIO[UUID] = {
    val id = UUID.randomUUID()
    sql"""INSERT INTO tax_registration (id, entity_id, tax_type, number, jurisdiction, region, registration_kind, effective_from)
          VALUES ($id, $entityId, $taxType, $number, $jurisdiction, $region, $kind, $effectiveFrom)""".update.run.as(id)
  }

  def addNexus(
      entityId: UUID,
      jurisdiction: String,
      region: String,
      thresholdAmount: Option[BigDecimal],
      thresholdTxn: Option[Int]
  ): ConnectionIO[UUID] = {
    val id = UUID.randomUUID()
    sql"""INSERT INTO nexus_profile (id, entity_id, jurisdiction, region, threshold_amount, threshold_txn_count, status)
          VALUES ($id, $entityId, $jurisdiction, $region, $thresholdAmount, $thresholdTxn, 'monitoring')
          ON CONFLICT (entity_id, jurisdiction, region) DO NOTHING""".update.run.as(id)
  }
}
