package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.supply.ActivationEvent
import com.hypervolt.conduit.supply.ActivationStreamRepo
import doobie.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import scala.concurrent.duration._

// Live activation stream (SSE) — the real-time half of the /activations Live feed. EventSource can't send an
// Authorization header, so the bearer token rides as ?token= and is validated the same way. On connect we replay a
// recent backlog, then stream anything newer than a moving cursor every few seconds (in prod, new activations push
// straight through from the placement-stream consumer); a heartbeat keeps the connection alive through proxies.
// Raw http4s (not tapir) — tapir's streaming body is heavier than this needs.
final class ActivationStreamRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) extends Http4sDsl[F] {

  private object TokenP extends OptionalQueryParamDecoderMatcher[String]("token")

  private def sse(e: ActivationEvent): ServerSentEvent =
    ServerSentEvent(
      data = Some(
        Json
          .obj(
            "serial"       -> e.serial.asJson,
            "activated_at" -> e.activatedAt.toString.asJson,
            "owner"        -> e.owner.asJson,
            "owner_id"     -> e.ownerId.map(_.toString).asJson
          )
          .noSpaces
      ),
      eventType = Some("activation")
    )

  private val stream: Stream[F, ServerSentEvent] = {
    val backlog = Stream.evalSeq(ActivationStreamRepo.recentBacklog(40).transact(xa)).map(sse)
    val live =
      Stream
        .eval(ActivationStreamRepo.latest.transact(xa).flatMap(l => Ref.of[F, Instant](l.getOrElse(Instant.EPOCH))))
        .flatMap(ref =>
          Stream
            .awakeEvery[F](3.seconds)
            .evalMap(_ =>
              ref.get
                .flatMap(c => ActivationStreamRepo.since(c, 200).transact(xa))
                .flatTap(rows => rows.lastOption.traverse_(r => ref.set(r.activatedAt)))
            )
            .flatMap(rows => Stream.emits(rows.map(sse)))
        )
    val heartbeat = Stream.awakeEvery[F](15.seconds).as(ServerSentEvent(data = Some("ping"), eventType = Some("heartbeat")))
    backlog ++ live.merge(heartbeat)
  }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "api" / "v1" / "activations" / "stream" :? TokenP(tokenOpt) =>
      tokenOpt match {
        case None => Forbidden(Json.obj("error" -> "token required".asJson))
        case Some(tok) =>
          auth.resolve(tok).flatMap {
            case Some(p) if PolicyEngine.hasPermission(p, Action.View, "pipeline_coverage") => Ok(stream)
            case Some(_) => Forbidden(Json.obj("error" -> "requires view:pipeline_coverage".asJson))
            case None    => Forbidden(Json.obj("error" -> "invalid token".asJson))
          }
      }
  }
}
