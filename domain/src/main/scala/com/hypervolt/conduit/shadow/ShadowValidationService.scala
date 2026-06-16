package com.hypervolt.conduit.shadow

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// The shadow-validation harness (doc 33 §5). A battery of set-based checks compares Conduit's computed reality
// against the source-stated figures and internal integrity invariants, upserting per-record discrepancies into the
// shadow_finding triage queue. Each run is idempotent: a finding still present is refreshed (human triage status
// preserved), one whose condition cleared is auto-resolved. Complements the period-close `reconciliation` ties
// (aggregate) — this is the per-record queue worked to zero before cutover. No external source needed: the source
// figures live in the domain rows (order.subtotal_ex_vat) and the invariants are self-evident.
final class ShadowValidationService[F[_]: Async](xa: Transactor[F]) {

  // Each check: (code, body(runId) → the SELECT feeding the upsert).
  // expected = source-stated / invariant-expected ; actual = Conduit-computed ; variance = actual − expected.
  private val checks: List[(String, UUID => Fragment)] = List(
    // Margin integrity: COGS relieved with zero recognised revenue. Graded by the SOURCE header: a header value
    // means the import dropped the line price (HIGH — understated revenue to fix); a £0 header means a genuinely
    // free shipment (LOW — warranty/replacement/sample, COGS correctly absorbed). expected = source revenue.
    (
      "cogs_without_revenue",
      (runId: UUID) => fr"""SELECT 'cogs_without_revenue',
                      CASE WHEN o.subtotal_ex_vat > 0 THEN 'high' ELSE 'low' END,
                      'recognition', rr.dispatch_id::text, rr.entity_id,
                      o.subtotal_ex_vat, rr.revenue_ex_vat, rr.revenue_ex_vat - o.subtotal_ex_vat, rr.currency,
                      jsonb_build_object('order_id', rr.order_id::text, 'order_no', o.order_no, 'cogs', rr.cogs,
                        'source_header_ex_vat', o.subtotal_ex_vat,
                        'classification', CASE WHEN o.subtotal_ex_vat > 0 THEN 'price_lost_in_import' ELSE 'genuinely_free' END),
                      $runId, 'open'
                    FROM revenue_recognition rr JOIN "order" o ON o.id = rr.order_id
                    WHERE rr.cogs > 0 AND rr.revenue_ex_vat = 0"""
    ),
    // Data fidelity: the source order header (ex-VAT) must reconcile to Conduit's line-derived value.
    (
      "order_header_vs_lines",
      (runId: UUID) => fr"""SELECT 'order_header_vs_lines', 'medium', 'order', o.id::text, o.entity_id,
                      o.subtotal_ex_vat, c.lines, c.lines - o.subtotal_ex_vat, o.txn_currency,
                      jsonb_build_object('order_no', o.order_no, 'header_ex_vat', o.subtotal_ex_vat, 'lines_ex_vat', c.lines),
                      $runId, 'open'
                    FROM "order" o CROSS JOIN LATERAL (
                      SELECT round(COALESCE(SUM(ol.qty * ol.unit_price_ex_vat * (1 - COALESCE(ol.discount_pct,0)/100)),0),2) AS lines
                      FROM order_line ol WHERE ol.order_id = o.id) c
                    WHERE o.total_inc_vat > 0 AND abs(o.subtotal_ex_vat - c.lines) > 0.01"""
    ),
    // Revenue integrity: an order cannot recognise more than it committed (over-recognition).
    (
      "over_recognition",
      (runId: UUID) => fr"""SELECT 'over_recognition', 'high', 'order', oc.order_id::text, oc.entity_id,
                      oc.committed_ex_vat, r.rec, r.rec - oc.committed_ex_vat, oc.currency,
                      jsonb_build_object('committed', oc.committed_ex_vat, 'recognised', r.rec), $runId, 'open'
                    FROM order_commitment oc CROSS JOIN LATERAL (
                      SELECT COALESCE(SUM(rr.revenue_ex_vat),0) AS rec FROM revenue_recognition rr WHERE rr.order_id = oc.order_id) r
                    WHERE r.rec > oc.committed_ex_vat + 0.01"""
    ),
    // AR completeness: recognised revenue with no invoice to bill it.
    (
      "recognition_without_invoice",
      (runId: UUID) =>
        fr"""SELECT 'recognition_without_invoice', 'high', 'dispatch', rr.dispatch_id::text, rr.entity_id,
                      rr.revenue_ex_vat, 0::numeric, rr.revenue_ex_vat, rr.currency,
                      jsonb_build_object('order_id', rr.order_id::text), $runId, 'open'
                    FROM revenue_recognition rr WHERE rr.revenue_ex_vat > 0
                      AND NOT EXISTS (SELECT 1 FROM order_invoice i
                        WHERE i.dispatch_id = rr.dispatch_id OR (i.order_id = rr.order_id AND i.dispatch_id IS NULL))"""
    ),
    // Costing completeness: serials dispatched without a costed lot (a COGS gap). Aggregated per variant.
    (
      "dispatched_serial_uncosted",
      (runId: UUID) =>
        fr"""SELECT 'dispatched_serial_uncosted', 'high', 'variant', s.product_variant_id::text, NULL::uuid,
                      0::numeric, count(*)::numeric, count(*)::numeric, NULL,
                      jsonb_build_object('variant', s.product_variant_id::text, 'uncosted_dispatched_serials', count(*)), $runId, 'open'
                    FROM serial_unit s WHERE s.dispatch_id IS NOT NULL AND s.lot_batch_id IS NULL
                    GROUP BY s.product_variant_id"""
    )
  )

  private val insertCols =
    fr"""INSERT INTO shadow_finding
         (check_code, severity, scope_type, scope_id, entity_id, expected, actual, variance, currency, detail, run_id, status)"""

  private val onConflict =
    fr"""ON CONFLICT (check_code, scope_type, scope_id) DO UPDATE SET
         severity = EXCLUDED.severity, expected = EXCLUDED.expected, actual = EXCLUDED.actual, variance = EXCLUDED.variance,
         detail = EXCLUDED.detail, currency = EXCLUDED.currency, entity_id = EXCLUDED.entity_id,
         run_id = EXCLUDED.run_id, updated_at = now(),
         status = CASE WHEN shadow_finding.status = 'resolved' THEN 'open' ELSE shadow_finding.status END"""

  private def resolveStale(code: String, runId: UUID): ConnectionIO[Int] =
    sql"""UPDATE shadow_finding SET status = 'resolved', resolved_at = now(), updated_at = now()
          WHERE check_code = $code AND status <> 'resolved' AND run_id IS DISTINCT FROM $runId""".update.run

  def runAll(startedBy: Option[UUID], shadowMode: Boolean): F[Json] =
    (for {
      runId <-
        sql"INSERT INTO shadow_validation_run (started_by, shadow_mode) VALUES ($startedBy, $shadowMode) RETURNING id"
          .query[UUID]
          .unique
      _    <- checks.traverse_(c => (insertCols ++ c._2(runId) ++ onConflict).update.run *> resolveStale(c._1, runId))
      s    <- summaryCio
      open <- sql"SELECT count(*) FROM shadow_finding WHERE status <> 'resolved'".query[Int].unique
      _ <-
        sql"UPDATE shadow_validation_run SET checks_run = ${checks.size}, total_findings = $open, summary = ${s.noSpaces}::jsonb WHERE id = $runId".update.run
    } yield s).transact(xa)

  private def summaryCio: ConnectionIO[Json] =
    (
      sql"SELECT check_code, count(*) FROM shadow_finding WHERE status <> 'resolved' GROUP BY check_code"
        .query[(String, Int)]
        .to[List],
      sql"SELECT severity, count(*) FROM shadow_finding WHERE status <> 'resolved' GROUP BY severity"
        .query[(String, Int)]
        .to[List],
      sql"SELECT status, count(*) FROM shadow_finding GROUP BY status".query[(String, Int)].to[List]
    ).mapN { (byCheck, bySeverity, byStatus) =>
      Json.obj(
        "by_check"    -> Json.obj(byCheck.map { case (k, v) => k -> v.asJson }: _*),
        "by_severity" -> Json.obj(bySeverity.map { case (k, v) => k -> v.asJson }: _*),
        "by_status"   -> Json.obj(byStatus.map { case (k, v) => k -> v.asJson }: _*)
      )
    }

  def summary: F[Json] = summaryCio.transact(xa)

  def findings(status: Option[String], check: Option[String], severity: Option[String], limit: Int): F[List[Json]] = {
    val f = List(
      status.map(s => fr"AND status = $s"),
      check.map(c => fr"AND check_code = $c"),
      severity.map(s => fr"AND severity = $s")
    ).flatten.foldLeft(Fragment.empty)(_ ++ _)
    (fr"""SELECT id, check_code, severity, scope_type, scope_id, entity_id, expected, actual, variance, currency,
            detail::text, status, note, detected_at::text, updated_at::text
          FROM shadow_finding WHERE 1=1""" ++ f ++ fr"ORDER BY severity, check_code, abs(COALESCE(variance,0)) DESC LIMIT $limit")
      .query[
        (
            UUID,
            String,
            String,
            String,
            String,
            Option[UUID],
            Option[BigDecimal],
            Option[BigDecimal],
            Option[BigDecimal],
            Option[String],
            String,
            String,
            Option[String],
            String,
            String
        )
      ]
      .to[List]
      .transact(xa)
      .map(_.map {
        case (id, code, sev, st, sid, eid, exp, act, vr, ccy, detail, status0, note, det, upd) =>
          Json.obj(
            "id"          -> id.toString.asJson,
            "check_code"  -> code.asJson,
            "severity"    -> sev.asJson,
            "scope_type"  -> st.asJson,
            "scope_id"    -> sid.asJson,
            "entity_id"   -> eid.map(_.toString).asJson,
            "expected"    -> exp.asJson,
            "actual"      -> act.asJson,
            "variance"    -> vr.asJson,
            "currency"    -> ccy.asJson,
            "detail"      -> io.circe.parser.parse(detail).getOrElse(Json.Null),
            "status"      -> status0.asJson,
            "note"        -> note.asJson,
            "detected_at" -> det.asJson,
            "updated_at"  -> upd.asJson
          )
      })
  }

  def triage(findingId: UUID, status: String, note: Option[String], actor: UUID): F[Int] =
    sql"""UPDATE shadow_finding SET status = $status, note = COALESCE($note, note),
            resolved_by = CASE WHEN $status IN ('resolved','accepted') THEN $actor ELSE resolved_by END,
            resolved_at = CASE WHEN $status IN ('resolved','accepted') THEN now() ELSE resolved_at END,
            updated_at = now()
          WHERE id = $findingId""".update.run.transact(xa)
}
