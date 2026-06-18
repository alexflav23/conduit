package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
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

// The fulfilment surface (M6, doc 04 §Orders). Dispatch decrements stock + flips serials and emits the events the
// consumer turns into revenue (deliver → dispatch.delivered → RevenueRecognitionConsumer). All TB-free: the API
// never touches TigerBeetle (house rule) — money posts downstream. Availability is the synchronous ATP read.
final class DispatchRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base     = Secured.base[F](auth)
  private val dispatch = new DispatchService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def forbid(p: Principal, action: Action, obj: String): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, action, obj))(
      err(StatusCode.Forbidden, "forbidden", s"requires ${action.name}:$obj")
    )
  private def optUuid(j: Json, k: String): Option[UUID] =
    j.hcursor.get[String](k).toOption.flatMap(s => Try(UUID.fromString(s)).toOption)

  private def parseLine(j: Json): Option[DispatchLineInput] =
    optUuid(j, "order_line_id").map(olid =>
      DispatchLineInput(
        olid,
        j.hcursor.get[Int]("qty").toOption.getOrElse(0),
        j.hcursor.downField("serials").as[List[String]].toOption.getOrElse(Nil)
      )
    )

  // ----- dispatch a (tranche of an) order -----
  private val createDispatch =
    base.post
      .in("api" / "v1" / "orders" / path[String]("id") / "dispatch")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(p => {
        case (orderS, body) =>
          forbid(p, Action.Create, "dispatch") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(orderS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(orderId) =>
                  val lines = body.hcursor.downField("lines").values.toList.flatten.flatMap(parseLine)
                  if (lines.isEmpty)
                    Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "at least one line is required")))
                  else
                    dispatch
                      .dispatch(
                        orderId,
                        optUuid(body, "tranche_id"),
                        optUuid(body, "carrier_id"),
                        body.hcursor.get[String]("tracking").toOption,
                        lines
                      )
                      .map {
                        case Right(id) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "created".asJson))
                        case Left(m)   => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                      }
              }
          }
      })

  // ----- deliver (control transfer → invoice + dispatch.delivered → recognition) -----
  private val deliver =
    base.post
      .in("api" / "v1" / "dispatches" / path[String]("id") / "deliver")
      .out(jsonBody[Json])
      .serverLogic(p =>
        dispatchS =>
          forbid(p, Action.Edit, "dispatch") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(dispatchS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(dispatchId) =>
                  dispatch.deliver(dispatchId).map {
                    case Right(_) => Right(Json.obj("id" -> dispatchS.asJson, "status" -> "delivered".asJson))
                    case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  }
              }
          }
      )

  // ----- availability (ATP): on-hand-allocatable for a variant at an entity -----
  private val availability =
    base.get
      .in("api" / "v1" / "inventory" / "availability")
      .in(query[String]("entity"))
      .in(query[String]("variant"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityS, variantS) =>
          forbid(p, Action.View, "stock_item") match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (uuid(entityS), uuid(variantS)).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((entity, variant)) =>
                  (InventoryRepo.available(entity, variant), InventoryRepo.allocatedQty(entity, variant)).tupled
                    .transact(xa)
                    .map {
                      case (avail, alloc) =>
                        Right(
                          Json.obj(
                            "entity"    -> entityS.asJson,
                            "variant"   -> variantS.asJson,
                            "available" -> avail.asJson,
                            "allocated" -> alloc.asJson
                          )
                        )
                    }
              }
          }
      })

  val serverEndpoints = List(createDispatch, deliver, availability)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
