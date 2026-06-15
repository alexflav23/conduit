package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.crm.CrmReadRepo
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
import sttp.tapir.server.http4s.Http4sServerInterpreter

// CRM reads (spec/ui/22-crm.md): the scope-filtered party worklist and the deal pipeline. Customer master is part
// of the commerce domain, so the gate is the same view the order desk needs (view:order), widened to anyone who
// can see the forecast board (view:pipeline_coverage) — sales, deal desk and CEO. Identity-only fields, so there
// is no layer wall to project here; credit + PII stay behind the per-party credit-terms route.
final class CrmRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def gate(p: Principal): Boolean =
    PolicyEngine.hasPermission(p, Action.View, "order") || PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage")
  private val denied = err(StatusCode.Forbidden, "forbidden", "requires view:order")

  private def optUuid(s: Option[String]): Either[(StatusCode, ApiError), Option[UUID]] =
    s.filter(_.nonEmpty).traverse(v => Try(UUID.fromString(v)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $v")))

  private val parties =
    base.get
      .in("api" / "v1" / "crm" / "parties")
      .in(query[Option[String]]("market"))
      .in(query[Option[String]]("sector"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (market, sector, q, limit) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            optUuid(market) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(mk) =>
                val cap = limit.getOrElse(200).min(500).max(1)
                (CrmReadRepo.listParties(mk, sector.filter(_.nonEmpty), q.filter(_.nonEmpty), cap), CrmReadRepo.sectors).tupled
                  .transact(xa)
                  .map { case (rows, sectors) => Right(Json.obj("rows" -> Json.fromValues(rows), "sectors" -> sectors.asJson)) }
            }
      })

  private val pipeline =
    base.get
      .in("api" / "v1" / "crm" / "pipeline")
      .in(query[Option[String]]("market"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (_, limit) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else CrmReadRepo.pipeline(limit.getOrElse(200).min(500).max(1)).transact(xa).map(Right(_))
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(parties, pipeline))
}
