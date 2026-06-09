package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.close.AuditQueryRepo
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.close.LineageService
import com.hypervolt.conduit.close.PeriodCloseService
import com.hypervolt.conduit.close.ReconResult
import com.hypervolt.conduit.close.ReconciliationService
import com.hypervolt.conduit.gl.ConsolidationService
import com.hypervolt.conduit.gl.GlProjectionService
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Auditability Center surface (doc 14 §6): the period close board, reconciliation results, the control register
// + on-demand control runs, and the lineage explorer (figure → ledger → events → document). Finance/auditor
// read; finance closes/locks. All Postgres-backed (no TB-in-API); running a reconciliation stays server-side.
final class AuditRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base    = Secured.base[F](auth)
  private val close   = new PeriodCloseService[F](xa)
  private val runner  = new ControlRunner[F](xa)
  private val lineage = new LineageService[F](xa)
  private val recon   = new ReconciliationService[F](xa)
  private val glProj  = new GlProjectionService[F](xa)
  private val consol  = new ConsolidationService[F](xa)

  private def reconJson(r: ReconResult): Json =
    Json.obj(
      "id"       -> r.id.toString.asJson,
      "expected" -> r.expected.asJson,
      "actual"   -> r.actual.asJson,
      "variance" -> r.variance.asJson,
      "status"   -> r.status.asJson
    )

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def instant(s: String): Either[(StatusCode, ApiError), Instant] =
    Try(Instant.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid timestamp: $s"))
  private def localDate(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid date: $s"))
  private def gate(p: Principal, obj: String) = PolicyEngine.hasPermission(p, Action.View, obj)
  private def forbid(obj: String)             = err(StatusCode.Forbidden, "forbidden", s"requires view:$obj")

  private val periods =
    base.get
      .in("api" / "v1" / "finance" / "periods")
      .in(query[Option[String]]("entity"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        e =>
          if (!gate(p, "accounting_period")) Async[F].pure(Left(forbid("accounting_period")))
          else
            e.traverse(uuid) match {
              case Left(x)   => Async[F].pure(Left(x))
              case Right(eo) => AuditQueryRepo.periods(eo).transact(xa).map(r => Right(Json.fromValues(r)))
            }
      )

  private val reconciliations =
    base.get
      .in("api" / "v1" / "finance" / "periods" / path[String]("id") / "reconciliations")
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!gate(p, "reconciliation")) Async[F].pure(Left(forbid("reconciliation")))
          else
            uuid(id) match {
              case Left(x)    => Async[F].pure(Left(x))
              case Right(pid) => AuditQueryRepo.reconciliations(pid).transact(xa).map(r => Right(Json.fromValues(r)))
            }
      )

  private val closePeriod =
    base.post
      .in("api" / "v1" / "finance" / "periods" / path[String]("id") / "close")
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "accounting_period"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:accounting_period")))
          else
            uuid(id) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(pid) =>
                close.close(pid, p.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> pid.toString.asJson, "status" -> "closed".asJson))
                }
            }
      )

  private val lockPeriod =
    base.post
      .in("api" / "v1" / "finance" / "periods" / path[String]("id") / "lock")
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "accounting_period"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:accounting_period")))
          else
            uuid(id) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(pid) =>
                close.lock(pid, p.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> pid.toString.asJson, "status" -> "locked".asJson))
                }
            }
      )

  private val controls =
    base.get
      .in("api" / "v1" / "finance" / "controls")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!gate(p, "control")) Async[F].pure(Left(forbid("control")))
          else AuditQueryRepo.controls.transact(xa).map(r => Right(Json.fromValues(r)))
      )

  private val runControl =
    base.post
      .in("api" / "v1" / "finance" / "controls" / path[String]("code") / "run")
      .out(jsonBody[Json])
      .serverLogic(p =>
        code =>
          if (!gate(p, "control")) Async[F].pure(Left(forbid("control")))
          else
            runner.run(code, None).map {
              case Left(m) => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
              case Right(o) =>
                Right(
                  Json.obj("code" -> o.code.asJson, "result" -> o.result.asJson, "violations" -> o.violations.asJson)
                )
            }
      )

  private val invoiceLineage =
    base.get
      .in("api" / "v1" / "finance" / "lineage")
      .in(query[String]("invoice_no"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        no =>
          if (!gate(p, "accounting_period")) Async[F].pure(Left(forbid("accounting_period")))
          else
            AuditQueryRepo.resolveInvoiceNo(no).transact(xa).flatMap {
              case None      => Async[F].pure(Right(Json.obj("error" -> s"unknown invoice $no".asJson)))
              case Some(iid) => lineage.forInvoice(iid).map(j => Right(j.getOrElse(Json.Null)))
            }
      )

  // Run the gl_entry-backed reconciliations synchronously (no TB on the request path): AR↔invoices + the
  // trial-balance tie. The gl_vs_tb MIRROR check reads TigerBeetle and so runs in the consumer, not here.
  private val runReconciliations =
    base.post
      .in("api" / "v1" / "finance" / "periods" / path[String]("id") / "reconciliations" / "run")
      .in(query[String]("entity"))
      .in(query[String]("currency"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (id, entityS, currency) =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "reconciliation"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:reconciliation")))
          else
            (uuid(id), uuid(entityS)).tupled match {
              case Left(x) => Async[F].pure(Left(x))
              case Right((pid, entity)) =>
                (recon.arVsInvoices(pid, entity, currency), recon.tbVsGl(pid, currency)).tupled.map {
                  case (ar, tb) => Right(Json.obj("ar_vs_invoices" -> reconJson(ar), "tb_vs_gl" -> reconJson(tb)))
                }
            }
      })

  private val signOffRecon =
    base.post
      .in("api" / "v1" / "finance" / "reconciliations" / path[String]("rid") / "sign-off")
      .out(jsonBody[Json])
      .serverLogic(p =>
        rid =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "reconciliation"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:reconciliation")))
          else
            uuid(rid) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(r) =>
                recon.signOff(r, p.userId).map {
                  case 0 => Left(err(StatusCode.UnprocessableEntity, "unprocessable", "no such reconciliation"))
                  case _ => Right(Json.obj("id" -> r.toString.asJson, "signed_off" -> true.asJson))
                }
            }
      )

  private val trialBalance =
    base.get
      .in("api" / "v1" / "finance" / "gl" / "trial-balance")
      .in(query[String]("entity"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        entityS =>
          if (!gate(p, "gl_entry")) Async[F].pure(Left(forbid("gl_entry")))
          else
            uuid(entityS) match {
              case Left(x)  => Async[F].pure(Left(x))
              case Right(e) => glProj.trialBalance(e).map(Right(_))
            }
      )

  private val glAsOf =
    base.get
      .in("api" / "v1" / "finance" / "gl" / "as-of")
      .in(query[String]("entity"))
      .in(query[String]("as_of"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityS, asOfS) =>
          if (!gate(p, "gl_entry")) Async[F].pure(Left(forbid("gl_entry")))
          else
            (uuid(entityS), instant(asOfS)).tupled match {
              case Left(x)          => Async[F].pure(Left(x))
              case Right((e, asOf)) => glProj.asOf(e, asOf).map(Right(_))
            }
      })

  // Hedge-aware, as-of consolidation to a presentation currency — an immutable, re-derivable run (doc 14 §2.4).
  private val runConsolidation =
    base.post
      .in("api" / "v1" / "finance" / "consolidation" / "run")
      .in(query[String]("as_of"))
      .in(query[Option[String]]("presentation"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (asOfS, presentation) =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "consolidation"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:consolidation")))
          else
            localDate(asOfS) match {
              case Left(x)     => Async[F].pure(Left(x))
              case Right(asOf) => consol.run(asOf, presentation.getOrElse("USD"), Some(p.userId)).map(Right(_))
            }
      })

  private val consolidationLineage =
    base.get
      .in("api" / "v1" / "finance" / "consolidation" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!gate(p, "consolidation")) Async[F].pure(Left(forbid("consolidation")))
          else
            uuid(id) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(rid) =>
                consol.lineage(rid).map {
                  case Some(j) => Right(j)
                  case None    => Right(Json.obj("error" -> s"unknown consolidation run $rid".asJson))
                }
            }
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        periods,
        reconciliations,
        closePeriod,
        lockPeriod,
        controls,
        runControl,
        invoiceLineage,
        runReconciliations,
        signOffRecon,
        trialBalance,
        glAsOf,
        runConsolidation,
        consolidationLineage
      )
    )
}
