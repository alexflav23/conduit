package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

// M-Period slice 2 (spec doc 32): investigate one accounting period end-to-end. Read-only; assembles, for a
// group period key (and optional entity), the close status + every journal / event / control run /
// reconciliation / document / lineage entry-point whose UTC instant falls in the period window. Period
// assignment is a re-projection of the instant against the window (doc 14 time model / L6) — never a stored
// period stamp — so a late-arriving event lands in the period its instant dictates. The route gates it
// view:accounting_period (finance/auditor/ceo/admin).
final class PeriodInvestigationService[F[_]: Async](xa: Transactor[F]) {

  def investigate(periodKey: String, entity: Option[UUID]): F[Option[Json]] =
    window(periodKey)
      .flatMap {
        case None => none[Json].pure[ConnectionIO]
        case Some((from, to, groupStatus)) =>
          (
            periodStatus(periodKey, entity),
            journals(from, to, entity),
            events(from, to),
            controls(from, to),
            reconciliations(periodKey),
            documents(from, to),
            lineageEntryPoints(from, to)
          ).mapN { (periods, jrn, evs, ctrls, recs, docs, lineage) =>
            Json
              .obj(
                "period_key"      -> periodKey.asJson,
                "from"            -> from.toString.asJson,
                "to"              -> to.toString.asJson,
                "group_status"    -> groupStatus.asJson,
                "entity_periods"  -> periods.asJson,
                "journals"        -> jrn,
                "events"          -> evs.asJson,
                "controls"        -> ctrls.asJson,
                "reconciliations" -> recs.asJson,
                "documents"       -> docs.asJson,
                "lineage"         -> lineage.asJson
              )
              .some
          }
      }
      .transact(xa)

  private def window(periodKey: String): ConnectionIO[Option[(LocalDate, LocalDate, String)]] =
    sql"SELECT period_from, period_to, status FROM reporting_calendar WHERE period_key = $periodKey"
      .query[(LocalDate, LocalDate, String)]
      .option

  private def periodStatus(periodKey: String, entity: Option[UUID]): ConnectionIO[List[Json]] = {
    val entityFilter = entity.fold(Fragment.empty)(e => fr"AND p.entity_id = $e")
    (sql"""SELECT e.name, p.status, p.closed_at::text FROM accounting_period p JOIN entity e ON e.id = p.entity_id
           WHERE p.period_key = $periodKey """ ++ entityFilter ++ fr" ORDER BY e.name")
      .query[(String, String, Option[String])]
      .to[List]
      .map(_.map { case (n, s, c) => Json.obj("entity" -> n.asJson, "status" -> s.asJson, "closed_at" -> c.asJson) })
  }

  // the period's postings, netted per account/side — the trial-balance shape, window-bounded by the instant
  private def journals(from: LocalDate, to: LocalDate, entity: Option[UUID]): ConnectionIO[Json] = {
    val entityFilter = entity.fold(Fragment.empty)(e => fr"AND entity_id = $e")
    (sql"""SELECT account_key, side, count(*), COALESCE(SUM(amount_minor), 0)
           FROM gl_entry WHERE posted AND occurred_at::date >= $from AND occurred_at::date <= $to """ ++ entityFilter ++
      fr" GROUP BY account_key, side ORDER BY account_key, side")
      .query[(String, String, Long, BigDecimal)]
      .to[List]
      .map { rows =>
        val lines = rows.map {
          case (k, side, n, amt) =>
            Json.obj("account" -> k.asJson, "side" -> side.asJson, "count" -> n.asJson, "amount" -> (amt / 100).asJson)
        }
        Json.obj("leg_count" -> rows.map(_._3).sum.asJson, "lines" -> lines.asJson)
      }
  }

  private def events(from: LocalDate, to: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT event_type, count(*) FROM outbox_event
          WHERE occurred_at::date >= $from AND occurred_at::date <= $to GROUP BY event_type ORDER BY event_type"""
      .query[(String, Long)]
      .to[List]
      .map(_.map { case (t, n) => Json.obj("event_type" -> t.asJson, "count" -> n.asJson) })

  private def controls(from: LocalDate, to: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT c.code, r.result, COALESCE((r.detail->>'violations')::bigint, 0), r.run_at::text
          FROM control_run r JOIN control c ON c.id = r.control_id
          WHERE r.run_at::date >= $from AND r.run_at::date <= $to ORDER BY r.run_at DESC"""
      .query[(String, String, Long, String)]
      .to[List]
      .map(_.map {
        case (code, result, v, at) =>
          Json.obj("code" -> code.asJson, "result" -> result.asJson, "violations" -> v.asJson, "run_at" -> at.asJson)
      })

  private def reconciliations(periodKey: String): ConnectionIO[List[Json]] =
    sql"""SELECT rc.type, rc.status, (rc.signed_off_by IS NOT NULL)
          FROM reconciliation rc JOIN accounting_period p ON p.id = rc.period_id
          WHERE p.period_key = $periodKey ORDER BY rc.type"""
      .query[(String, String, Boolean)]
      .to[List]
      .map(_.map {
        case (t, s, signed) => Json.obj("type" -> t.asJson, "status" -> s.asJson, "signed_off" -> signed.asJson)
      })

  private def documents(from: LocalDate, to: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT COALESCE(formatted_number, ''), document_type, status
          FROM document WHERE created_at::date >= $from AND created_at::date <= $to AND status = 'finalised'
          ORDER BY created_at"""
      .query[(String, String, String)]
      .to[List]
      .map(_.map { case (no, kind, s) => Json.obj("number" -> no.asJson, "kind" -> kind.asJson, "status" -> s.asJson) })

  // the invoices recognised in the period — each a one-click entry into the Journal Atlas walk to its CM PO
  private def lineageEntryPoints(from: LocalDate, to: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT DISTINCT invoice_no FROM revenue_recognition
          WHERE invoice_no IS NOT NULL AND recognized_at::date >= $from AND recognized_at::date <= $to
          ORDER BY invoice_no"""
      .query[String]
      .to[List]
      .map(_.map(no => Json.obj("invoice_no" -> no.asJson)))
}
