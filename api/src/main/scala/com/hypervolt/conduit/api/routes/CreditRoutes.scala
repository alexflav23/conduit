package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.credit.CashWaterfallRepo
import com.hypervolt.conduit.credit.CreditTermsService
import com.hypervolt.conduit.payment.PaymentQueryRepo
import com.hypervolt.conduit.revenue.RevenueQueryRepo
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

final case class CreditTermsReq(payment_terms_days: Int, credit_limit: Option[BigDecimal], currency: Option[String])
object CreditTermsReq { implicit val codec: Codec[CreditTermsReq] = deriveCodec }

// Per-invoice-contact credit terms admin + the cash waterfall (doc 13 / doc 14 §AR). Finance/admin edit terms;
// the waterfall is a finance read. Terms drive the dispatch-time invoice due date and the waterfall buckets.
final class CreditRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base  = Secured.base[F](auth)
  private val terms = new CreditTermsService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def month(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(if (s.length == 7) s + "-01" else s)).toEither
      .leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid period: $s"))

  private val getTerms =
    base.get
      .in("api" / "v1" / "parties" / path[String]("id") / "credit-terms")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "credit_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:credit_profile")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                terms
                  .get(id)
                  .map(t =>
                    Right(
                      Json.obj(
                        "party_id"           -> id.toString.asJson,
                        "payment_terms_days" -> t.paymentTermsDays.asJson,
                        "credit_limit"       -> t.creditLimit.asJson,
                        "currency"           -> t.currency.asJson
                      )
                    )
                  )
            }
      )

  private val setTerms =
    base.put
      .in("api" / "v1" / "parties" / path[String]("id") / "credit-terms")
      .in(jsonBody[CreditTermsReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (idStr, req) =>
          if (!PolicyEngine.hasPermission(principal, Action.Edit, "credit_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:credit_profile")))
          else
            uuid(idStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(id) =>
                terms.set(id, req.payment_terms_days, req.credit_limit, req.currency).map {
                  case Left(m) => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) =>
                    Right(
                      Json.obj("party_id" -> id.toString.asJson, "payment_terms_days" -> req.payment_terms_days.asJson)
                    )
                }
            }
      })

  private val cashWaterfall =
    base.get
      .in("api" / "v1" / "finance" / "cash-waterfall")
      .in(query[Option[String]]("currency"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        ccy =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "credit_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:credit_profile")))
          else CashWaterfallRepo.waterfall(ccy).transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  // ASC-606 P&L for a market/month: matched revenue + COGS recognised on dispatch, off the immutable ledger
  // (the revenue_recognition rows are written atomically with the TigerBeetle post, so this is proof not assertion).
  private val pnl =
    base.get
      .in("api" / "v1" / "finance" / "pnl")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "credit_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:credit_profile")))
          else
            (uuid(marketStr), month(periodStr)).tupled match {
              case Left(e)                 => Async[F].pure(Left(e))
              case Right((market, period)) => RevenueQueryRepo.totals(market, period).transact(xa).map(Right(_))
            }
      })

  // AR aging — outstanding (invoice − payments) bucketed by overdue age; the realised-collections counterpart
  // to the (forward) cash waterfall. Plus DSO. Off the payment data Conduit owns (no TB-in-API).
  private val arAging =
    base.get
      .in("api" / "v1" / "finance" / "ar-aging")
      .in(query[Option[String]]("currency"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        ccy =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "credit_profile"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:credit_profile")))
          else
            (PaymentQueryRepo.arAging(ccy), PaymentQueryRepo.dso(ccy)).tupled.transact(xa).map {
              case (aging, dso) => Right(Json.obj("aging" -> Json.fromValues(aging), "dso" -> dso))
            }
      )

  val serverEndpoints = List(getTerms, setTerms, cashWaterfall, pnl, arAging)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(serverEndpoints)
}
