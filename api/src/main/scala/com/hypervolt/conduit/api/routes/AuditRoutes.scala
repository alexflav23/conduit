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
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
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

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
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
          else uuid(id) match {
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
          else uuid(id) match {
            case Left(x)    => Async[F].pure(Left(x))
            case Right(pid) => close.close(pid, p.userId).map {
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
          else uuid(id) match {
            case Left(x)    => Async[F].pure(Left(x))
            case Right(pid) => close.lock(pid, p.userId).map {
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
              case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
              case Right(o) => Right(Json.obj("code" -> o.code.asJson, "result" -> o.result.asJson, "violations" -> o.violations.asJson))
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

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(periods, reconciliations, closePeriod, lockPeriod, controls, runControl, invoiceLineage)
    )
}
