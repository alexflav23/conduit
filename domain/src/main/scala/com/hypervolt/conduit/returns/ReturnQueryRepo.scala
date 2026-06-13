package com.hypervolt.conduit.returns

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Read models for the returns desk (doc 09 §L): list + detail. Money/cost fields ride in the JSON and are
// layer-projected at the route (refund_amount → commercial, unit_landed_cost → profitability, doc 05 §3); the
// list is scope-filtered by a predicate fragment the route supplies (entity/market/channel, doc 05 §2).
object ReturnQueryRepo {

  // header columns shared by list + detail; the route projects them per the principal's layers.
  private def headerJson(
      id: UUID,
      no: String,
      orderId: UUID,
      rType: String,
      scope: String,
      status: String,
      currency: String,
      refund: Option[BigDecimal],
      reason: Option[String],
      replacement: Option[UUID],
      creditNote: Option[UUID]
  ): Json =
    Json.obj(
      "id"                   -> id.toString.asJson,
      "rma_no"               -> no.asJson,
      "order_id"             -> orderId.toString.asJson,
      "type"                 -> rType.asJson,
      "scope"                -> scope.asJson,
      "status"               -> status.asJson,
      "currency"             -> currency.asJson,
      "refund_amount"        -> refund.map(_.toString).asJson,
      "reason_code"          -> reason.asJson,
      "replacement_order_id" -> replacement.map(_.toString).asJson,
      "credit_note_id"       -> creditNote.map(_.toString).asJson
    )

  def list(scopePredicate: Fragment, filters: Fragment): ConnectionIO[List[Json]] =
    (sql"""SELECT id, rma_no, order_id, type, scope, status, refund_currency, refund_amount, reason_code,
             replacement_order_id, credit_note_id
           FROM rma WHERE """ ++ scopePredicate ++ filters ++ fr" ORDER BY created_at DESC LIMIT 200")
      .query[
        (
            UUID,
            String,
            UUID,
            String,
            String,
            String,
            String,
            Option[BigDecimal],
            Option[String],
            Option[UUID],
            Option[UUID]
        )
      ]
      .to[List]
      .map(_.map {
        case (id, no, o, t, sc, st, cur, ra, rc, rep, cn) => headerJson(id, no, o, t, sc, st, cur, ra, rc, rep, cn)
      })

  def detail(rmaId: UUID): ConnectionIO[Option[Json]] =
    header(rmaId).flatMap {
      case None => Option.empty[Json].pure[ConnectionIO]
      case Some(h) =>
        (lines(rmaId), dispositions(rmaId), creditNote(rmaId), lifecycle(rmaId)).mapN { (ls, ds, cn, lc) =>
          h.deepMerge(
            Json.obj(
              "lines"        -> ls.asJson,
              "dispositions" -> ds.asJson,
              "credit_note"  -> cn.getOrElse(Json.Null),
              "lifecycle"    -> lc.asJson
            )
          ).some
        }
    }

  private def header(rmaId: UUID): ConnectionIO[Option[Json]] =
    sql"""SELECT id, rma_no, order_id, type, scope, status, refund_currency, refund_amount, reason_code,
            replacement_order_id, credit_note_id
          FROM rma WHERE id = $rmaId"""
      .query[
        (
            UUID,
            String,
            UUID,
            String,
            String,
            String,
            String,
            Option[BigDecimal],
            Option[String],
            Option[UUID],
            Option[UUID]
        )
      ]
      .option
      .map(_.map {
        case (id, no, o, t, sc, st, cur, ra, rc, rep, cn) => headerJson(id, no, o, t, sc, st, cur, ra, rc, rep, cn)
      })

  private def lines(rmaId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT id, product_variant_id, serial_unit_id, component_ref, qty, condition_grade, unit_landed_cost,
            disposition, status
          FROM rma_line WHERE rma_id = $rmaId ORDER BY id"""
      .query[
        (UUID, UUID, Option[UUID], Option[String], Int, Option[String], Option[BigDecimal], Option[String], String)
      ]
      .to[List]
      .map(_.map {
        case (id, v, ser, comp, qty, grade, cost, disp, st) =>
          Json.obj(
            "id"               -> id.toString.asJson,
            "product_variant"  -> v.toString.asJson,
            "serial_unit"      -> ser.map(_.toString).asJson,
            "component_ref"    -> comp.asJson,
            "qty"              -> qty.asJson,
            "condition_grade"  -> grade.asJson,
            "unit_landed_cost" -> cost.map(_.toString).asJson,
            "disposition"      -> disp.asJson,
            "status"           -> st.asJson
          )
      })

  private def dispositions(rmaId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT rd.disposition, rd.from_status, rd.to_status
          FROM return_disposition rd JOIN rma_line rl ON rl.id = rd.rma_line_id
          WHERE rl.rma_id = $rmaId ORDER BY rd.id"""
      .query[(String, String, String)]
      .to[List]
      .map(_.map {
        case (d, from, to) => Json.obj("disposition" -> d.asJson, "from" -> from.asJson, "to" -> to.asJson)
      })

  private def creditNote(rmaId: UUID): ConnectionIO[Option[Json]] =
    sql"""SELECT credit_note_no, total_ex_vat, vat_total, total_inc_vat, refund_method
          FROM credit_note WHERE rma_id = $rmaId ORDER BY issued_at DESC LIMIT 1"""
      .query[(String, BigDecimal, BigDecimal, BigDecimal, String)]
      .option
      .map(_.map {
        case (no, ex, vat, inc, method) =>
          Json.obj(
            "credit_note_no" -> no.asJson,
            "total_ex_vat"   -> ex.toString.asJson,
            "vat_total"      -> vat.toString.asJson,
            "total_inc_vat"  -> inc.toString.asJson,
            "refund_method"  -> method.asJson
          )
      })

  // the lifecycle is the return.* event spine in the outbox — the audit trail of the RMA's transitions.
  private def lifecycle(rmaId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT event_type, occurred_at::text FROM outbox_event
          WHERE aggregate_type = 'rma' AND aggregate_id = $rmaId ORDER BY occurred_at"""
      .query[(String, String)]
      .to[List]
      .map(_.map { case (t, at) => Json.obj("event" -> t.asJson, "at" -> at.asJson) })
}
