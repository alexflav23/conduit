package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.shadow.ShadowValidationService
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

// The shadow-validation REST surface (doc 33 §5). Run the harness, browse the triage queue, see the cutover-gate
// summary, and triage a finding (investigating / accepted / resolved). view:shadow_validation to read; edit to run
// + triage. shadowMode flag echoes whether outbound effectors are muted when this ran.
final class ShadowValidationRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F], shadowMode: Boolean) {

  private val base = Secured.base[F](auth)
  private val svc  = new ShadowValidationService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def forbid(p: Principal, action: Action): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, action, "shadow_validation"))(
      err(StatusCode.Forbidden, "forbidden", s"requires ${action.name}:shadow_validation")
    )

  // ----- run the harness -----
  private val run =
    base.post
      .in("api" / "v1" / "shadow" / "validate")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.Edit) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              svc
                .runAll(Some(p.userId), shadowMode)
                .map(s => Right(Json.obj("shadow_mode" -> shadowMode.asJson, "summary" -> s)))
          }
      )

  // ----- cutover-gate summary -----
  private val summary =
    base.get
      .in("api" / "v1" / "shadow" / "summary")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p, Action.View) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => svc.summary.map(s => Right(Json.obj("shadow_mode" -> shadowMode.asJson, "summary" -> s)))
          }
      )

  // ----- triage queue -----
  private val findings =
    base.get
      .in("api" / "v1" / "shadow" / "findings")
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("check"))
      .in(query[Option[String]]("severity"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (status, check, severity, limit) =>
          forbid(p, Action.View) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              svc.findings(status, check, severity, limit.getOrElse(200)).map(rows => Right(Json.fromValues(rows)))
          }
      })

  // ----- triage a finding -----
  private val triage =
    base.post
      .in("api" / "v1" / "shadow" / "findings" / path[String]("id") / "triage")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (idS, body) =>
          forbid(p, Action.Edit) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val status = body.hcursor.get[String]("status").toOption.getOrElse("")
              val valid  = Set("open", "investigating", "accepted", "resolved")
              (Try(UUID.fromString(idS)).toOption, Option.when(valid(status))(status)) match {
                case (None, _) => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid id: $idS")))
                case (_, None) =>
                  Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"status must be one of $valid")))
                case (Some(id), Some(st)) =>
                  svc.triage(id, st, body.hcursor.get[String]("note").toOption, p.userId).map {
                    case 0 => Left(err(StatusCode.NotFound, "not_found", s"no finding $idS"))
                    case _ => Right(Json.obj("id" -> idS.asJson, "status" -> st.asJson))
                  }
              }
          }
      })

  val serverEndpoints = List(run, summary, findings, triage)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
