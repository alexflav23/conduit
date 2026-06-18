package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.intercompany.IcQueryRepo
import com.hypervolt.conduit.intercompany.IcRepo
import com.hypervolt.conduit.intercompany.NewPolicy
import com.hypervolt.conduit.intercompany.TpPolicyService
import com.hypervolt.conduit.intercompany.TransferPricing
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.RoundingPolicy
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class NewPolicyReq(
    from_entity_id: String,
    to_entity_id: String,
    method: String,
    markup_pct: Option[BigDecimal],
    resale_margin_pct: Option[BigDecimal],
    fixed_price: Option[BigDecimal],
    tp_currency: Option[String],
    documentation_method: Option[String]
)
object NewPolicyReq { implicit val codec: Codec[NewPolicyReq] = deriveCodec }

// Intercompany & transfer-pricing REST surface (doc 13 §8). Transfer prices, lot costs and policy detail project
// only to the inter_entity layer; consolidated/CTA figures only to treasury. Policy activation is maker-checker.
final class IntercompanyRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base    = Secured.base[F](auth)
  private val service = new TpPolicyService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def forbid(layerObj: String) = err(StatusCode.Forbidden, "forbidden", s"requires view:$layerObj")
  private def project(p: Principal, obj: String, rows: List[Json]): Json =
    Json.fromValues(rows.map(r => Projection.projectFor(p, obj, r)))

  private val policiesList =
    base.get
      .in("api" / "v1" / "intercompany" / "policies")
      .in(query[Option[String]]("from_entity_id"))
      .in(query[Option[String]]("to_entity_id"))
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (f, t, st) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "transfer_price_policy"))
            Async[F].pure(Left(forbid("transfer_price_policy")))
          else
            (f.traverse(uuid), t.traverse(uuid)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fo, to_)) =>
                IcQueryRepo
                  .policies(fo, to_, st)
                  .transact(xa)
                  .map(rows => Right(project(principal, "transfer_price_policy", rows)))
            }
      })

  private val policyCreate =
    base.post
      .in("api" / "v1" / "intercompany" / "policies")
      .in(jsonBody[NewPolicyReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "transfer_price_policy"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:transfer_price_policy")))
          else
            (uuid(req.from_entity_id), uuid(req.to_entity_id)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((f, t)) =>
                service
                  .create(
                    NewPolicy(
                      f,
                      t,
                      req.method,
                      req.markup_pct,
                      req.resale_margin_pct,
                      req.fixed_price,
                      req.tp_currency,
                      req.documentation_method
                    ),
                    principal.userId
                  )
                  .map {
                    case Left(m)   => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                    case Right(id) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson))
                  }
            }
      )

  private val policyApprove =
    base.post
      .in("api" / "v1" / "intercompany" / "policies" / path[String]("id") / "approve")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.Approve, "transfer_price_policy"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires approve:transfer_price_policy")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                service.approve(id, principal.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "active".asJson))
                }
            }
      )

  private val preview =
    base.get
      .in("api" / "v1" / "intercompany" / "transfer-price" / "preview")
      .in(query[String]("from_entity_id"))
      .in(query[String]("to_entity_id"))
      .in(query[String]("variant"))
      .in(query[String]("lot_batch_id"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (f, t, vr, lotId) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "transfer_price_policy"))
            Async[F].pure(Left(forbid("transfer_price_policy")))
          else
            (uuid(f), uuid(t), uuid(vr), uuid(lotId)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fe, te, variant, lot)) =>
                (
                  IcRepo.activePolicy(fe, te, variant, LocalDate.now()),
                  IcRepo.lot(lot),
                  IcRepo.resaleAnchor(variant)
                ).tupled
                  .transact(xa)
                  .map {
                    case (None, _, _) => Left(err(StatusCode.UnprocessableEntity, "unprocessable", "no active policy"))
                    case (_, None, _) => Left(err(StatusCode.UnprocessableEntity, "unprocessable", "lot not found"))
                    case (Some(pol), Some(l), anchor) =>
                      val ccy = Currency.fromCode(l.currency).getOrElse(Currency.fromCode("GBP").get)
                      val m   = TransferPricing.Method.fromCode(pol.method)
                      m.flatMap(mm =>
                        TransferPricing
                          .unitPrice(
                            TransferPricing.Policy(mm, pol.markupPct, pol.resaleMarginPct, pol.fixedPrice),
                            l.landedUnitCost,
                            anchor,
                            ccy,
                            RoundingPolicy.HalfUp
                          )
                          .toOption
                      ) match {
                        case None => Left(err(StatusCode.UnprocessableEntity, "unprocessable", "cannot price"))
                        case Some(tp) =>
                          Right(
                            Json.obj(
                              "method"               -> pol.method.asJson,
                              "lot_landed_unit_cost" -> l.landedUnitCost.asJson,
                              "transfer_unit_price"  -> tp.asJson,
                              "tp_currency"          -> ccy.code.asJson,
                              "policy_version"       -> pol.version.asJson
                            )
                          )
                      }
                  }
            }
      })

  private val movementsList =
    base.get
      .in("api" / "v1" / "intercompany" / "movements")
      .in(query[Option[String]]("from_entity_id"))
      .in(query[Option[String]]("to_entity_id"))
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (f, t, st) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "intercompany_link"))
            Async[F].pure(Left(forbid("intercompany_link")))
          else
            (f.traverse(uuid), t.traverse(uuid)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((fo, to_)) =>
                IcQueryRepo
                  .movements(fo, to_, st)
                  .transact(xa)
                  .map(rows => Right(project(principal, "intercompany_link", rows)))
            }
      })

  private val movementOne =
    base.get
      .in("api" / "v1" / "intercompany" / "movements" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "intercompany_link"))
            Async[F].pure(Left(forbid("intercompany_link")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                (IcQueryRepo.movements(None, None, None), IcQueryRepo.tpDocuments(id)).tupled.transact(xa).map {
                  case (movs, docs) =>
                    val mov = movs.find(_.hcursor.downField("id").as[String].toOption.contains(id.toString))
                    val movP =
                      mov.map(m => Projection.projectFor(principal, "intercompany_link", m)).getOrElse(Json.Null)
                    val docsP = docs.map(d => Projection.projectFor(principal, "tp_document", d))
                    Right(movP.deepMerge(Json.obj("tp_documents" -> Json.fromValues(docsP))))
                }
            }
      )

  private val topology =
    base.get
      .in("api" / "v1" / "intercompany" / "topology")
      .in(query[String]("operating_entity_id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        op =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "intercompany_link"))
            Async[F].pure(Left(forbid("intercompany_link")))
          else
            uuid(op) match {
              case Left(e)  => Async[F].pure(Left(e))
              case Right(o) => IcQueryRepo.topology(o).transact(xa).map(Right(_))
            }
      )

  private def consRoute(seg: String, q: String => doobie.ConnectionIO[Json]) =
    base.get
      .in("api" / "v1" / "consolidation" / seg)
      .in(query[String]("period"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        period =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "consolidation"))
            Async[F].pure(Left(forbid("consolidation")))
          else q(period).transact(xa).map(Right(_))
      )

  private val icBalances =
    consRoute("intercompany-balances", p => IcQueryRepo.intercompanyBalances(p).map(Json.fromValues(_)))
  private val eliminations = consRoute("eliminations", p => IcQueryRepo.eliminations(p).map(Json.fromValues(_)))
  private val translate    = consRoute("translate", IcQueryRepo.translate)

  val serverEndpoints = List(
    policiesList,
    policyCreate,
    policyApprove,
    preview,
    movementsList,
    movementOne,
    topology,
    icBalances,
    eliminations,
    translate
  )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
