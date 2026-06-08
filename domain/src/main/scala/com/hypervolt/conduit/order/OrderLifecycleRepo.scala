package com.hypervolt.conduit.order

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The Order Collection Ledger (doc 13 §void / order→cash). The order is the root of the lifecycle, so this REPLAYS
// the immutable event stream for one order_id into a readable ledger. Two views, both keyed on the order:
//   • timeline — the flat, chronological event log straight from the append-only outbox (the "perfect log"),
//   • cycles   — one collection cycle per invoice (the back-and-forth: issued → recognised → collected → refunded
//                → voided → replaced-by), built from the immutable typed facts so the money ties to the ledger.
// No new bookkeeping: every figure here is a projection of an event/fact that already happened.
object OrderLifecycleRepo {

  // Structural-only (no money): the event spine. Money lives in `cycles`, which the route layer-projects.
  def timeline(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT seq, event_id, event_type, occurred_at, payload
          FROM outbox_event
          WHERE aggregate_id = $orderId OR payload->>'order_id' = ${orderId.toString}
          ORDER BY seq"""
      .query[(Long, UUID, String, Instant, Json)]
      .to[List]
      .map(_.map {
        case (seq, eid, tpe, at, payload) =>
          val c = payload.hcursor
          Json.obj(
            "seq"              -> seq.asJson,
            "event_id"         -> eid.toString.asJson,
            "event_type"       -> tpe.asJson,
            "occurred_at"      -> at.toString.asJson,
            "invoice_no"       -> c.get[String]("invoice_no").toOption.asJson,
            "kind"             -> c.get[String]("kind").toOption.asJson,
            "formatted_number" -> c.get[String]("formatted_number").toOption.asJson,
            "correlation_id"   -> c.get[String]("correlation_id").toOption.asJson
          )
      })

  private type CycleRow = (
      String,             // invoice_no
      String,             // status
      BigDecimal,         // total_inc_vat
      Instant,            // issued_at
      Option[LocalDate],  // due_date
      Option[Instant],    // voided_at
      Option[String],     // void_kind
      Option[String],     // void_reason
      Option[String],     // replaced_by invoice_no
      BigDecimal,         // net applied (signed: applies − refunds)
      BigDecimal,         // paid (positive applies)
      BigDecimal,         // refunded (abs of negatives)
      Option[String],     // reversal kind (from invoice_reversal)
      Option[String],     // credit note number
      Option[BigDecimal], // recognised revenue_ex_vat
      Option[BigDecimal], // recognised vat
      Option[BigDecimal]  // recognised cogs
  )

  def cycles(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT i.invoice_no, i.status, i.total_inc_vat, i.issued_at, i.due_date,
                 i.voided_at, i.void_kind, i.void_reason,
                 (SELECT r.invoice_no FROM order_invoice r WHERE r.id = i.replaced_by_invoice_id),
                 COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.order_invoice_id = i.id), 0),
                 COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.order_invoice_id = i.id AND a.amount > 0), 0),
                 COALESCE((SELECT -SUM(a.amount) FROM payment_allocation a WHERE a.order_invoice_id = i.id AND a.amount < 0), 0),
                 ir.kind, cn.formatted_number,
                 rr.revenue_ex_vat, rr.vat, rr.cogs
          FROM order_invoice i
            LEFT JOIN revenue_recognition rr ON rr.invoice_no = i.invoice_no
            LEFT JOIN invoice_reversal ir ON ir.order_invoice_id = i.id
            LEFT JOIN document cn ON cn.order_invoice_id = i.id AND cn.document_type = 'credit_note'
          WHERE i.order_id = $orderId
          ORDER BY i.issued_at, i.invoice_no"""
      .query[CycleRow]
      .to[List]
      .map(_.zipWithIndex.map {
        case (r, idx) =>
          val outstanding = if (r._2 == "void") BigDecimal(0) else (r._3 - r._10).max(0)
          Json.obj(
            "cycle"          -> (idx + 1).asJson, // the back-and-forth ordinal for this order
            "invoice_no"     -> r._1.asJson,
            "status"         -> r._2.asJson,
            "issued_at"      -> r._4.toString.asJson,
            "due_date"       -> r._5.map(_.toString).asJson,
            "voided_at"      -> r._6.map(_.toString).asJson,
            "void_kind"      -> (r._7.orElse(r._13)).asJson,
            "void_reason"    -> r._8.asJson,
            "replaced_by"    -> r._9.asJson,
            "credit_note_no" -> r._14.asJson,
            // money — commercial layer (projected at the route)
            "total"          -> r._3.asJson,
            "revenue_ex_vat" -> r._15.asJson,
            "vat"            -> r._16.asJson,
            "cogs"           -> r._17.asJson,
            "paid"           -> r._11.asJson,
            "refunded"       -> r._12.asJson,
            "outstanding"    -> outstanding.asJson
          )
      })
}
