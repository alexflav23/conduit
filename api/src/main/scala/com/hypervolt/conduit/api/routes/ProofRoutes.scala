package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.DataLayer
import com.hypervolt.conduit.access.PolicyEngine
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.gl.GlProjectionService
import com.hypervolt.conduit.proof.Asc606Walkthrough
import com.hypervolt.conduit.proof.FormalismRegister
import com.hypervolt.conduit.proof.TamperService
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

// The Proof Center surface (spec doc 31 §3): the law register with LIVE re-performance, the trial balance,
// the ASC-606 five-step bundle for a real order, and the non-prod Tamper Sandbox. Everything is recomputed
// on request — green is earned per click, never cached.
final class ProofRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F], tamperEnabled: Boolean) {

  private val base   = Secured.base[F](auth)
  private val runner = new ControlRunner[F](xa)
  private val gl     = new GlProjectionService[F](xa)
  private val tamper = new TamperService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def view(p: Principal)                                               = PolicyEngine.hasPermission(p, Action.View, "proof_center")
  private def manage(p: Principal)                                             = PolicyEngine.hasPermission(p, Action.Edit, "proof_center")
  private def forbidden                                                        = err(StatusCode.Forbidden, "forbidden", "requires view:proof_center")
  private def interEntity(p: Principal): Boolean =
    p.grants.exists(
      _.permissions.exists(pm => pm.objectType == "ic_match" && pm.viewableLayers.contains(DataLayer.InterEntity))
    )

  private def lawJson(l: com.hypervolt.conduit.proof.Law, lastRuns: Map[String, (String, String, Long)]): Json =
    Json.obj(
      "id"        -> l.id.asJson,
      "title"     -> l.title.asJson,
      "statement" -> l.statement.asJson,
      "mechanism" -> l.mechanism.asJson,
      "origin"    -> l.origin.asJson,
      "pins" -> l.pins.map { pin =>
        val last = lastRuns.get(pin.ref)
        Json.obj(
          "kind"            -> pin.kind.asJson,
          "ref"             -> pin.ref.asJson,
          "re_performable"  -> (pin.kind == "control").asJson,
          "last_result"     -> last.map(_._1).asJson,
          "last_run_at"     -> last.map(_._2).asJson,
          "last_violations" -> last.map(_._3).asJson
        )
      }.asJson
    )

  private val laws =
    base.get
      .in("api" / "v1" / "proof" / "laws")
      .out(jsonBody[Json])
      .serverLogic(p =>
        (_: Unit) =>
          if (!view(p)) Async[F].pure(Left(forbidden))
          else
            sql"""SELECT DISTINCT ON (c.code) c.code, r.result, r.run_at::text,
                         COALESCE((r.detail->>'violations')::bigint, 0)
                  FROM control c JOIN control_run r ON r.control_id = c.id
                  ORDER BY c.code, r.run_at DESC"""
              .query[(String, String, String, Long)]
              .to[List]
              .transact(xa)
              .map { runs =>
                val byCode = runs.map(r => r._1 -> ((r._2, r._3, r._4))).toMap
                Right(Json.obj("laws" -> FormalismRegister.laws.map(lawJson(_, byCode)).asJson))
              }
      )

  private val runControl =
    base.post
      .in("api" / "v1" / "proof" / "controls" / path[String]("code") / "run")
      .out(jsonBody[Json])
      .serverLogic(p =>
        code =>
          if (!view(p)) Async[F].pure(Left(forbidden))
          else
            runner.run(code, None).map {
              case Left(m) => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
              case Right(o) =>
                Right(
                  Json.obj("code" -> o.code.asJson, "result" -> o.result.asJson, "violations" -> o.violations.asJson)
                )
            }
      )

  private val trialBalance =
    base.get
      .in("api" / "v1" / "proof" / "trial-balance" / path[String]("entityId"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!view(p)) Async[F].pure(Left(forbidden))
          else
            Try(UUID.fromString(id)).toOption match {
              case None      => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid id: $id")))
              case Some(eid) => gl.trialBalance(eid).map(Right(_))
            }
      )

  private val journal =
    base.get
      .in("api" / "v1" / "proof" / "journal" / path[String]("invoiceNo"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        invoiceNo =>
          if (!view(p)) Async[F].pure(Left(forbidden))
          else
            Asc606Walkthrough
              .journalForInvoice(invoiceNo, includeInterEntity = interEntity(p))
              .transact(xa)
              .map(Right(_))
      )

  private val asc606 =
    base.get
      .in("api" / "v1" / "proof" / "asc606" / path[String]("orderId"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!view(p)) Async[F].pure(Left(forbidden))
          else
            Try(UUID.fromString(id)).toOption match {
              case None => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid id: $id")))
              case Some(oid) =>
                Asc606Walkthrough.bundle(oid, includeInterEntity = interEntity(p)).transact(xa).map {
                  case None    => Left(err(StatusCode.NotFound, "not_found", s"unknown order $oid"))
                  case Some(j) => Right(j)
                }
            }
      )

  // The Tamper Sandbox (doc 31 §2.5) — double-gated: manage:proof_center AND a non-prod deployment.
  // In prod the surface DOES NOT EXIST (404, the same absence rule as everything else walled).
  private val tamperKind =
    base.post
      .in("api" / "v1" / "proof" / "tamper" / path[String]("kind"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        kind =>
          if (!tamperEnabled) Async[F].pure(Left(err(StatusCode.NotFound, "not_found", "no such endpoint")))
          else if (!manage(p))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires manage:proof_center")))
          else
            tamper.tamper(kind).map {
              case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
              case Right(j) => Right(j)
            }
      )

  private val tamperRestore =
    base.post
      .in("api" / "v1" / "proof" / "tamper-restore")
      .out(jsonBody[Json])
      .serverLogic(p =>
        (_: Unit) =>
          if (!tamperEnabled) Async[F].pure(Left(err(StatusCode.NotFound, "not_found", "no such endpoint")))
          else if (!manage(p))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires manage:proof_center")))
          else tamper.restore.map(Right(_))
      )

  val serverEndpoints = List(laws, runControl, trialBalance, journal, asc606, tamperKind, tamperRestore)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints)
}
