package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class WhoAmI(userId: String, permissions: List[String])
object WhoAmI {
  implicit val codec: Codec[WhoAmI] = deriveCodec
  def from(p: Principal): WhoAmI =
    WhoAmI(
      p.userId.toString,
      p.grants.flatMap(_.permissions).map(pm => s"${pm.action.name}:${pm.objectType}").distinct.sorted
    )
}

final case class RoleDto(id: String, name: String, isPreset: Boolean)
object RoleDto {
  implicit val codec: Codec[RoleDto] = deriveCodec
}

final case class CreateRoleReq(name: String, description: Option[String])
object CreateRoleReq { implicit val codec: Codec[CreateRoleReq] = deriveCodec }

final case class AssignReq(
    roleId: String,
    scopeEntities: List[String],
    scopeMarkets: List[String],
    scopeChannels: List[String],
    breadthOverride: Option[String]
)
object AssignReq { implicit val codec: Codec[AssignReq] = deriveCodec }

final case class AssignResp(assignmentId: String)
object AssignResp { implicit val codec: Codec[AssignResp] = deriveCodec }

final class AccessRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = endpoint
    .securityIn(sttp.tapir.auth.bearer[String]())
    .errorOut(statusCode.and(jsonBody[ApiError]))
    .serverSecurityLogic[Principal, F](token =>
      auth
        .resolve(token)
        .map(_.toRight((StatusCode.Unauthorized, ApiError("unauthorized", "missing or invalid token"))))
    )

  private val anchor = Target(None, None, None, None)

  private def requireAdmin(p: Principal): Either[(StatusCode, ApiError), Unit] =
    Either.cond(
      PolicyEngine.authorize(p, Action.Create, "role", anchor),
      (),
      (StatusCode.Forbidden, ApiError("forbidden", "requires create:role (admin)"))
    )

  private def parseUuids(ss: List[String]): Either[(StatusCode, ApiError), List[UUID]] =
    ss.traverse(s => Try(UUID.fromString(s)).toEither)
      .leftMap(_ => (StatusCode.BadRequest, ApiError("bad_request", "invalid UUID in scope")))

  private val whoami =
    base.get
      .in("api" / "v1" / "access" / "me")
      .out(jsonBody[WhoAmI])
      .serverLogic(principal => _ => Async[F].pure(Right(WhoAmI.from(principal))))

  private val listRoles =
    base.get
      .in("api" / "v1" / "admin" / "roles")
      .out(jsonBody[List[RoleDto]])
      .serverLogic(principal =>
        _ =>
          requireAdmin(principal) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(_) =>
              AdminRepo.listRoles
                .transact(xa)
                .map(rs => Right(rs.map { case (id, n, p) => RoleDto(id.toString, n, p) }))
          }
      )

  private val createRole =
    base.post
      .in("api" / "v1" / "admin" / "roles")
      .in(jsonBody[CreateRoleReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[RoleDto]))
      .serverLogic(principal =>
        req =>
          requireAdmin(principal) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(_) =>
              AdminRepo
                .createRole(req.name, req.description)
                .transact(xa)
                .map(id => Right(RoleDto(id.toString, req.name, isPreset = false)))
          }
      )

  private val assign =
    base.post
      .in("api" / "v1" / "admin" / "users" / path[String]("keycloakId") / "assignments")
      .in(jsonBody[AssignReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[AssignResp]))
      .serverLogic(principal => {
        case (keycloakId, req) =>
          val result = for {
            _ <- requireAdmin(principal)
            roleId <- Try(UUID.fromString(req.roleId)).toEither.leftMap(_ =>
              (StatusCode.BadRequest, ApiError("bad_request", "invalid roleId"))
            )
            entities <- parseUuids(req.scopeEntities)
            markets  <- parseUuids(req.scopeMarkets)
            channels <- parseUuids(req.scopeChannels)
          } yield (keycloakId, roleId, entities, markets, channels, req.breadthOverride)
          result match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((kc, roleId, entities, markets, channels, breadth)) =>
              AdminRepo
                .ensureUser(kc, None)
                .flatMap(uid => AdminRepo.assign(uid, roleId, entities, markets, channels, breadth))
                .transact(xa)
                .map(aid => Right(AssignResp(aid.toString)))
          }
      })

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(whoami, listRoles, createRole, assign))
}
