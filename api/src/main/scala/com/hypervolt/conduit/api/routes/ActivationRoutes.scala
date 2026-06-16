package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.supply.ActivationCapacityRepo
import com.hypervolt.conduit.supply.SerialShelfRepo
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Activation feed (doc 08 §M8): the live sell-through stream off the serial register — newest activations first,
// plus the sell-in-vs-through headline. Same gate as the shelf board (view:pipeline_coverage), since sell-through
// is what feeds the forecast. The `market` query is accepted for desk symmetry; year-1 is UK-only so it does not
// narrow the (single-market) register today.
final class ActivationRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def gate(p: Principal): Boolean = PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage")
  private val denied                      = (StatusCode.Forbidden, ApiError("forbidden", "requires view:pipeline_coverage"))

  private val feed =
    base.get
      .in("api" / "v1" / "activations")
      .in(query[Option[String]]("market"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (_, limit) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else {
            val cap = limit.getOrElse(60).min(500).max(1)
            (
              SerialShelfRepo.activationFeed(cap),
              SerialShelfRepo.sellInVsThrough,
              SerialShelfRepo.activatedCount
            ).tupled
              .transact(xa)
              .map {
                case (rows, sellThrough, total) =>
                  Right(
                    Json.obj(
                      "rows"               -> Json.fromValues(rows),
                      "total"              -> total.asJson,
                      "sell_in_vs_through" -> sellThrough
                    )
                  )
              }
          }
      })

  // Capacity-connected trend: smoothed daily MW (default 28-day trailing mean over 24 months) + cumulative MW online.
  private val capacity =
    base.get
      .in("api" / "v1" / "activations" / "capacity")
      .in(query[Option[Int]]("months"))
      .in(query[Option[Int]]("smoothing"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (months, smoothing) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else ActivationCapacityRepo.capacity(months.getOrElse(24), smoothing.getOrElse(28)).transact(xa).map(Right(_))
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(feed, capacity))
}
