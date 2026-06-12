package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.PolicyEngine
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.access.Projection
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter
import java.util.UUID
import scala.util.Try

// The procurement desk (spec doc 28): the principal's central price catalogue (governed, append-only,
// maker<>checker) and the flash-title match ledger. EVERYTHING here is inter_entity-walled: only admin and
// the procurement role hold view; every payload passes the layer projection — to anyone else these routes
// 403 and the fields do not exist.
final case class PriceListLineReq(variantId: String, unitPrice: BigDecimal)
final case class ProposePriceListReq(
    procurementEntityId: String,
    marketId: String,
    currency: String,
    lines: List[PriceListLineReq]
)
object ProposePriceListReq {
  implicit val lc: io.circe.Codec[PriceListLineReq]   = deriveCodec
  implicit val c: io.circe.Codec[ProposePriceListReq] = deriveCodec
}

final class ProcurementRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def forbid(act: String, obj: String)                                 = err(StatusCode.Forbidden, "forbidden", s"requires $act:$obj")
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def project(p: Principal, obj: String, rows: List[Json]): Json =
    Json.fromValues(rows.map(r => Projection.projectFor(p, obj, r)))

  private val listLists =
    base.get
      .in("api" / "v1" / "procurement" / "price-lists")
      .in(query[Option[String]]("market_id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        market =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "transfer_price_list"))
            Async[F].pure(Left(forbid("view", "transfer_price_list")))
          else
            sql"""SELECT json_build_object(
                    'id', l.id, 'procurement_entity_id', l.procurement_entity_id, 'market_id', l.market_id,
                    'currency', l.currency, 'status', l.status, 'version', l.version,
                    'effective_from', l.effective_from, 'effective_to', l.effective_to,
                    'lines', (SELECT COALESCE(json_agg(json_build_object(
                                'product_variant_id', ll.product_variant_id, 'unit_price', ll.unit_price)), '[]'::json)
                              FROM transfer_price_list_line ll WHERE ll.price_list_id = l.id))::text
                  FROM transfer_price_list l
                  WHERE ($market::uuid IS NULL OR l.market_id = $market::uuid)
                  ORDER BY l.market_id, l.version DESC"""
              .query[String]
              .to[List]
              .transact(xa)
              .map(rows =>
                Right(project(principal, "transfer_price_list", rows.flatMap(io.circe.parser.parse(_).toOption)))
              )
      )

  private val propose =
    base.post
      .in("api" / "v1" / "procurement" / "price-lists")
      .in(jsonBody[ProposePriceListReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "transfer_price_list"))
            Async[F].pure(Left(forbid("create", "transfer_price_list")))
          else
            (uuid(req.procurementEntityId), uuid(req.marketId), req.lines.traverse(l => uuid(l.variantId))).tupled
              .traverse {
                case (pe, market, variants) =>
                  ProcurementCatalogue
                    .propose(
                      pe,
                      market,
                      req.currency,
                      variants.zip(req.lines).map { case (v, l) => ProcurementCatalogue.PriceListLine(v, l.unitPrice) },
                      principal.userId
                    )
                    .transact(xa)
              }
              .map(_.flatMap {
                case Left(m)   => Left(err(StatusCode.UnprocessableEntity, "invalid", m))
                case Right(id) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson))
              })
      )

  private val activate =
    base.post
      .in("api" / "v1" / "procurement" / "price-lists" / path[String]("id") / "activate")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        id =>
          if (!PolicyEngine.hasPermission(principal, Action.Approve, "transfer_price_list"))
            Async[F].pure(Left(forbid("approve", "transfer_price_list")))
          else
            uuid(id)
              .traverse(lid => ProcurementCatalogue.activate(lid, principal.userId).transact(xa))
              .map(_.flatMap {
                case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "invalid", m))
                case Right(_) => Right(Json.obj("status" -> "active".asJson))
              })
      )

  private val matches =
    base.get
      .in("api" / "v1" / "procurement" / "matches")
      .in(query[Option[String]]("order_id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        order =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "ic_match"))
            Async[F].pure(Left(forbid("view", "ic_match")))
          else
            sql"""SELECT json_build_object(
                    'id', m.id, 'dispatch_id', m.dispatch_id, 'order_id', m.order_id,
                    'operating_entity_id', m.operating_entity_id, 'procurement_entity_id', m.procurement_entity_id,
                    'price_list_id', m.price_list_id, 'currency', m.currency,
                    'landed_total', m.landed_total, 'transfer_total', m.transfer_total, 'uplift_total', m.uplift_total,
                    'origin_batch_ids', m.origin_batch_ids, 'elimination_group_id', m.elimination_group_id,
                    'created_at', m.created_at)::text
                  FROM ic_match m
                  WHERE ($order::uuid IS NULL OR m.order_id = $order::uuid)
                  ORDER BY m.created_at DESC LIMIT 500"""
              .query[String]
              .to[List]
              .transact(xa)
              .map(rows => Right(project(principal, "ic_match", rows.flatMap(io.circe.parser.parse(_).toOption))))
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(List(listLists, propose, activate, matches))
}
