package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.orgconfig.SellingEntityRepo
import com.hypervolt.conduit.orgconfig.SellingEntityService
import com.hypervolt.conduit.tax.NewRate
import com.hypervolt.conduit.tax.RateTableProvider
import com.hypervolt.conduit.tax.TaxAdminRepo
import com.hypervolt.conduit.tax.TaxDeterminationService
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxRateService
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
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class NewRateReq(
    tax_type: String,
    jurisdiction: String,
    region: Option[String],
    postcode_prefix: Option[String],
    level: String,
    tax_category_code: Option[String],
    name: String,
    rate_pct: BigDecimal,
    kind: Option[String],
    effective_from: String
)
object NewRateReq { implicit val codec: Codec[NewRateReq] = deriveCodec }

final case class NewRegistrationReq(
    entity_id: String,
    tax_type: String,
    number: Option[String],
    jurisdiction: String,
    region: Option[String],
    registration_kind: Option[String],
    effective_from: String
)
object NewRegistrationReq { implicit val codec: Codec[NewRegistrationReq] = deriveCodec }

final case class NewNexusReq(
    entity_id: String,
    jurisdiction: String,
    region: String,
    threshold_amount: Option[BigDecimal],
    threshold_txn_count: Option[Int]
)
object NewNexusReq { implicit val codec: Codec[NewNexusReq] = deriveCodec }

final case class NewSellingEntityReq(jurisdiction: String, entity_id: String, effective_from: String)
object NewSellingEntityReq { implicit val codec: Codec[NewSellingEntityReq] = deriveCodec }

// The tax & customs REST surface (doc 16 §10). The determination engine (POST /tax/quote) plus the config admin:
// regimes/rates (maker-checker — tax_specialist proposes, CFO approves), routing, registrations, nexus, and the
// reproducible quote history. Layer-projected: amounts project to commercial, quantities to volume, VAT numbers to pii.
final class TaxRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base       = Secured.base[F](auth)
  private val engine     = new TaxDeterminationService[F](xa, Map(RateTableProvider.name -> RateTableProvider))
  private val rateSvc    = new TaxRateService[F](xa)
  private val sellingSvc = new SellingEntityService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def date(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid date: $s"))
  private def forbid(obj: String) = err(StatusCode.Forbidden, "forbidden", s"requires view:$obj")
  private def project(p: Principal, obj: String, rows: List[Json]): Json =
    Json.fromValues(rows.map(r => Projection.projectFor(p, obj, r)))

  private def listEndpoint[A](seg: String, obj: String, q: EndpointInput[A])(
      run: A => doobie.ConnectionIO[List[Json]]
  ) =
    base.get
      .in("api" / "v1" / "tax" / seg)
      .in(q)
      .out(jsonBody[Json])
      .serverLogic(principal =>
        a =>
          if (!PolicyEngine.hasPermission(principal, Action.View, obj)) Async[F].pure(Left(forbid(obj)))
          else run(a).transact(xa).map(rows => Right(project(principal, obj, rows)))
      )

  private val quote =
    base.post
      .in("api" / "v1" / "tax" / "quote")
      .in(jsonBody[TaxQuoteRequest])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "tax_quote"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:tax_quote")))
          else
            engine.determine(req).map {
              case Right(resp) => Right(resp.asJson)
              case Left(msg)   => Left(err(StatusCode.UnprocessableEntity, "tax_determination_unavailable", msg))
            }
      )

  private val quotesList =
    listEndpoint("quotes", "tax_quote", query[Option[String]]("order_id").and(query[Option[String]]("context"))) {
      case (o, c) => TaxAdminRepo.quotes(o.flatMap(s => Try(UUID.fromString(s)).toOption), c)
    }

  private val quoteOne =
    base.get
      .in("api" / "v1" / "tax" / "quotes" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "tax_quote")) Async[F].pure(Left(forbid("tax_quote")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                TaxAdminRepo
                  .quoteDetail(id)
                  .transact(xa)
                  .map(_.toRight(err(StatusCode.NotFound, "not_found", "quote not found")))
            }
      )

  private val regimesList = listEndpoint("regimes", "tax_regime", emptyInput)(_ => TaxAdminRepo.regimes)
  private val categories  = listEndpoint("categories", "tax_regime", emptyInput)(_ => TaxAdminRepo.categories)
  private val routingList = listEndpoint("routing", "tax_routing", emptyInput)(_ => TaxAdminRepo.routing)

  private val ratesList =
    listEndpoint(
      "rates",
      "tax_rate",
      query[Option[String]]("jurisdiction").and(query[Option[String]]("tax_type")).and(query[Option[String]]("as_of"))
    ) {
      case (j, t, a) => TaxAdminRepo.rates(j, t, a.flatMap(s => Try(LocalDate.parse(s)).toOption))
    }

  private val registrationsList =
    listEndpoint("registrations", "tax_registration", query[Option[String]]("entity_id")) { e =>
      TaxAdminRepo.registrations(e.flatMap(s => Try(UUID.fromString(s)).toOption))
    }

  private val nexusList =
    listEndpoint("nexus", "nexus_profile", query[Option[String]]("entity_id").and(query[Option[String]]("status"))) {
      case (e, s) => TaxAdminRepo.nexus(e.flatMap(x => Try(UUID.fromString(x)).toOption), s)
    }

  private val rateCreate =
    base.post
      .in("api" / "v1" / "tax" / "rates")
      .in(jsonBody[NewRateReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "tax_rate"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:tax_rate")))
          else
            date(req.effective_from) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(from) =>
                rateSvc
                  .propose(
                    NewRate(
                      req.tax_type,
                      req.jurisdiction,
                      req.region,
                      req.postcode_prefix,
                      req.level,
                      req.tax_category_code,
                      req.name,
                      req.rate_pct,
                      req.kind.getOrElse("standard"),
                      from
                    ),
                    principal.userId
                  )
                  .map(id => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson)))
            }
      )

  private val rateActivate =
    base.post
      .in("api" / "v1" / "tax" / "rates" / path[String]("id") / "activate")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.Approve, "tax_rate"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires approve:tax_rate")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                rateSvc.activate(id, principal.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "active".asJson))
                }
            }
      )

  private val registrationCreate =
    base.post
      .in("api" / "v1" / "tax" / "registrations")
      .in(jsonBody[NewRegistrationReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "tax_registration"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:tax_registration")))
          else
            (uuid(req.entity_id), date(req.effective_from)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((e, from)) =>
                TaxAdminRepo
                  .addRegistration(
                    e,
                    req.tax_type,
                    req.number,
                    req.jurisdiction,
                    req.region,
                    req.registration_kind.getOrElse("domestic"),
                    from
                  )
                  .transact(xa)
                  .map(id => Right(Json.obj("id" -> id.toString.asJson)))
            }
      )

  private val nexusCreate =
    base.post
      .in("api" / "v1" / "tax" / "nexus")
      .in(jsonBody[NewNexusReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "nexus_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:nexus_profile")))
          else
            uuid(req.entity_id) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(e) =>
                TaxAdminRepo
                  .addNexus(e, req.jurisdiction, req.region, req.threshold_amount, req.threshold_txn_count)
                  .transact(xa)
                  .map(id => Right(Json.obj("id" -> id.toString.asJson)))
            }
      )

  private val sellingList = listEndpoint("selling-entities", "selling_entity", emptyInput)(_ => SellingEntityRepo.list)

  private val sellingCreate =
    base.post
      .in("api" / "v1" / "tax" / "selling-entities")
      .in(jsonBody[NewSellingEntityReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.Create, "selling_entity"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires create:selling_entity")))
          else
            (uuid(req.entity_id), date(req.effective_from)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((eid, from)) =>
                sellingSvc
                  .propose(req.jurisdiction, eid, from, principal.userId)
                  .map(id => Right(Json.obj("id" -> id.toString.asJson, "status" -> "draft".asJson)))
            }
      )

  private val sellingActivate =
    base.post
      .in("api" / "v1" / "tax" / "selling-entities" / path[String]("id") / "activate")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.Approve, "selling_entity"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires approve:selling_entity")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                sellingSvc.activate(id, principal.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "active".asJson))
                }
            }
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        quote,
        quotesList,
        quoteOne,
        regimesList,
        categories,
        routingList,
        ratesList,
        registrationsList,
        nexusList,
        rateCreate,
        rateActivate,
        registrationCreate,
        nexusCreate,
        sellingList,
        sellingCreate,
        sellingActivate
      )
    )
}
