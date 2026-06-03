package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.pricing._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class QuoteLineReq(sku: String, qty: Int, unitPriceExVat: Option[String])
object QuoteLineReq { implicit val codec: Codec[QuoteLineReq] = deriveCodec }

final case class QuoteReq(entityId: Option[String], channelId: String, marketId: String, currency: String, lines: List[QuoteLineReq])
object QuoteReq { implicit val codec: Codec[QuoteReq] = deriveCodec }

final case class QuoteLineResp(
    sku: String,
    qty: Int,
    resolvedExVat: String,
    maxDiscountPct: String,
    appliedDiscountPct: String,
    unitPriceExVat: String,
    adlpCategory: String,
    vat: String,
    lineTotalIncVat: String
)
object QuoteLineResp { implicit val codec: Codec[QuoteLineResp] = deriveCodec }

final case class QuoteResp(
    lines: List[QuoteLineResp],
    subtotalExVat: String,
    vatTotal: String,
    totalIncVat: String,
    requiresException: Boolean
)
object QuoteResp { implicit val codec: Codec[QuoteResp] = deriveCodec }

final case class CreateRuleReq(
    surface: String,
    sku: Option[String],
    channelId: Option[String],
    marketId: Option[String],
    entityId: Option[String],
    currency: String,
    taxRegime: Option[String],
    authorisedPrice: String,
    maxDiscountPct: String,
    minQty: Option[Int],
    fromEntityId: Option[String],
    toEntityId: Option[String],
    tpMethod: Option[String],
    tpMarkupPct: Option[String]
)
object CreateRuleReq { implicit val codec: Codec[CreateRuleReq] = deriveCodec }

final class PricingRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base         = Secured.base[F](auth)
  private val quoteService = new QuoteService[F](xa)
  private val anchor       = Target(None, None, None, None)

  private def badRequest(msg: String): (StatusCode, ApiError) = (StatusCode.BadRequest, ApiError("bad_request", msg))
  private def forbidden(msg: String): (StatusCode, ApiError)   = (StatusCode.Forbidden, ApiError("forbidden", msg))

  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => badRequest(s"invalid uuid: $s"))

  private def optUuid(s: Option[String]): Either[(StatusCode, ApiError), Option[UUID]] =
    s.traverse(v => uuid(v))

  private def decimal(s: String): Either[(StatusCode, ApiError), BigDecimal] =
    Try(BigDecimal(s)).toEither.leftMap(_ => badRequest(s"invalid decimal: $s"))

  private val quote =
    base.post
      .in("api" / "v1" / "pricing" / "quote")
      .in(jsonBody[QuoteReq])
      .out(jsonBody[QuoteResp])
      .serverLogic { principal => req =>
        if (!PolicyEngine.authorize(principal, Action.View, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires view:price_rule")))
        else {
          val parsed = for {
            channel <- uuid(req.channelId)
            market  <- uuid(req.marketId)
            entity  <- optUuid(req.entityId)
            lines <- req.lines.traverse(l => l.unitPriceExVat.traverse(decimal).map(p => QuoteLine(l.sku, l.qty, p)))
          } yield (channel, market, entity, lines)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((channel, market, entity, lines)) =>
              quoteService.quote(channel, market, entity, req.currency, lines, Instant.now()).map {
                case Left(err)     => Left((StatusCode.UnprocessableEntity, ApiError("no_price", err)))
                case Right(result) => Right(toResp(result))
              }
          }
        }
      }

  private val listRules =
    base.get
      .in("api" / "v1" / "pricing" / "rules")
      .out(jsonBody[Json])
      .serverLogic { principal => _ =>
        if (!PolicyEngine.authorize(principal, Action.View, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires view:price_rule")))
        else
          PriceRuleRepo.listRulesJson
            .transact(xa)
            .map(rows => Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "price_rule", r)))))
      }

  private val createRule =
    base.post
      .in("api" / "v1" / "pricing" / "rules")
      .in(jsonBody[CreateRuleReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic { principal => req =>
        if (!PolicyEngine.authorize(principal, Action.Edit, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires edit:price_rule")))
        else {
          val parsed = for {
            channel <- optUuid(req.channelId)
            market  <- optUuid(req.marketId)
            entity  <- optUuid(req.entityId)
            fromE   <- optUuid(req.fromEntityId)
            toE     <- optUuid(req.toEntityId)
            price   <- decimal(req.authorisedPrice)
            maxDisc <- decimal(req.maxDiscountPct)
            markup  <- req.tpMarkupPct.traverse(decimal)
          } yield (channel, market, entity, fromE, toE, price, maxDisc, markup)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((channel, market, entity, fromE, toE, price, maxDisc, markup)) =>
              val program = req.sku.traverse(VariantRepo.idBySku).flatMap { maybeVariant =>
                PriceRuleRepo.insert(
                  req.surface, maybeVariant.flatten, channel, market, entity, req.currency, req.taxRegime,
                  price, maxDisc, req.minQty.getOrElse(1), fromE, toE, req.tpMethod, markup, Some(principal.userId)
                )
              }
              program.transact(xa).map(id => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson)))
          }
        }
      }

  private val activateRule =
    base.post
      .in("api" / "v1" / "pricing" / "rules" / path[String]("id") / "activate")
      .out(jsonBody[Json])
      .serverLogic { principal => idStr =>
        if (!PolicyEngine.authorize(principal, Action.Edit, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires edit:price_rule")))
        else
          uuid(idStr) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(ruleId) =>
              val after = Json.obj("status" -> "active".asJson, "approved_by" -> principal.userId.toString.asJson)
              val event = OutboxEvent(
                UUID.randomUUID(), "pricing.rule.changed", 1, "price_rule", ruleId, ruleId.toString,
                None, None, None, after, Instant.now()
              )
              val tx = for {
                updated <- PriceRuleRepo.activate(ruleId, principal.userId)
                _       <- PriceRuleRepo.logChange(ruleId, after, principal.userId)
                _       <- OutboxRepo.append(event)
              } yield updated
              tx.transact(xa).map {
                case 0 => Left((StatusCode.NotFound, ApiError("not_found", "no such price rule")))
                case _ => Right(Json.obj("id" -> ruleId.toString.asJson, "status" -> "active".asJson))
              }
          }
      }

  private def toResp(r: QuoteResult): QuoteResp =
    QuoteResp(
      lines = r.lines.map(l =>
        QuoteLineResp(
          l.sku, l.qty, l.resolvedExVat.toString, l.maxDiscountPct.toString, l.appliedDiscountPct.toString,
          l.unitPriceExVat.toString, l.adlpCategory, l.vat.toString, l.lineTotalIncVat.toString
        )
      ),
      subtotalExVat = r.subtotalExVat.toString,
      vatTotal = r.vatTotal.toString,
      totalIncVat = r.totalIncVat.toString,
      requiresException = r.requiresException
    )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(List(quote, listRules, createRule, activateRule))
}
