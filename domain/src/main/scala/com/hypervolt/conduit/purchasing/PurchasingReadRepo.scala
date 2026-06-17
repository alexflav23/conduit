package com.hypervolt.conduit.purchasing

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.fragments
import io.circe.Json
import java.util.UUID

// Read side of purchasing/receiving (M9). The PO book, a PO's lines/receipt ladder, and the stock-operations
// ledger (cycle-count / transfer / write-off). The historical estate had no POs (supply migrated as opening
// lots), so these honestly return empty until purchasing runs — the desk renders the empty state, not a 405.
object PurchasingReadRepo {

  def listPos(entity: Option[UUID], status: Option[String]): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'id', po.id::text, 'po_no', po.po_no, 'supplier', sup.name, 'cm', sup.name, 'status', po.status,
            'currency', po.txn_currency, 'total', po.total, 'order_date', po.order_date, 'expected_date', po.expected_date,
            'total_ordered', COALESCE((SELECT sum(qty) FROM po_line WHERE po_id = po.id), 0),
            'total_received', COALESCE((SELECT sum(qty_received) FROM po_line WHERE po_id = po.id), 0))
          FROM purchase_order po LEFT JOIN supplier sup ON sup.id = po.supplier_id """
      ++ fragments.whereAndOpt(entity.map(e => fr"po.entity_id = $e"), status.map(s => fr"po.status = $s"))
      ++ fr"ORDER BY po.order_date DESC NULLS LAST, po.po_no")
      .query[Json]
      .to[List]

  def poDetail(id: UUID): ConnectionIO[Option[Json]] =
    sql"""SELECT jsonb_build_object(
            'id', po.id::text, 'po_no', po.po_no, 'supplier', sup.name, 'cm', sup.name, 'status', po.status,
            'currency', po.txn_currency, 'total', po.total, 'order_date', po.order_date, 'expected_date', po.expected_date,
            'total_ordered', COALESCE((SELECT sum(qty) FROM po_line WHERE po_id = po.id), 0),
            'total_received', COALESCE((SELECT sum(qty_received) FROM po_line WHERE po_id = po.id), 0),
            'can_receive', po.status <> 'received',
            'tranches', '[]'::jsonb,
            'lines', COALESCE((
              SELECT jsonb_agg(jsonb_build_object('id', l.id::text, 'sku', pv.sku, 'variant', pv.sku,
                       'expected', l.qty, 'received', l.qty_received, 'unit_cost', l.unit_cost) ORDER BY pv.sku)
              FROM po_line l LEFT JOIN product_variant pv ON pv.id = l.product_variant_id WHERE l.po_id = po.id), '[]'::jsonb))
          FROM purchase_order po LEFT JOIN supplier sup ON sup.id = po.supplier_id
          WHERE po.id = $id""".query[Json].option

  def stockOps(entity: Option[UUID], limit: Int, offset: Int): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'id', m.id::text, 'type', m.type, 'sku', pv.sku, 'qty', m.qty, 'ref_type', m.ref_type,
            'reason_code', m.reason_code, 'occurred_at', m.occurred_at)
          FROM stock_movement m LEFT JOIN product_variant pv ON pv.id = m.product_variant_id """
      ++ fragments.whereAndOpt(entity.map(e => fr"m.entity_id = $e"))
      ++ fr"ORDER BY m.occurred_at DESC LIMIT $limit OFFSET $offset")
      .query[Json]
      .to[List]

  def stockOpsCount(entity: Option[UUID]): ConnectionIO[Long] =
    (fr"SELECT count(*) FROM stock_movement m" ++ fragments.whereAndOpt(entity.map(e => fr"m.entity_id = $e")))
      .query[Long]
      .unique
}
