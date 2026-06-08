package com.hypervolt.conduit.close

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Read-side for the Auditability Center (doc 14 §6): the close board (periods), the reconciliation results, and
// the control register with its latest run. All plain projections — no ledger access — so they serve from the API
// without a TigerBeetle client (running a reconciliation, which needs the ledger, stays in ReconciliationService).
object AuditQueryRepo {

  def periods(entity: Option[UUID]): ConnectionIO[List[Json]] = {
    val base = fr"""SELECT id, entity_id, scope, period_key, status, closed_at FROM accounting_period"""
    val filt = entity.fold(Fragment.empty)(e => fr"WHERE entity_id = $e")
    (base ++ filt ++ fr"ORDER BY period_key DESC LIMIT 60")
      .query[(UUID, UUID, String, String, String, Option[java.time.Instant])]
      .to[List]
      .map(_.map { case (id, e, sc, pk, st, ca) =>
        Json.obj("id" -> id.toString.asJson, "entity_id" -> e.toString.asJson, "scope" -> sc.asJson,
          "period_key" -> pk.asJson, "status" -> st.asJson, "closed_at" -> ca.map(_.toString).asJson)
      })
  }

  def reconciliations(periodId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT type, expected, actual, variance, currency, status, signed_off_by IS NOT NULL
          FROM reconciliation WHERE period_id = $periodId ORDER BY type"""
      .query[(String, Option[BigDecimal], Option[BigDecimal], Option[BigDecimal], Option[String], String, Boolean)]
      .to[List]
      .map(_.map { case (t, exp, act, vr, ccy, st, signed) =>
        Json.obj("type" -> t.asJson, "expected" -> exp.asJson, "actual" -> act.asJson, "variance" -> vr.asJson,
          "currency" -> ccy.asJson, "status" -> st.asJson, "signed_off" -> signed.asJson)
      })

  // The control register with the latest run result — the SOX control board.
  def controls: ConnectionIO[List[Json]] =
    sql"""SELECT c.code, c.name, c.type,
                 (SELECT r.result FROM control_run r WHERE r.control_id = c.id ORDER BY r.run_at DESC LIMIT 1),
                 (SELECT r.run_at FROM control_run r WHERE r.control_id = c.id ORDER BY r.run_at DESC LIMIT 1)
          FROM control c WHERE c.status = 'active' ORDER BY c.code"""
      .query[(String, String, String, Option[String], Option[java.time.Instant])]
      .to[List]
      .map(_.map { case (code, name, typ, last, at) =>
        Json.obj("code" -> code.asJson, "name" -> name.asJson, "type" -> typ.asJson,
          "last_result" -> last.asJson, "last_run_at" -> at.map(_.toString).asJson)
      })

  def resolveInvoiceNo(invoiceNo: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM order_invoice WHERE invoice_no = $invoiceNo ORDER BY issued_at DESC LIMIT 1".query[UUID].option
}
