package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.privacy.CryptoShred
import com.hypervolt.conduit.privacy.DsarService
import com.hypervolt.conduit.privacy.PiiVault
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

// GDPR DSAR surface (doc 19 §B.3.3): request erasure, then a SEPARATE Data-Protection approver decides — the
// crypto-shred runs on approval (server-enforced maker-checker). A PII read shows the `«erased»` tombstone after
// shred. The vault never returns a fabricated value or a misleading null.
final class PrivacyRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F], crypto: CryptoShred) {

  private val base  = Secured.base[F](auth)
  private val vault = new PiiVault[F](xa, crypto)
  private val dsar  = new DsarService[F](xa, vault)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))

  private val requestErasure =
    base.post
      .in("api" / "v1" / "privacy" / "dsar" / "erasure")
      .in(query[String]("subject"))
      .in(query[Option[String]]("reason"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (subjectS, reason) =>
          if (!PolicyEngine.hasPermission(p, Action.Edit, "dsar"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:dsar")))
          else
            uuid(subjectS) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(subject) =>
                dsar
                  .requestErasure(subject, reason.getOrElse("right-to-erasure"), p.userId)
                  .map(id => Right(Json.obj("id" -> id.toString.asJson, "status" -> "pending".asJson)))
            }
      })

  private val approveErasure =
    base.post
      .in("api" / "v1" / "privacy" / "dsar" / path[String]("id") / "approve")
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!PolicyEngine.hasPermission(p, Action.Approve, "dsar"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires approve:dsar")))
          else
            uuid(id) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(rid) =>
                dsar.approveErasure(rid, p.userId).map {
                  case Left(m)  => Left(err(StatusCode.UnprocessableEntity, "unprocessable", m))
                  case Right(_) => Right(Json.obj("id" -> rid.toString.asJson, "status" -> "completed".asJson))
                }
            }
      )

  private val readPii =
    base.get
      .in("api" / "v1" / "privacy" / "pii")
      .in(query[String]("subject"))
      .in(query[String]("field"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (subjectS, field) =>
          if (!PolicyEngine.hasPermission(p, Action.View, "dsar"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:dsar")))
          else
            uuid(subjectS) match {
              case Left(x) => Async[F].pure(Left(x))
              case Right(subject) =>
                vault.get(subject, field).map(v => Right(Json.obj("field" -> field.asJson, "value" -> v.asJson)))
            }
      })

  val serverEndpoints = List(requestErasure, approveErasure, readPii)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
