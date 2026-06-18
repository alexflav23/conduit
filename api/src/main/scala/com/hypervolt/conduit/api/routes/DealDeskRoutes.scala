package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.dealdesk.DealDeskService
import com.hypervolt.conduit.dealdesk.Narrative
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
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class SubmitNarrativeReq(
    justification: String,
    volumeExpectation: Int,
    volumeDenomination: String,
    strategicImportance: Option[String],
    notes: Option[String]
)
object SubmitNarrativeReq { implicit val codec: Codec[SubmitNarrativeReq] = deriveCodec }

final case class DecisionReq(
    decision: String,
    memo: Option[String],
    validFrom: Option[String],
    validTo: Option[String],
    volumeMin: Option[Int]
)
object DecisionReq { implicit val codec: Codec[DecisionReq] = deriveCodec }

final class DealDeskRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base    = Secured.base[F](auth)
  private val service = new DealDeskService[F](xa)
  private val anchor  = Target(None, None, None, None)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def instant(s: String): Either[(StatusCode, ApiError), Instant] =
    Try(Instant.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid timestamp: $s"))

  private val list =
    base.get
      .in("api" / "v1" / "adlp" / "exceptions")
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        status =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "adlp_exception"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:adlp_exception")))
          else
            service
              .listJson(principal, status)
              .map(rows => Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "adlp_exception", r)))))
      )

  private val get =
    base.get
      .in("api" / "v1" / "adlp" / "exceptions" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        idStr =>
          Try(UUID.fromString(idStr)).toEither match {
            case Left(_) => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "invalid id")))
            case Right(id) =>
              service.ownerOf(id).flatMap { owner =>
                if (!PolicyEngine.authorize(principal, Action.View, "adlp_exception", Target(None, None, None, owner)))
                  Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:adlp_exception")))
                else
                  service.getJson(id).map {
                    case None       => Left(err(StatusCode.NotFound, "not_found", "no such exception"))
                    case Some(json) => Right(Projection.projectFor(principal, "adlp_exception", json))
                  }
              }
          }
      )

  private val submit =
    base.post
      .in("api" / "v1" / "adlp" / "exceptions" / path[String]("id") / "submit")
      .in(jsonBody[SubmitNarrativeReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (idStr, req) =>
          Try(UUID.fromString(idStr)).toEither match {
            case Left(_) => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "invalid id")))
            case Right(id) =>
              service.ownerOf(id).flatMap { owner =>
                if (!PolicyEngine.authorize(principal, Action.Edit, "adlp_exception", Target(None, None, None, owner)))
                  Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:adlp_exception")))
                else
                  service
                    .submit(
                      id,
                      Narrative(
                        req.justification,
                        req.volumeExpectation,
                        req.volumeDenomination,
                        req.strategicImportance,
                        req.notes,
                        None
                      ),
                      principal.userId
                    )
                    .map {
                      case Left(e)  => Left(err(StatusCode.UnprocessableEntity, "invalid", e))
                      case Right(_) => Right(Json.obj("id" -> id.toString.asJson, "status" -> "pending_ceo".asJson))
                    }
              }
          }
      })

  // CEO-only: the policy layer grants approve:adlp_exception to the `ceo` role alone.
  private val decision =
    base.post
      .in("api" / "v1" / "adlp" / "exceptions" / path[String]("id") / "decision")
      .in(jsonBody[DecisionReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (idStr, req) =>
          if (!PolicyEngine.authorize(principal, Action.Approve, "adlp_exception", anchor))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "only the CEO may approve a price deviation")))
          else {
            val parsed = for {
              id <- Try(UUID.fromString(idStr)).toEither.leftMap(_ =>
                err(StatusCode.BadRequest, "bad_request", "invalid id")
              )
              from <- req.validFrom.traverse(instant)
              to   <- req.validTo.traverse(instant)
            } yield (id, from, to)
            parsed match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((id, from, to)) =>
                service.decide(id, principal.userId, req.decision == "approve", req.memo, from, to, req.volumeMin).map {
                  case Left(e)  => Left(err(StatusCode.UnprocessableEntity, "invalid", e))
                  case Right(_) => Right(Json.obj("id" -> id.toString.asJson, "decision" -> req.decision.asJson))
                }
            }
          }
      })

  val serverEndpoints = List(list, get, submit, decision)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
