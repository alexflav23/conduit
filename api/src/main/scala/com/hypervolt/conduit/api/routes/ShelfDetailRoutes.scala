package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.supply.AccountDetailRepo
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Per-account shelf drill (doc 20 D11 / spec/ui/06): for one account, the deliveries MRPeasy shipped (each a
// dated tranche with a depletion %), the activation rate over time (day/week/month), and the depletion/runway
// curve. Same gate as the shelf board (view:pipeline_coverage).
final class ShelfDetailRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def gate(p: Principal): Boolean = PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage")
  private val denied                      = (StatusCode.Forbidden, ApiError("forbidden", "requires view:pipeline_coverage"))

  private val detail =
    base.get
      .in("api" / "v1" / "h6q" / "shelf" / path[String]("company") / "detail")
      .out(jsonBody[Json])
      .serverLogic(p =>
        companyStr =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            Try(UUID.fromString(companyStr)).toEither match {
              case Left(_)        => Async[F].pure(Left((StatusCode.BadRequest, ApiError("bad_request", s"invalid id: $companyStr"))))
              case Right(company) => AccountDetailRepo.detail(company, deliveryLimit = 300).transact(xa).map(Right(_))
            }
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(detail))
}
