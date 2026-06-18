package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.ingest.InboxRepo
import doobie.util.transactor.Transactor
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The shadow-mode inbox desk surface (S1): inbound durability made visible. `health` is the per-source landed →
// published → processed → failed board; `quarantine` lists the rows that failed to map with their raw payload +
// error retained (never lost); `requeue` re-queues a quarantined row for another pass once the mapping is fixed.
// Gated to the dual-run owners (view:ingest_record = admin/ceo/finance/auditor); requeue needs an edit role.
final class InboxRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val repo = new InboxRepo[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def canView(p: Principal): Boolean                                   = PolicyEngine.hasPermission(p, Action.View, "ingest_record")
  private def canAct(p: Principal): Boolean                                    = PolicyEngine.hasPermission(p, Action.Edit, "reconciliation")

  private val health =
    base.get
      .in("api" / "v1" / "inbox" / "health")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!canView(p)) Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:ingest_record")))
          else repo.statusCounts.map(rows => Right(Json.obj("rows" -> Json.arr(rows: _*))))
      )

  private val quarantine =
    base.get
      .in("api" / "v1" / "inbox" / "quarantine")
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (limF, offF) =>
          if (!canView(p)) Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:ingest_record")))
          else
            repo
              .quarantine(limF.getOrElse(100).min(500).max(1), offF.getOrElse(0).max(0))
              .map(rows => Right(Json.obj("rows" -> Json.arr(rows: _*))))
      })

  private val requeue =
    base.post
      .in("api" / "v1" / "inbox" / "requeue")
      .in(query[String]("source"))
      .in(query[String]("dataset"))
      .in(query[String]("source_id"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (source, dataset, sourceId) =>
          if (!canAct(p)) Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:reconciliation")))
          else repo.requeue(source, dataset, sourceId).map(n => Right(Json.obj("requeued" -> Json.fromInt(n))))
      })

  val serverEndpoints = List(health, quarantine, requeue)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
