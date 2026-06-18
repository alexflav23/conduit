package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnDeskService
import com.hypervolt.conduit.returns.ReturnQueryRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
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

// The returns / RMA REST surface (doc 09 §L). The API drives the TB-free transitions synchronously
// (raise/assess/approve/receive — real 403 SoD / 422 memo) and records the money-posting ones
// (disposition/refund) as command events the ReturnConsumer effects (no TigerBeetle in the API). List + detail
// are scope-filtered (entity/market/channel) and layer-projected (refund_amount → commercial,
// unit_landed_cost → profitability). Authz per doc 09 §J: create/edit/approve:rma, create:credit_note.
final class ReturnRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val desk = new ReturnDeskService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def forbid(p: Principal, action: Action, obj: String): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, action, obj))(
      err(StatusCode.Forbidden, "forbidden", s"requires ${action.name}:$obj")
    )
  private def unprocessable(m: String) = err(StatusCode.UnprocessableEntity, "unprocessable", m)
  private def str(j: Json, k: String)  = j.hcursor.get[String](k).toOption
  private def strReq(j: Json, k: String): Either[(StatusCode, ApiError), String] =
    str(j, k).filter(_.nonEmpty).toRight(err(StatusCode.BadRequest, "bad_request", s"missing field: $k"))

  // ----- raise -----
  private val raise =
    base.post
      .in("api" / "v1" / "orders" / path[String]("id") / "returns")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(p => {
        case (orderS, body) =>
          forbid(p, Action.Create, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (uuid(orderS), strReq(body, "type"), strReq(body, "reason_code")).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((orderId, rType, reason)) =>
                  val scope = str(body, "scope").getOrElse("line")
                  val lines = body.hcursor.downField("lines").values.toList.flatten.flatMap(parseLine)
                  if (lines.isEmpty)
                    Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "at least one line is required")))
                  else
                    desk
                      .raise(orderId, rType, scope, reason, p.userId, lines)
                      .map(rmaId => Right(Json.obj("id" -> rmaId.toString.asJson, "status" -> "raised".asJson)))
              }
          }
      })

  private def parseLine(j: Json): Option[RaiseLine] =
    j.hcursor.get[String]("order_line_id").toOption.flatMap(s => Try(UUID.fromString(s)).toOption).map { olid =>
      RaiseLine(olid, str(j, "serial"), str(j, "component_ref"), j.hcursor.get[Int]("qty").toOption.getOrElse(1))
    }

  // ----- assess -----
  private val assess =
    base.post
      .in("api" / "v1" / "returns" / path[String]("id") / "assess")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (rmaS, body) =>
          forbid(p, Action.Edit, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(rmaS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(rmaId) =>
                  val grades = body.hcursor.downField("lines").values.toList.flatten.flatMap { l =>
                    (
                      l.hcursor.get[String]("rma_line_id").toOption.flatMap(s => Try(UUID.fromString(s)).toOption),
                      l.hcursor.get[String]("condition_grade").toOption
                    ).mapN((id, g) => (id, g))
                  }
                  desk
                    .assess(rmaId, grades, p.userId)
                    .as(Right(Json.obj("id" -> rmaS.asJson, "status" -> "assessed".asJson)))
              }
          }
      })

  // ----- approve (maker-checker; SoD enforced in ReturnDeskService) -----
  private val approve =
    base.post
      .in("api" / "v1" / "returns" / path[String]("id") / "approve")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (rmaS, body) =>
          forbid(p, Action.Approve, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(rmaS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(rmaId) =>
                  desk.approve(rmaId, p.userId, str(body, "approval_memo_ref")).map {
                    case Right(_)                               => Right(Json.obj("id" -> rmaS.asJson, "status" -> "approved".asJson))
                    case Left(m) if m.contains("self-approval") => Left(err(StatusCode.Forbidden, "forbidden", m))
                    case Left(m)                                => Left(unprocessable(m))
                  }
              }
          }
      })

  // ----- receive -----
  private val receive =
    base.post
      .in("api" / "v1" / "returns" / path[String]("id") / "receive")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p =>
        in =>
          forbid(p, Action.Edit, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(in._1) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(rmaId) =>
                  desk.receive(rmaId).map {
                    case Right(_) => Right(Json.obj("id" -> in._1.asJson, "status" -> "received".asJson))
                    case Left(m)  => Left(unprocessable(m))
                  }
              }
          }
      )

  // ----- disposition (deferred to the consumer; validated synchronously here) -----
  private val disposition =
    base.post
      .in("api" / "v1" / "returns" / path[String]("id") / "disposition")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Accepted).and(jsonBody[Json]))
      .serverLogic(p => {
        case (rmaS, body) =>
          forbid(p, Action.Edit, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (uuid(rmaS), strReq(body, "rma_line_id").flatMap(uuid), strReq(body, "disposition")).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((rmaId, lineId, choice)) =>
                  val loc = str(body, "location_id").flatMap(s => Try(UUID.fromString(s)).toOption)
                  desk.requestDisposition(rmaId, lineId, choice, loc, p.userId).map {
                    case Right(_) => Right(Json.obj("id" -> rmaS.asJson, "status" -> "disposition_requested".asJson))
                    case Left(m)  => Left(unprocessable(m))
                  }
              }
          }
      })

  // ----- refund (deferred to the consumer; create:credit_note) -----
  private val refund =
    base.post
      .in("api" / "v1" / "returns" / path[String]("id") / "refund")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Accepted).and(jsonBody[Json]))
      .serverLogic(p => {
        case (rmaS, body) =>
          forbid(p, Action.Create, "credit_note") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (uuid(rmaS), strReq(body, "refund_method")).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((rmaId, method)) =>
                  desk.requestRefund(rmaId, method, p.userId).map {
                    case Right(_) => Right(Json.obj("id" -> rmaS.asJson, "status" -> "refund_requested".asJson))
                    case Left(m)  => Left(unprocessable(m))
                  }
              }
          }
      })

  // ----- list (scope-filtered + layer-projected) -----
  private val list =
    base.get
      .in("api" / "v1" / "returns")
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("order_id"))
      .in(query[Option[String]]("type"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (statusF, orderF, typeF) =>
          forbid(p, Action.View, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val scopePred = ScopePredicate.forPrincipal(p, "rma")
              val filters = List(
                statusF.map(s => fr"AND status = $s"),
                orderF.flatMap(o => Try(UUID.fromString(o)).toOption).map(o => fr"AND order_id = $o"),
                typeF.map(t => fr"AND type = $t")
              ).flatten.foldLeft(Fragment.empty)(_ ++ _)
              ReturnQueryRepo
                .list(scopePred, filters)
                .transact(xa)
                .map(rows => Right(Json.fromValues(rows.map(Projection.projectFor(p, "rma", _)))))
          }
      })

  // ----- detail -----
  private val detail =
    base.get
      .in("api" / "v1" / "returns" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        in =>
          forbid(p, Action.View, "rma") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(in) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(rmaId) =>
                  ReturnQueryRepo.detail(rmaId).transact(xa).map {
                    case None    => Right(Json.obj("error" -> s"unknown rma $rmaId".asJson))
                    case Some(j) => Right(Projection.projectFor(p, "rma", j))
                  }
              }
          }
      )

  val serverEndpoints = List(raise, assess, approve, receive, disposition, refund, list, detail)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
