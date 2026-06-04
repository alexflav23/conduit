package com.hypervolt.conduit.supply

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

// Read-side for the Supply window desk (design spec doc 20 §2.4): the firm-commitment horizon, the auto-PO
// proposals, and the divergence warnings — all per contract manufacturer. SKU labels joined for readability.
object SupplyQueryRepo {

  def contractManufacturers: ConnectionIO[List[Json]] =
    sql"SELECT id, name FROM supplier WHERE is_contract_manufacturer ORDER BY name"
      .query[(UUID, String)]
      .to[List]
      .map(_.map { case (id, name) => Json.obj("id" -> id.toString.asJson, "name" -> name.asJson) })

  // The firm-commitment horizon strip: each SKU/week with the firm PO and its fence zone.
  def commitments(supplier: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT v.sku, c.product_variant_id, c.target_date, c.qty, c.zone
          FROM supply_commitment c JOIN product_variant v ON v.id = c.product_variant_id
          WHERE c.supplier_id = $supplier ORDER BY c.target_date, v.sku LIMIT 200"""
      .query[(String, UUID, LocalDate, Int, String)]
      .to[List]
      .map(_.map {
        case (sku, v, t, q, z) =>
          Json.obj(
            "sku"                -> sku.asJson,
            "product_variant_id" -> v.toString.asJson,
            "target_date"        -> t.toString.asJson,
            "qty"                -> q.asJson,
            "zone"               -> z.asJson
          )
      })

  // The current auto-PO proposals (auto-fill within headroom + the blocked remainder needing escalation).
  def proposals(supplier: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT v.sku, pr.product_variant_id, pr.target_date, pr.demand_qty, pr.committed_qty, pr.available_qty,
                 pr.net_need, pr.proposed_delta, pr.blocked_qty, pr.zone, pr.status
          FROM po_proposal pr JOIN product_variant v ON v.id = pr.product_variant_id
          WHERE pr.supplier_id = $supplier ORDER BY pr.blocked_qty DESC, pr.created_at DESC LIMIT 200"""
      .query[(String, UUID, LocalDate, Int, Int, Int, Int, Int, Int, String, String)]
      .to[List]
      .map(_.map {
        case (sku, v, t, dem, com, avail, net, prop, blk, zone, st) =>
          Json.obj(
            "sku"                -> sku.asJson,
            "product_variant_id" -> v.toString.asJson,
            "target_date"        -> t.toString.asJson,
            "demand"             -> dem.asJson,
            "committed"          -> com.asJson,
            "available"          -> avail.asJson,
            "net_need"           -> net.asJson,
            "proposed_delta"     -> prop.asJson,
            "blocked_qty"        -> blk.asJson,
            "zone"               -> zone.asJson,
            "status"             -> st.asJson
          )
      })

  def warnings(supplier: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT v.sku, w.target_date, w.zone, w.committed_qty, w.demand_qty, w.delta, w.source, w.severity, w.message
          FROM commitment_warning w JOIN product_variant v ON v.id = w.product_variant_id
          WHERE w.supplier_id = $supplier ORDER BY w.created_at DESC LIMIT 100"""
      .query[(String, LocalDate, String, Int, Int, Int, String, String, String)]
      .to[List]
      .map(_.map {
        case (sku, t, z, com, dem, d, src, sev, msg) =>
          Json.obj(
            "sku"         -> sku.asJson,
            "target_date" -> t.toString.asJson,
            "zone"        -> z.asJson,
            "committed"   -> com.asJson,
            "demand"      -> dem.asJson,
            "delta"       -> d.asJson,
            "source"      -> src.asJson,
            "severity"    -> sev.asJson,
            "message"     -> msg.asJson
          )
      })

  def proposal(supplier: UUID, variant: UUID, target: LocalDate): ConnectionIO[Option[Int]] =
    sql"""SELECT committed_qty + proposed_delta FROM po_proposal
          WHERE supplier_id = $supplier AND product_variant_id = $variant AND target_date = $target AND status = 'proposed'"""
      .query[Int]
      .option

  def markCommitted(supplier: UUID, variant: UUID, target: LocalDate): ConnectionIO[Int] =
    sql"""UPDATE po_proposal SET status = 'committed'
          WHERE supplier_id = $supplier AND product_variant_id = $variant AND target_date = $target""".update.run
}
