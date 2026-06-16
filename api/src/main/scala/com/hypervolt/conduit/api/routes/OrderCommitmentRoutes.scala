package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.order.OrderCommitmentService
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

// Sales backlog read surface (M4). The committed-vs-recognised-vs-open reality for shadow validation before
// cutover. Gated on view:order; layer projection is trivial here (commercial figures only).
final class OrderCommitmentRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base       = Secured.base[F](auth)
  private val commitment = new OrderCommitmentService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def forbid(p: Principal): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, Action.View, "order"))(
      err(StatusCode.Forbidden, "forbidden", "requires view:order")
    )

  private val ofOrder =
    base.get
      .in("api" / "v1" / "orders" / path[String]("id") / "commitment")
      .out(jsonBody[Json])
      .serverLogic(p =>
        orderS =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              Try(UUID.fromString(orderS)).toEither
                .leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $orderS")) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(orderId) =>
                  commitment.forOrder(orderId).map {
                    case None    => Right(Json.obj("error" -> s"no commitment for $orderId".asJson))
                    case Some(j) => Right(j)
                  }
              }
          }
      )

  private val backlog =
    base.get
      .in("api" / "v1" / "finance" / "backlog")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None    => commitment.backlog.map(rows => Right(Json.fromValues(rows)))
          }
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(ofOrder, backlog))
}
