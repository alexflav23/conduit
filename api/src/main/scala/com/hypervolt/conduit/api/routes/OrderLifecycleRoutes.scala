package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.order.OrderLifecycleRepo
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
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The Order Collection Ledger surface (doc 13 §void / order→cash). Replays the immutable event stream for one
// order into {timeline, cycles}: the chronological event log (structural) + the per-invoice collection cycles
// (the back-and-forth). View-gated on `order`; the cycle money is commercial-layer and is stripped for a
// principal lacking it. Postgres-backed projection — no TigerBeetle in the API.
final class OrderLifecycleRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))

  private val lifecycle =
    base.get
      .in("api" / "v1" / "orders" / path[String]("id") / "lifecycle")
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!PolicyEngine.hasPermission(p, Action.View, "order"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:order")))
          else
            Try(UUID.fromString(id)).toEither
              .leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $id")) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(orderId) =>
                (OrderLifecycleRepo.timeline(orderId), OrderLifecycleRepo.cycles(orderId)).tupled.transact(xa).map {
                  case (timeline, cycles) =>
                    // The cycle money is classified under `collection_cycle`, but the layers come from the
                    // principal's `order` grant (the gated object) — so a finance viewer sees the money and a
                    // volume-only viewer has it stripped, without needing a separate collection_cycle grant.
                    val layers = Projection.visibleLayers(p, "order")
                    Right(
                      Json.obj(
                        "order_id" -> orderId.toString.asJson,
                        "timeline" -> Json.fromValues(timeline), // structural event spine
                        "cycles" -> Json.fromValues(
                          cycles.map(c => Projection.project("collection_cycle", FieldLayerMap.seed, layers, c))
                        )
                      )
                    )
                }
            }
      )

  val serverEndpoints = List(lifecycle)

  val routes: HttpRoutes[F] = Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
