package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.order._
import com.hypervolt.conduit.party.PartyRepo
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.time.Instant
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

final case class CreatePartyReq(
    displayName: String,
    partyType: String,
    isOrganization: Boolean,
    channelId: Option[String],
    marketId: Option[String],
    customerPoRequired: Option[Boolean]
)
object CreatePartyReq { implicit val codec: Codec[CreatePartyReq] = deriveCodec }

final case class BillingProfileReq(
    billingName: String,
    currency: String,
    paymentTermsDays: Int,
    taxRegimeDefault: Option[String]
)
object BillingProfileReq { implicit val codec: Codec[BillingProfileReq] = deriveCodec }

final case class CreditProfileReq(creditLimit: String, currency: String, termsDays: Int, policy: String)
object CreditProfileReq { implicit val codec: Codec[CreditProfileReq] = deriveCodec }

final case class TrancheReq(seq: Int, qty: Int, requestedDate: String)
object TrancheReq { implicit val codec: Codec[TrancheReq] = deriveCodec }

final case class OrderLineReq(sku: String, qty: Int, unitPriceExVat: Option[String], schedule: Option[List[TrancheReq]])
object OrderLineReq { implicit val codec: Codec[OrderLineReq] = deriveCodec }

final case class CreateOrderReq(
    `type`: String,
    entityId: Option[String],
    soldToPartyId: String,
    billToPartyId: String,
    channelId: String,
    marketId: String,
    currency: String,
    paymentMethod: String,
    customerPoNumber: Option[String],
    requestedDelivery: Option[String],
    lines: List[OrderLineReq]
)
object CreateOrderReq { implicit val codec: Codec[CreateOrderReq] = deriveCodec }

final case class AmendReq(lines: List[OrderLineReq], reason: Option[String])
object AmendReq { implicit val codec: Codec[AmendReq] = deriveCodec }

final class CommerceRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base         = Secured.base[F](auth)
  private val orderService = new OrderService[F](xa)

  private def err(status: StatusCode, code: String, msg: String): (StatusCode, ApiError) = (status, ApiError(code, msg))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid uuid: $s"))
  private def optUuid(s: Option[String]): Either[(StatusCode, ApiError), Option[UUID]] = s.traverse(uuid)
  private def decimal(s: String): Either[(StatusCode, ApiError), BigDecimal] =
    Try(BigDecimal(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid decimal: $s"))
  private def date(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid date: $s"))

  private def orderErrorStatus(e: OrderError): (StatusCode, ApiError) =
    e match {
      case _: OrderError.AmendRejected => (StatusCode.Conflict, ApiError(e.code, e.message))
      case _: OrderError.NotFound      => (StatusCode.NotFound, ApiError(e.code, e.message))
      case _                           => (StatusCode.UnprocessableEntity, ApiError(e.code, e.message))
    }

  private def toLineInputs(lines: List[OrderLineReq]): Either[(StatusCode, ApiError), List[PlaceLineInput]] =
    lines.traverse { l =>
      for {
        price <- l.unitPriceExVat.traverse(decimal)
        schedule <-
          l.schedule.getOrElse(Nil).traverse(t => date(t.requestedDate).map(d => TrancheInput(t.seq, t.qty, d)))
      } yield PlaceLineInput(l.sku, l.qty, price, schedule)
    }

  // ----- parties -----

  private val createParty =
    base.post
      .in("api" / "v1" / "parties")
      .in(jsonBody[CreatePartyReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(_ =>
        req =>
          (for { ch <- optUuid(req.channelId); mk <- optUuid(req.marketId) } yield (ch, mk)) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((ch, mk)) =>
              PartyRepo
                .create(
                  req.displayName,
                  req.partyType,
                  req.isOrganization,
                  ch,
                  mk,
                  req.customerPoRequired.getOrElse(false)
                )
                .transact(xa)
                .map(id => Right(Json.obj("id" -> id.toString.asJson)))
          }
      )

  private val billingProfile =
    base.post
      .in("api" / "v1" / "parties" / path[String]("id") / "billing-profile")
      .in(jsonBody[BillingProfileReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(_ => {
        case (idStr, req) =>
          uuid(idStr) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(pid) =>
              PartyRepo
                .addBillingProfile(pid, req.billingName, req.currency, req.paymentTermsDays, req.taxRegimeDefault)
                .transact(xa)
                .map(id => Right(Json.obj("id" -> id.toString.asJson)))
          }
      })

  private val creditProfile =
    base.post
      .in("api" / "v1" / "parties" / path[String]("id") / "credit-profile")
      .in(jsonBody[CreditProfileReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic(_ => {
        case (idStr, req) =>
          (for { pid <- uuid(idStr); limit <- decimal(req.creditLimit) } yield (pid, limit)) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((pid, limit)) =>
              PartyRepo
                .addCreditProfile(pid, limit, req.currency, req.termsDays, req.policy, "self")
                .transact(xa)
                .map(id => Right(Json.obj("id" -> id.toString.asJson)))
          }
      })

  // ----- orders -----

  private val placeOrder =
    base.post
      .in("api" / "v1" / "orders")
      .in(jsonBody[CreateOrderReq])
      .out(statusCode.and(jsonBody[Json]))
      .serverLogic(principal =>
        req => {
          val parsed =
            for {
              channel <- uuid(req.channelId)
              market  <- uuid(req.marketId)
              soldTo  <- uuid(req.soldToPartyId)
              billTo  <- uuid(req.billToPartyId)
              entity  <- optUuid(req.entityId)
              reqDel  <- req.requestedDelivery.traverse(date)
              lines   <- toLineInputs(req.lines)
              _ <- Either.cond(
                PolicyEngine.authorize(
                  principal,
                  Action.Create,
                  "order",
                  Target(entity, Some(market), Some(channel), Some(principal.userId))
                ),
                (),
                err(StatusCode.Forbidden, "forbidden", "requires create:order")
              )
            } yield PlaceOrderInput(
              req.`type`,
              entity,
              soldTo,
              billTo,
              channel,
              market,
              req.currency,
              req.paymentMethod,
              req.customerPoNumber,
              reqDel,
              Some(principal.userId),
              lines
            )
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(in) =>
              orderService.place(in, Instant.now()).map {
                case Left(e) => Left(orderErrorStatus(e))
                case Right(o) =>
                  val sc = if (o.status == "pending_ceo") StatusCode.Accepted else StatusCode.Created
                  Right((sc, orderJson(o)))
              }
          }
        }
      )

  private val amendOrder =
    base.post
      .in("api" / "v1" / "orders" / path[String]("id") / "amend")
      .in(jsonBody[AmendReq])
      .out(statusCode.and(jsonBody[Json]))
      .serverLogic(principal => {
        case (idStr, req) =>
          val parsed = for {
            orderId <- uuid(idStr)
            lines   <- toLineInputs(req.lines)
            _ <- Either.cond(
              PolicyEngine.authorize(principal, Action.Edit, "order", Target(None, None, None, None)),
              (),
              err(StatusCode.Forbidden, "forbidden", "requires edit:order")
            )
          } yield (orderId, lines)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((orderId, lines)) =>
              orderService.amend(orderId, lines, req.reason, Some(principal.userId), Instant.now()).map {
                case Left(e)  => Left(orderErrorStatus(e))
                case Right(o) => Right((StatusCode.Ok, orderJson(o)))
              }
          }
      })

  private val getOrder =
    base.get
      .in("api" / "v1" / "orders" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          uuid(idStr) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(orderId) =>
              (OrderRepo.scopeRow(orderId), OrderRepo.viewJson(orderId)).tupled.transact(xa).map {
                case (None, _) | (_, None) => Left(err(StatusCode.NotFound, "not_found", "no such order"))
                case (Some((entity, market, channel, owner)), Some(json)) =>
                  if (PolicyEngine.authorize(principal, Action.View, "order", Target(entity, market, channel, owner)))
                    Right(Projection.projectFor(principal, "order", json))
                  else Left(err(StatusCode.Forbidden, "forbidden", "requires view:order"))
              }
          }
      )

  private def orderJson(o: PlacedOrder): Json =
    Json.obj(
      "id"            -> o.id.toString.asJson,
      "orderNo"       -> o.orderNo.asJson,
      "status"        -> o.status.asJson,
      "adlpCategory"  -> o.adlpCategory.asJson,
      "subtotalExVat" -> o.subtotalExVat.toString.asJson,
      "vatTotal"      -> o.vatTotal.toString.asJson,
      "totalIncVat"   -> o.totalIncVat.toString.asJson
    )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(
      List(createParty, billingProfile, creditProfile, placeOrder, amendOrder, getOrder)
    )
}
