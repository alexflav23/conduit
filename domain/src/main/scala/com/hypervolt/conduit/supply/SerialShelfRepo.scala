package com.hypervolt.conduit.supply

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Real-time per-account stock from the serial register (mirrors ghost-busters /stock/dashboard, but the
// serial→customer attribution is OWNED by Conduit — set at dispatch, not looked up in MRPeasy). Every shipment
// is a finite set of serials attributed to the buying account (serial_unit.company_id); the activation stream
// flips each serial to 'activated' in real time, so on-shelf = shipped − activated falls live, per account.
object SerialShelfRepo {

  // Per-account shelf: shipped (dispatched to this account), activated (consumed), on-shelf (still on the shelf).
  def shelf(company: UUID): ConnectionIO[Json] =
    sql"""SELECT
            COUNT(*) FILTER (WHERE status IN ('dispatched','activated'))::int AS shipped,
            COUNT(*) FILTER (WHERE status = 'activated')::int AS activated
          FROM serial_unit WHERE company_id = $company"""
      .query[(Int, Int)]
      .unique
      .map {
        case (shipped, activated) =>
          Json.obj(
            "company_id" -> company.toString.asJson,
            "shipped"    -> shipped.asJson,
            "activated"  -> activated.asJson,
            "on_shelf"   -> (shipped - activated).asJson
          )
      }

  // The fleet shelf board: every account with shipped/activated/on-shelf, busiest shelf first.
  def board(limit: Int): ConnectionIO[List[Json]] =
    sql"""SELECT s.company_id, p.display_name,
            COUNT(*) FILTER (WHERE s.status IN ('dispatched','activated'))::int,
            COUNT(*) FILTER (WHERE s.status = 'activated')::int
          FROM serial_unit s LEFT JOIN party p ON p.id = s.company_id
          WHERE s.company_id IS NOT NULL
          GROUP BY s.company_id, p.display_name
          ORDER BY (COUNT(*) FILTER (WHERE s.status IN ('dispatched','activated')) -
                    COUNT(*) FILTER (WHERE s.status = 'activated')) DESC
          LIMIT $limit"""
      .query[(UUID, Option[String], Int, Int)]
      .to[List]
      .map(_.map {
        case (id, name, shipped, activated) =>
          Json.obj(
            "company_id" -> id.toString.asJson,
            "name"       -> name.asJson,
            "shipped"    -> shipped.asJson,
            "activated"  -> activated.asJson,
            "on_shelf"   -> (shipped - activated).asJson
          )
      })

  // Finished-goods on hand for a SKU (what's available to cover demand before a new PO is needed).
  def onHand(variant: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(SUM(qty_on_hand),0)::int FROM stock_item WHERE product_variant_id = $variant".query[Int].unique
}
