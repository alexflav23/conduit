package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.inventory.InventoryReadRepo
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

// Inventory read surface (M6/M7): the lot ledger, the serial fleet (paginated over the whole population), ATP, and
// per-serial genealogy. Write/allocation stays in PurchasingRoutes/DispatchRoutes. Cost fields ride the
// profitability layer (projected per principal); the rest is the commercial layer. Lists return {rows,total,limit,
// offset} so the desk pages server-side.
final class InventoryRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val Obj  = "stock_item"

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def forbid(p: Principal): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, Action.View, Obj))(
      err(StatusCode.Forbidden, "forbidden", s"requires view:$Obj")
    )
  private def optUuid(s: Option[String]): Option[UUID] = s.flatMap(v => Try(UUID.fromString(v)).toOption)
  private def clampLimit(l: Option[Int]): Int          = l.getOrElse(50).max(1).min(200)
  private def clampOffset(o: Option[Int]): Int         = o.getOrElse(0).max(0)

  private def page(p: Principal, rows: List[Json], total: Long, limit: Int, offset: Int): Json =
    Json.obj(
      "rows"   -> Json.fromValues(rows.map(Projection.projectFor(p, Obj, _))),
      "total"  -> total.asJson,
      "limit"  -> limit.asJson,
      "offset" -> offset.asJson
    )

  private val serials =
    base.get
      .in("api" / "v1" / "inventory" / "serials")
      .in(query[Option[String]]("entity_id"))
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityF, statusF, qF, limitF, offsetF) =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val (lim, off, ent) = (clampLimit(limitF), clampOffset(offsetF), optUuid(entityF))
              (
                InventoryReadRepo.serialsPage(ent, statusF, qF, lim, off),
                InventoryReadRepo.serialsCount(ent, statusF, qF)
              ).tupled
                .transact(xa)
                .map { case (rows, total) => Right(page(p, rows, total, lim, off)) }
          }
      })

  private val batches =
    base.get
      .in("api" / "v1" / "inventory" / "batches")
      .in(query[Option[String]]("entity_id"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityF, limitF, offsetF) =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val (lim, off, ent) = (clampLimit(limitF), clampOffset(offsetF), optUuid(entityF))
              (InventoryReadRepo.batchesPage(ent, lim, off), InventoryReadRepo.batchesCount(ent)).tupled
                .transact(xa)
                .map { case (rows, total) => Right(page(p, rows, total, lim, off)) }
          }
      })

  private val atp =
    base.get
      .in("api" / "v1" / "inventory" / "atp")
      .in(query[Option[String]]("entity_id"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (entityF, limitF, offsetF) =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val (lim, off, ent) = (clampLimit(limitF), clampOffset(offsetF), optUuid(entityF))
              (InventoryReadRepo.atp(ent, lim, off), InventoryReadRepo.atpCount(ent)).tupled
                .transact(xa)
                .map { case (rows, total) => Right(page(p, rows, total, lim, off)) }
          }
      })

  private val roster =
    base.get
      .in("api" / "v1" / "inventory" / "batches" / path[String]("id") / "roster")
      .out(jsonBody[Json])
      .serverLogic(p =>
        idS =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              Try(UUID.fromString(idS)).toOption match {
                case None => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid id: $idS")))
                case Some(id) =>
                  InventoryReadRepo
                    .batchRoster(id, 1000)
                    .transact(xa)
                    .map {
                      case Some(row) => Right(Projection.projectFor(p, Obj, row))
                      case None      => Left(err(StatusCode.NotFound, "not_found", s"no batch $idS"))
                    }
              }
          }
      )

  private val genealogy =
    base.get
      .in("api" / "v1" / "inventory" / "genealogy")
      .in(query[String]("serial"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        serial =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              InventoryReadRepo
                .genealogy(serial)
                .transact(xa)
                .map {
                  case Some(row) => Right(Projection.projectFor(p, Obj, row))
                  case None      => Left(err(StatusCode.NotFound, "not_found", s"no serial $serial"))
                }
          }
      )

  val serverEndpoints = List(serials, batches, roster, atp, genealogy)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
