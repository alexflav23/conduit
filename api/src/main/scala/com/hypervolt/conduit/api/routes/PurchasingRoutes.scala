package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.purchasing.PurchasingReadRepo
import com.hypervolt.conduit.purchasing.PurchasingService
import com.hypervolt.conduit.purchasing.ReceiveLine
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Purchasing/receiving (M9, doc 02 §H / doc 04 §Inventory). Create a PO, add lines, and receive — receiving lands
// the per-lot cost (USD × fx + freight + duty), increments stock, mints serials, and auto-fills the oldest
// backorders. TB-free (the INV ledger side posts downstream); profitability layer gated to procurement/admin.
final class PurchasingRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base       = Secured.base[F](auth)
  private val purchasing = new PurchasingService[F](xa, new AllocationService[F](xa))

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def forbid(p: Principal, action: Action, obj: String): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, action, obj))(
      err(StatusCode.Forbidden, "forbidden", s"requires ${action.name}:$obj")
    )
  private def reqUuid(j: Json, k: String): Either[(StatusCode, ApiError), UUID] =
    j.hcursor
      .get[String](k)
      .toOption
      .toRight(err(StatusCode.BadRequest, "bad_request", s"missing field: $k"))
      .flatMap(uuid)
  private def num(j: Json, k: String): BigDecimal =
    j.hcursor.get[BigDecimal](k).toOption.getOrElse(BigDecimal(0))
  private def optUuid(j: Json, k: String): Option[UUID] =
    j.hcursor.get[String](k).toOption.flatMap(s => Try(UUID.fromString(s)).toOption)

  // ----- create PO -----
  private val createPo =
    base.post
      .in("api" / "v1" / "purchasing" / "orders")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(p =>
        body =>
          forbid(p, Action.Create, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              reqUuid(body, "entity_id") match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(entity) =>
                  purchasing
                    .createPO(
                      entity,
                      optUuid(body, "supplier_id"),
                      body.hcursor.get[String]("currency").toOption.getOrElse("GBP")
                    )
                    .map { case (id, no) => Right(Json.obj("id" -> id.toString.asJson, "po_no" -> no.asJson)) }
              }
          }
      )

  // ----- add a PO line -----
  private val addLine =
    base.post
      .in("api" / "v1" / "purchasing" / "orders" / path[String]("id") / "lines")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(p => {
        case (poS, body) =>
          forbid(p, Action.Edit, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (uuid(poS), reqUuid(body, "variant_id")).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((poId, variant)) =>
                  purchasing
                    .addPoLine(
                      poId,
                      variant,
                      body.hcursor.get[Int]("qty").toOption.getOrElse(0),
                      num(body, "unit_cost")
                    )
                    .map(id => Right(Json.obj("id" -> id.toString.asJson)))
              }
          }
      })

  // ----- receive a PO line (lands cost, increments stock, mints serials) -----
  private val receive =
    base.post
      .in("api" / "v1" / "purchasing" / "orders" / path[String]("id") / "receive")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(p => {
        case (poS, body) =>
          forbid(p, Action.Edit, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (
                uuid(poS),
                reqUuid(body, "entity_id"),
                reqUuid(body, "location_id"),
                reqUuid(body, "po_line_id"),
                reqUuid(body, "variant_id")
              ).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((poId, entity, location, poLineId, variant)) =>
                  val line = ReceiveLine(
                    poLineId,
                    variant,
                    body.hcursor.get[Int]("qty").toOption.getOrElse(0),
                    num(body, "unit_cost_usd"),
                    num(body, "fx_rate"),
                    num(body, "freight"),
                    num(body, "duty"),
                    body.hcursor.downField("serials").as[List[String]].toOption.getOrElse(Nil),
                    body.hcursor.get[String]("currency").toOption.getOrElse("GBP")
                  )
                  val received = body.hcursor
                    .get[String]("received_date")
                    .toOption
                    .flatMap(s => Try(LocalDate.parse(s)).toOption)
                    .getOrElse(LocalDate.now())
                  purchasing
                    .receive(poId, entity, location, line, received)
                    .map(lotId =>
                      Right(Json.obj("lot_batch_id" -> lotId.toString.asJson, "status" -> "received".asJson))
                    )
              }
          }
      })

  // ----- read: the PO book -----
  private def optUuidS(s: Option[String]): Option[UUID] = s.flatMap(v => Try(UUID.fromString(v)).toOption)

  private val listPos =
    base.get
      .in("api" / "v1" / "purchasing" / "orders")
      .in(query[Option[String]]("entity_id"))
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityF, statusF) =>
          forbid(p, Action.View, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              PurchasingReadRepo
                .listPos(optUuidS(entityF), statusF)
                .transact(xa)
                .map(rows =>
                  Right(
                    Json.obj(
                      "rows"        -> Json.fromValues(rows.map(Projection.projectFor(p, "purchase_order", _))),
                      "can_receive" -> PolicyEngine.hasPermission(p, Action.Edit, "purchase_order").asJson
                    )
                  )
                )
          }
      })

  private val poDetail =
    base.get
      .in("api" / "v1" / "purchasing" / "orders" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        idS =>
          forbid(p, Action.View, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(idS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(id) =>
                  PurchasingReadRepo
                    .poDetail(id)
                    .transact(xa)
                    .map {
                      case Some(row) => Right(Projection.projectFor(p, "purchase_order", row))
                      case None      => Left(err(StatusCode.NotFound, "not_found", s"no PO $idS"))
                    }
              }
          }
      )

  private val stockOps =
    base.get
      .in("api" / "v1" / "purchasing" / "stock-ops")
      .in(query[Option[String]]("entity_id"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case entityF =>
          forbid(p, Action.View, "purchase_order") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (PurchasingReadRepo.stockOps(optUuidS(entityF), 200, 0), PurchasingReadRepo.stockOpsCount(optUuidS(entityF)))
                .tupled
                .transact(xa)
                .map { case (rows, total) => Right(Json.obj("rows" -> Json.fromValues(rows), "total" -> total.asJson)) }
          }
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(List(listPos, poDetail, stockOps, createPo, addLine, receive))
}
