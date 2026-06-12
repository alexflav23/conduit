package com.hypervolt.conduit.proof

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// The ASC 606 five-step bundle for one real order (spec doc 31 §2.3; the surface of doc 29 A3). Every step
// renders LIVE rows and cites its pinning laws/controls from the FormalismRegister — page and spec share
// this one source. The flash-title decomposition (step 5's principal/LRD overlay) is included ONLY when the
// viewer holds the inter_entity layer: the wall is absence (L7), here too.
object Asc606Walkthrough {

  def bundle(orderId: UUID, includeInterEntity: Boolean): ConnectionIO[Option[Json]] =
    head(orderId).flatMap {
      case None => Option.empty[Json].pure[ConnectionIO]
      case Some(h) =>
        (agreements(orderId), lines(orderId), recognitions(orderId), reversals(orderId), rebate(orderId, h._8))
          .mapN { (ags, lns, recogs, revs, reb) =>
            flash(orderId, includeInterEntity).map { fl =>
              Json
                .obj(
                  "order" -> Json.obj(
                    "id"              -> orderId.toString.asJson,
                    "order_no"        -> h._1.asJson,
                    "status"          -> h._2.asJson,
                    "currency"        -> h._3.asJson,
                    "customer"        -> h._7.asJson,
                    "subtotal_ex_vat" -> h._4.asJson,
                    "vat_total"       -> h._5.asJson,
                    "total_inc_vat"   -> h._6.asJson
                  ),
                  "step1_identify_contract" -> Json.obj(
                    "explain"              -> "The contract is the order bound to its governed tier agreement — nobody typed a price (doc 24). The customer PO is the provenance link.".asJson,
                    "customer_po_number"   -> h._9.asJson,
                    "source_attachment_id" -> h._10.map(_.toString).asJson,
                    "price_agreements"     -> ags.asJson,
                    "pins"                 -> List("L8", "L9", "CTRL-IC-CATALOGUE").asJson
                  ),
                  "step2_performance_obligations" -> Json.obj(
                    "explain" -> "Each order line (and tranche, where scheduled) is a distinct performance obligation with its own fulfilment state.".asJson,
                    "lines"   -> lns.asJson,
                    "pins"    -> List("L14").asJson
                  ),
                  "step3_transaction_price" -> Json.obj(
                    "explain" -> "Line prices resolve from the agreement's bands. Retrospective volume rebates are VARIABLE CONSIDERATION: invoiced at the firm entry price, the expected rebate accrues from the first unit and trues up as evidence changes.".asJson,
                    "rebate"  -> reb,
                    "pins"    -> List("L6", "CTRL-REBATE-ACCRUAL").asJson
                  ),
                  "step4_allocation" -> Json.obj(
                    "explain" -> "Allocation uses the conserving largest-remainder allocate: Σ parts == total, always — a ScalaCheck law, not a rounding hope.".asJson,
                    "line_total_check" -> Json.obj(
                      "sum_of_lines"    -> lns.flatMap(_.hcursor.get[BigDecimal]("line_total_ex_vat").toOption).sum.asJson,
                      "subtotal_ex_vat" -> h._4.asJson
                    ),
                    "pins" -> List("L1").asJson
                  ),
                  "step5_recognition" -> Json.obj(
                    "explain"      -> "Control transfers at dispatch: the recognition journal posts to the immutable ledger with deterministic ids; a void mirrors the EXACT leg set (per-event reversal). Under the principal/LRD structure the operating P&L carries COGS at transfer; the group margin eliminates.".asJson,
                    "recognitions" -> recogs.asJson,
                    "reversals"    -> revs.asJson,
                    "pins"         -> List("L2", "L3", "L4", "CTRL-LINEAGE-CLOSURE", "CTRL-IC-MATCH").asJson
                  )
                )
                .deepMerge(fl.fold(Json.obj())(f => Json.obj("step5_recognition_flash" -> f)))
            }
          }
          .flatMap(identity)
          .map(Option(_))
    }

  private def head(orderId: UUID): ConnectionIO[Option[
    (String, String, String, BigDecimal, BigDecimal, BigDecimal, String, Option[UUID], Option[String], Option[UUID])
  ]] =
    sql"""SELECT o.order_no, o.status, o.txn_currency, o.subtotal_ex_vat, o.vat_total, o.total_inc_vat,
                 p.display_name, o.entity_id, o.customer_po_number, o.source_attachment_id
          FROM "order" o JOIN party p ON p.id = o.bill_to_party_id WHERE o.id = $orderId"""
      .query[
        (String, String, String, BigDecimal, BigDecimal, BigDecimal, String, Option[UUID], Option[String], Option[UUID])
      ]
      .option

  private def agreements(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT DISTINCT pa.id, pa.name, pa.base_volume_basis, pa.status, pa.valid_from::text, pa.valid_to::text
          FROM order_line ol JOIN price_agreement pa ON pa.id = ol.price_agreement_id
          WHERE ol.order_id = $orderId"""
      .query[(UUID, String, String, String, String, Option[String])]
      .to[List]
      .flatMap(_.traverse {
        case (id, name, basis, status, from, to) =>
          sql"""SELECT min_qty, up_to_qty, authorised_price FROM price_rule
                WHERE price_agreement_id = $id ORDER BY min_qty"""
            .query[(Int, Option[Int], BigDecimal)]
            .to[List]
            .map(bands =>
              Json.obj(
                "agreement_id" -> id.toString.asJson,
                "name"         -> name.asJson,
                "volume_basis" -> basis.asJson,
                "status"       -> status.asJson,
                "valid_from"   -> from.asJson,
                "valid_to"     -> to.asJson,
                "bands" -> bands.map {
                  case (lo, hi, price) =>
                    Json.obj("from_qty" -> lo.asJson, "up_to_qty" -> hi.asJson, "unit_price" -> price.asJson)
                }.asJson
              )
            )
      })

  private def lines(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT ol.id, pv.sku, ol.qty, ol.unit_price_ex_vat, ol.status,
                 (SELECT count(*) FROM delivery_tranche t WHERE t.order_line_id = ol.id)
          FROM order_line ol JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE ol.order_id = $orderId ORDER BY pv.sku"""
      .query[(UUID, String, Int, BigDecimal, String, Int)]
      .to[List]
      .map(_.map {
        case (id, sku, qty, unit, status, tranches) =>
          Json.obj(
            "line_id"           -> id.toString.asJson,
            "sku"               -> sku.asJson,
            "qty"               -> qty.asJson,
            "unit_price_ex_vat" -> unit.asJson,
            "line_total_ex_vat" -> (unit * qty).asJson,
            "status"            -> status.asJson,
            "tranches"          -> tranches.asJson
          )
      })

  private def recognitions(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT dispatch_id, invoice_no, revenue_ex_vat, vat, cogs, gross_margin, recognized_at::text,
                 ar_transfer_id::text, vat_transfer_id::text, cogs_transfer_id::text
          FROM revenue_recognition WHERE order_id = $orderId ORDER BY recognized_at"""
      .query[
        (
            UUID,
            Option[String],
            BigDecimal,
            BigDecimal,
            BigDecimal,
            BigDecimal,
            String,
            Option[String],
            Option[String],
            Option[String]
        )
      ]
      .to[List]
      .map(_.map {
        case (d, inv, rev, vatAmt, cogs, gm, at, ar, vt, cg) =>
          Json.obj(
            "dispatch_id"      -> d.toString.asJson,
            "invoice_no"       -> inv.asJson,
            "revenue_ex_vat"   -> rev.asJson,
            "vat"              -> vatAmt.asJson,
            "cogs"             -> cogs.asJson,
            "gross_margin"     -> gm.asJson,
            "recognized_at"    -> at.asJson,
            "ar_transfer_id"   -> ar.asJson,
            "vat_transfer_id"  -> vt.asJson,
            "cogs_transfer_id" -> cg.asJson
          )
      })

  private def reversals(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT id, invoice_no, kind, reason, reversed_revenue_ex_vat, reversed_vat, reversed_cogs,
                 rev_ar_transfer_id::text, rev_vat_transfer_id::text, rev_cogs_transfer_id::text
          FROM invoice_reversal WHERE order_id = $orderId"""
      .query[
        (
            UUID,
            String,
            String,
            String,
            BigDecimal,
            BigDecimal,
            BigDecimal,
            Option[String],
            Option[String],
            Option[String]
        )
      ]
      .to[List]
      .map(_.map {
        case (id, inv, kind, reason, rev, vatAmt, cogs, ar, vt, cg) =>
          Json.obj(
            "reversal_id"          -> id.toString.asJson,
            "invoice_no"           -> inv.asJson,
            "kind"                 -> kind.asJson,
            "reason"               -> reason.asJson,
            "reversed_revenue"     -> rev.asJson,
            "reversed_vat"         -> vatAmt.asJson,
            "reversed_cogs"        -> cogs.asJson,
            "rev_ar_transfer_id"   -> ar.asJson,
            "rev_vat_transfer_id"  -> vt.asJson,
            "rev_cogs_transfer_id" -> cg.asJson
          )
      })

  // Variable consideration: the retrospective agreement's accrual position — outstanding liability from the
  // gl mirror + the settlement history. Json.Null when the order's agreements carry no retrospective basis.
  private def rebate(orderId: UUID, entity: Option[UUID]): ConnectionIO[Json] =
    sql"""SELECT DISTINCT pa.id FROM order_line ol
          JOIN price_agreement pa ON pa.id = ol.price_agreement_id
          WHERE ol.order_id = $orderId AND pa.base_volume_basis = 'cumulative_retrospective'"""
      .query[UUID]
      .to[List]
      .flatMap {
        case Nil => (Json.Null: Json).pure[ConnectionIO]
        case agreementId :: _ =>
          val accrualKey = entity.fold("REBATE_ACCRUAL:")(e => s"REBATE_ACCRUAL:$e")
          (
            sql"""SELECT COALESCE(SUM(CASE WHEN side = 'credit' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM gl_entry WHERE account_key = $accrualKey AND posted""".query[BigDecimal].unique,
            sql"""SELECT milestone, amount, status FROM rebate_settlement
                  WHERE agreement_id = $agreementId ORDER BY created_at"""
              .query[(String, BigDecimal, String)]
              .to[List]
          ).mapN { (outstandingMinor, settlements) =>
            Json.obj(
              "agreement_id"        -> agreementId.toString.asJson,
              "accrual_outstanding" -> (outstandingMinor / 100).asJson,
              "settlements" -> settlements.map {
                case (m, a, s) => Json.obj("milestone" -> m.asJson, "amount" -> a.asJson, "status" -> s.asJson)
              }.asJson
            )
          }
      }

  // The principal/LRD overlay — inter_entity holders only (L7: absence, never null).
  private def flash(orderId: UUID, include: Boolean): ConnectionIO[Option[Json]] =
    if (!include) Option.empty[Json].pure[ConnectionIO]
    else
      sql"""SELECT dispatch_id, landed_total, transfer_total, uplift_total, returned_uplift,
                   reversed_at IS NOT NULL
            FROM ic_match m JOIN "order" o ON o.id = m.order_id WHERE o.id = $orderId"""
        .query[(UUID, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Boolean)]
        .to[List]
        .map {
          case Nil => None
          case ms =>
            Json
              .obj(
                "explain" -> "Flash title at dispatch: operating COGS = transfer; the principal books exactly the markup; the pair eliminates at group. Reversals and unwinds carry the genealogy.".asJson,
                "matches" -> ms.map {
                  case (d, landed, transfer, uplift, returned, reversed) =>
                    Json.obj(
                      "dispatch_id"     -> d.toString.asJson,
                      "landed_total"    -> landed.asJson,
                      "transfer_total"  -> transfer.asJson,
                      "uplift_total"    -> uplift.asJson,
                      "returned_uplift" -> returned.asJson,
                      "reversed"        -> reversed.asJson
                    )
                }.asJson,
                "pins" -> List("L2", "L7", "CTRL-IC-MATCH").asJson
              )
              .some
        }
}
