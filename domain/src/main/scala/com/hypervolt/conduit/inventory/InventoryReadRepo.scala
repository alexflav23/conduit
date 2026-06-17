package com.hypervolt.conduit.inventory

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.fragments
import io.circe.Json
import java.util.UUID

// Read side of inventory (M6/M7): the lot ledger, the serial fleet (paginated over the whole 100k+ population),
// availability/ATP, per-serial genealogy and per-batch roster. Write/allocation stays in InventoryRepo/
// AllocationService. Rows come back as jsonb; lists carry a separate count for server-side pagination. Shapes
// match the desk's BatchGenealogy/Inventory contracts (sku_label/cm/landed_unit_cost…).
object InventoryReadRepo {

  private def entityFr(entity: Option[UUID]): Option[Fragment] = entity.map(e => fr"s.entity_id = $e")
  private def statusFr(status: Option[String]): Option[Fragment] = status.map(s => fr"s.status = $s")
  private def qFr(q: Option[String]): Option[Fragment] =
    q.filter(_.trim.nonEmpty).map(t => fr"s.serial_no ILIKE ${"%" + t.trim + "%"}")

  def serialsPage(
      entity: Option[UUID],
      status: Option[String],
      q: Option[String],
      limit: Int,
      offset: Int
  ): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'id', s.id::text, 'sn', s.serial_no, 'serial_no', s.serial_no, 'generation', s.generation, 'status', s.status,
            'sku', pv.sku, 'sku_label', pv.sku, 'batch_no', b.batch_no, 'landed_unit_cost', b.landed_unit_cost,
            'activated_at', s.activated_at, 'warranty_end', s.warranty_end,
            'replaces_serial_unit_id', s.replaces_serial_unit_id::text, 'source', s.source, 'dispatch_id', s.dispatch_id::text)
          FROM serial_unit s
          LEFT JOIN product_variant pv ON pv.id = s.product_variant_id
          LEFT JOIN lot_batch b ON b.id = s.lot_batch_id """
      ++ fragments.whereAndOpt(entityFr(entity), statusFr(status), qFr(q))
      ++ fr"ORDER BY s.created_at DESC, s.serial_no LIMIT $limit OFFSET $offset")
      .query[Json]
      .to[List]

  def serialsCount(entity: Option[UUID], status: Option[String], q: Option[String]): ConnectionIO[Long] =
    (fr"SELECT count(*) FROM serial_unit s" ++ fragments.whereAndOpt(entityFr(entity), statusFr(status), qFr(q)))
      .query[Long]
      .unique

  def batchesPage(entity: Option[UUID], limit: Int, offset: Int): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'id', b.id::text, 'batch_no', b.batch_no, 'sku', pv.sku, 'sku_label', pv.sku, 'qty', b.qty,
            'unit_cost_usd', b.unit_cost_usd, 'unit_cost', b.unit_cost_usd, 'fx_rate', b.fx_rate, 'fx_basis', b.fx_basis,
            'shipping_alloc', b.shipping_alloc, 'duty_alloc', b.duty_alloc, 'landed_unit_cost', b.landed_unit_cost,
            'currency', b.currency, 'received', b.received_date, 'received_date', b.received_date,
            'manufactured_date', b.manufactured_date, 'cm', sup.name, 'supplier', sup.name,
            'serials_linked', (SELECT count(*) FROM serial_unit su WHERE su.lot_batch_id = b.id))
          FROM lot_batch b
          LEFT JOIN product_variant pv ON pv.id = b.product_variant_id
          LEFT JOIN supplier sup ON sup.id = b.supplier_id """
      ++ fragments.whereAndOpt(entity.map(e => fr"""EXISTS (SELECT 1 FROM serial_unit su WHERE su.lot_batch_id = b.id AND su.entity_id = $e)"""))
      ++ fr"ORDER BY b.received_date DESC NULLS LAST, b.batch_no LIMIT $limit OFFSET $offset")
      .query[Json]
      .to[List]

  def batchesCount(entity: Option[UUID]): ConnectionIO[Long] =
    (fr"SELECT count(*) FROM lot_batch b" ++ fragments.whereAndOpt(
      entity.map(e => fr"""EXISTS (SELECT 1 FROM serial_unit su WHERE su.lot_batch_id = b.id AND su.entity_id = $e)""")
    )).query[Long].unique

  // Availability / ATP: on-hand, allocated and free per variant, optionally scoped to an entity.
  def atp(entity: Option[UUID], limit: Int, offset: Int): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'sku', pv.sku, 'sku_label', pv.sku, 'product_variant_id', si.product_variant_id::text,
            'qty_on_hand', sum(si.qty_on_hand), 'qty_allocated', sum(si.qty_allocated),
            'qty_incoming', sum(si.qty_incoming), 'available', sum(si.qty_on_hand - si.qty_allocated))
          FROM stock_item si LEFT JOIN product_variant pv ON pv.id = si.product_variant_id """
      ++ fragments.whereAndOpt(entity.map(e => fr"si.entity_id = $e"))
      ++ fr"""GROUP BY pv.sku, si.product_variant_id ORDER BY pv.sku LIMIT $limit OFFSET $offset""")
      .query[Json]
      .to[List]

  def atpCount(entity: Option[UUID]): ConnectionIO[Long] =
    (fr"""SELECT count(DISTINCT si.product_variant_id) FROM stock_item si"""
      ++ fragments.whereAndOpt(entity.map(e => fr"si.entity_id = $e"))).query[Long].unique

  // Per-serial genealogy in the desk's nested shape: serial → batch/PO → order/customer → activation, plus a
  // lifecycle timeline. Bidirectional (replaces/replaced_by) rides on the serial node for the RMA chain.
  def genealogy(serial: String): ConnectionIO[Option[Json]] =
    sql"""SELECT jsonb_build_object(
            'serial', jsonb_build_object(
              'sn', s.serial_no, 'serial', s.serial_no, 'sku_label', pv.sku, 'location', loc.name,
              'status', s.status, 'order', o.order_no, 'customer', party.display_name,
              'dispatched_at', COALESCE(d.delivered_at, d.date::timestamptz),
              'replaces_serial_no', (SELECT r.serial_no FROM serial_unit r WHERE r.id = s.replaces_serial_unit_id),
              'replaced_by', (SELECT jsonb_agg(c.serial_no) FROM serial_unit c WHERE c.replaces_serial_unit_id = s.id)),
            'batch', CASE WHEN b.id IS NULL THEN NULL ELSE jsonb_build_object(
              'id', b.batch_no, 'received', b.received_date, 'cm', sup.name, 'po', NULL,
              'location_name', loc.name, 'landed_unit_cost', b.landed_unit_cost, 'unit_cost', b.unit_cost_usd,
              'freight_per_unit', b.shipping_alloc, 'duty_per_unit', b.duty_alloc, 'currency', b.currency) END,
            'activation', CASE WHEN s.activated_at IS NULL THEN NULL ELSE jsonb_build_object(
              'activated_at', s.activated_at, 'installer', s.installer_user_id) END,
            'timeline', (
              SELECT jsonb_agg(e ORDER BY (e->>'at')) FROM (
                SELECT jsonb_build_object('at', b.received_date::text, 'event', 'received', 'origin', sup.name) AS e WHERE b.received_date IS NOT NULL
                UNION ALL
                SELECT jsonb_build_object('at', COALESCE(d.delivered_at, d.date::timestamptz)::text, 'event', 'dispatched', 'origin', party.display_name) WHERE d.id IS NOT NULL
                UNION ALL
                SELECT jsonb_build_object('at', s.activated_at::text, 'event', 'activated', 'origin', 'field') WHERE s.activated_at IS NOT NULL
              ) evs))
          FROM serial_unit s
          LEFT JOIN product_variant pv ON pv.id = s.product_variant_id
          LEFT JOIN lot_batch b ON b.id = s.lot_batch_id
          LEFT JOIN supplier sup ON sup.id = b.supplier_id
          LEFT JOIN location loc ON loc.id = s.location_id
          LEFT JOIN dispatch d ON d.id = s.dispatch_id
          LEFT JOIN "order" o ON o.id = d.order_id
          LEFT JOIN party ON party.id = o.sold_to_party_id
          WHERE s.serial_no = $serial LIMIT 1""".query[Json].option

  // Per-batch roster: the lot header + its serials (capped) + a status histogram. The desk's BatchRoster tab.
  def batchRoster(batchId: UUID, serialLimit: Int): ConnectionIO[Option[Json]] =
    sql"""SELECT jsonb_build_object(
            'batch', jsonb_build_object(
              'id', b.batch_no, 'sku_label', pv.sku, 'qty', b.qty, 'received', b.received_date, 'cm', sup.name,
              'landed_unit_cost', b.landed_unit_cost, 'unit_cost', b.unit_cost_usd,
              'freight_per_unit', b.shipping_alloc, 'duty_per_unit', b.duty_alloc, 'currency', b.currency,
              'landed_value', b.landed_unit_cost * b.qty),
            'serials', COALESCE((
              SELECT jsonb_agg(jsonb_build_object('sn', su.serial_no, 'status', su.status, 'order', o.order_no,
                       'customer', p.display_name, 'dispatched_at', COALESCE(d.delivered_at, d.date::timestamptz)) ORDER BY su.serial_no)
              FROM (SELECT * FROM serial_unit WHERE lot_batch_id = b.id ORDER BY serial_no LIMIT $serialLimit) su
              LEFT JOIN dispatch d ON d.id = su.dispatch_id
              LEFT JOIN "order" o ON o.id = d.order_id
              LEFT JOIN party p ON p.id = o.sold_to_party_id), '[]'::jsonb),
            'by_status', COALESCE((
              SELECT jsonb_object_agg(status, c) FROM (
                SELECT status, count(*) AS c FROM serial_unit WHERE lot_batch_id = b.id GROUP BY status) t), '{}'::jsonb))
          FROM lot_batch b
          LEFT JOIN product_variant pv ON pv.id = b.product_variant_id
          LEFT JOIN supplier sup ON sup.id = b.supplier_id
          WHERE b.id = $batchId""".query[Json].option
}
