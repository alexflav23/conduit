package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.orgconfig.MarketRepo
import com.hypervolt.conduit.orgconfig.SellingEntityRepo
import com.hypervolt.conduit.pricing._
import com.hypervolt.conduit.tax.RateTableProvider
import com.hypervolt.conduit.tax.TaxDeterminationService
import com.hypervolt.conduit.tax.TaxQuoteLineReq
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxQuoteResponse
import com.hypervolt.conduit.tax.TaxShipPoint
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

final case class QuoteLineReq(sku: String, qty: Int, unitPriceExVat: Option[String])
object QuoteLineReq { implicit val codec: Codec[QuoteLineReq] = deriveCodec }

final case class QuoteReq(
    entityId: Option[String],
    channelId: String,
    marketId: String,
    currency: String,
    customerId: Option[String], // the buyer (party) — resolves customer-scoped tiers (doc 24 §2); None = open_list
    lines: List[QuoteLineReq]
)
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
    lineTotalIncVat: String,
    priceAgreementId: Option[String] = None // the resolved contract (doc 24); None for an open_list line
)
object QuoteLineResp { implicit val codec: Codec[QuoteLineResp] = deriveCodec }

final case class QuoteResp(
    lines: List[QuoteLineResp],
    subtotalExVat: String,
    vatTotal: String,
    totalIncVat: String,
    requiresException: Boolean,
    // The tax engine's preview determination for the market's jurisdiction (doc 16 §6) — the resolved place of
    // supply + engine VAT, so cross-border (reverse-charge/export/import) is visible before the order is placed.
    supplyKind: Option[String] = None,
    engineVatTotal: Option[String] = None
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

// A price-tier request (doc 24 §6) — the renamed ADLP exception. Becomes a draft, customer-scoped price_agreement.
final case class BandReq(sku: String, fromQty: Int, upToQty: Option[Int], price: String, taxRegime: String)
object BandReq { implicit val codec: Codec[BandReq] = deriveCodec }

final case class TierRequestReq(
    name: String,
    currency: String,
    customerIds: List[String],
    bands: List[BandReq],
    validFrom: Option[String],
    validTo: Option[String],
    baseVolumeBasis: Option[String],
    justification: Option[String]
)
object TierRequestReq { implicit val codec: Codec[TierRequestReq] = deriveCodec }

final class PricingRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base             = Secured.base[F](auth)
  private val quoteService     = new QuoteService[F](xa)
  private val agreementService = new AgreementService[F](xa)
  private val tax              = new TaxDeterminationService[F](xa, Map(RateTableProvider.name -> RateTableProvider))
  private val anchor           = Target(None, None, None, None)

  // The preview tax determination for a market (doc 16 §6, context=quote_preview). Resolves the market's
  // jurisdiction + the seller-of-record entity, then runs the engine on the ex-tax subtotal. None when the market
  // has no jurisdiction / no resolvable entity (so no quote is persisted against a non-existent entity).
  private def previewTax(
      market: UUID,
      entity: Option[UUID],
      currency: String,
      subtotal: BigDecimal
  ): F[Option[TaxQuoteResponse]] =
    MarketRepo.jurisdiction(market).transact(xa).flatMap {
      case None => Async[F].pure(None)
      case Some(jur) =>
        val resolved: F[Option[UUID]] = entity match {
          case Some(e) => Async[F].pure(Some(e))
          case None    => SellingEntityRepo.active(jur, LocalDate.now()).transact(xa)
        }
        resolved.flatMap {
          case None => Async[F].pure(None)
          case Some(ent) =>
            tax
              .determine(
                TaxQuoteRequest(
                  "quote_preview",
                  ent,
                  TaxShipPoint(jur, None, None),
                  TaxShipPoint(jur, None, None),
                  "business",
                  None,
                  None,
                  currency,
                  LocalDate.now(),
                  List(TaxQuoteLineReq("q", None, Some("goods_standard"), None, 1, subtotal))
                )
              )
              .map(_.toOption)
        }
    }

  private def badRequest(msg: String): (StatusCode, ApiError) = (StatusCode.BadRequest, ApiError("bad_request", msg))
  private def forbidden(msg: String): (StatusCode, ApiError)  = (StatusCode.Forbidden, ApiError("forbidden", msg))

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
            channel  <- uuid(req.channelId)
            market   <- uuid(req.marketId)
            entity   <- optUuid(req.entityId)
            customer <- optUuid(req.customerId)
            lines    <- req.lines.traverse(l => l.unitPriceExVat.traverse(decimal).map(p => QuoteLine(l.sku, l.qty, p)))
          } yield (channel, market, entity, customer, lines)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((channel, market, entity, customer, lines)) =>
              quoteService.quote(channel, market, entity, req.currency, lines, customer, Instant.now()).flatMap {
                case Left(err) =>
                  Async[F].pure(Left((StatusCode.UnprocessableEntity, ApiError("no_price", err))))
                case Right(result) =>
                  previewTax(market, entity, req.currency, result.subtotalExVat).map(t =>
                    Right(
                      toResp(result)
                        .copy(supplyKind = t.map(_.supplyKind), engineVatTotal = t.map(_.taxTotal.toString))
                    )
                  )
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
                  req.surface,
                  maybeVariant.flatten,
                  channel,
                  market,
                  entity,
                  req.currency,
                  req.taxRegime,
                  price,
                  maxDisc,
                  req.minQty.getOrElse(1),
                  fromE,
                  toE,
                  req.tpMethod,
                  markup,
                  Some(principal.userId)
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
                UUID.randomUUID(),
                "pricing.rule.changed",
                1,
                "price_rule",
                ruleId,
                ruleId.toString,
                None,
                None,
                None,
                after,
                Instant.now()
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

  // doc 24 §6 — file a price-tier request: a draft, customer-scoped agreement + its bands. Requires the propose
  // right (agents don't have it); the proposer is recorded for the maker-checker activation.
  private val requestAgreement =
    base.post
      .in("api" / "v1" / "pricing" / "agreements")
      .in(jsonBody[TierRequestReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic { principal => req =>
        if (!PolicyEngine.authorize(principal, Action.Edit, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires edit:price_rule")))
        else {
          val parsed = for {
            customers <- req.customerIds.traverse(uuid)
            from <-
              req.validFrom.traverse(s => Try(Instant.parse(s)).toEither.leftMap(_ => badRequest("invalid validFrom")))
            to    <- req.validTo.traverse(s => Try(Instant.parse(s)).toEither.leftMap(_ => badRequest("invalid validTo")))
            bands <- req.bands.traverse(b => decimal(b.price).map(p => (b, p)))
          } yield (customers, from, to, bands)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((customers, from, to, bands)) =>
              val program = bands
                .traverse {
                  case (b, price) =>
                    VariantRepo
                      .idBySku(b.sku)
                      .map(_.map(vid => TierBand(vid, b.fromQty, b.upToQty, price, b.taxRegime)))
                }
                .map(_.sequence)
              program.transact(xa).flatMap {
                case None =>
                  Async[F]
                    .pure(Left((StatusCode.UnprocessableEntity, ApiError("unknown_sku", "a band sku is unknown"))))
                case Some(tierBands) =>
                  agreementService
                    .request(
                      TierRequest(
                        req.name,
                        req.currency,
                        customers,
                        tierBands,
                        from.getOrElse(Instant.now()),
                        to,
                        req.baseVolumeBasis.getOrElse("per_order"),
                        Json.obj(),
                        req.justification,
                        principal.userId
                      )
                    )
                    .map(id => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson)))
              }
          }
        }
      }

  // doc 24 §6 — govern the request: maker-checker activation (proposer ≠ approver, enforced in the service). On
  // activation the agreement + its tier rules go active and the named customers resolve the new tier thereafter.
  private val activateAgreement =
    base.post
      .in("api" / "v1" / "pricing" / "agreements" / path[String]("id") / "activate")
      .out(jsonBody[Json])
      .serverLogic { principal => idStr =>
        if (!PolicyEngine.authorize(principal, Action.Edit, "price_rule", anchor))
          Async[F].pure(Left(forbidden("requires edit:price_rule")))
        else
          uuid(idStr) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(id) =>
              agreementService.activate(id, principal.userId).map {
                case Left(msg) => Left((StatusCode.UnprocessableEntity, ApiError("not_activatable", msg)))
                case Right(_)  => Right(Json.obj("id" -> id.toString.asJson, "status" -> "active".asJson))
              }
          }
      }

  private def toResp(r: QuoteResult): QuoteResp =
    QuoteResp(
      lines = r.lines.map(l =>
        QuoteLineResp(
          l.sku,
          l.qty,
          l.resolvedExVat.toString,
          l.maxDiscountPct.toString,
          l.appliedDiscountPct.toString,
          l.unitPriceExVat.toString,
          l.adlpCategory,
          l.vat.toString,
          l.lineTotalIncVat.toString,
          l.priceAgreementId.map(_.toString)
        )
      ),
      subtotalExVat = r.subtotalExVat.toString,
      vatTotal = r.vatTotal.toString,
      totalIncVat = r.totalIncVat.toString,
      requiresException = r.requiresException
    )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(List(quote, listRules, createRule, activateRule, requestAgreement, activateAgreement))
}
