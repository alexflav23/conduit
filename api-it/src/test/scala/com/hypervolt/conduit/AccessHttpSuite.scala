package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import org.http4s.implicits._
import weaver.IOSuite

object AccessHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] =
    new AccessRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes.orNotFound

  private def get(xa: HikariTransactor[IO], path: String, token: Option[String]): IO[Response[IO]] = {
    val base = Request[IO](Method.GET, uri = Uri.unsafeFromString(path))
    app(xa).run(token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))))
  }

  private def post(xa: HikariTransactor[IO], path: String, token: Option[String], body: Json): IO[Response[IO]] = {
    val base = Request[IO](Method.POST, uri = Uri.unsafeFromString(path)).withEntity(body)
    app(xa).run(token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))))
  }

  // Seed an admin user (assigned the preset `admin` role) and a plain user (no grants).
  private def seedUsers(xa: HikariTransactor[IO], adminKc: String, plainKc: String): IO[Unit] =
    (for {
      adminId  <- AdminRepo.ensureUser(adminKc, Some("Admin"))
      _        <- AdminRepo.ensureUser(plainKc, Some("Plain"))
      adminRole <- sql"SELECT id FROM role WHERE name = 'admin'".query[UUID].unique
      _        <- AdminRepo.assign(adminId, adminRole, Nil, Nil, Nil, Some("all"))
    } yield ()).transact(xa)

  test("no token -> 401") { xa =>
    get(xa, "/api/v1/access/me", None).map(r => expect(r.status == Status.Unauthorized))
  }

  test("authenticated whoami returns the principal's permissions") { xa =>
    val plainKc = s"plain-${UUID.randomUUID()}"
    val adminKc = s"admin-${UUID.randomUUID()}"
    for {
      _    <- seedUsers(xa, adminKc, plainKc)
      resp <- get(xa, "/api/v1/access/me", Some(s"dev:$adminKc"))
      body <- resp.as[Json]
    } yield expect(resp.status == Status.Ok) and
      expect(body.hcursor.get[List[String]]("permissions").exists(_.contains("create:role")))
  }

  test("non-admin POST /admin/roles -> 403; admin -> 201") { xa =>
    val plainKc = s"plain-${UUID.randomUUID()}"
    val adminKc = s"admin-${UUID.randomUUID()}"
    val newRole = Json.obj("name" -> s"custom-${UUID.randomUUID()}".asJson, "description" -> "x".asJson)
    for {
      _        <- seedUsers(xa, adminKc, plainKc)
      forbidden <- post(xa, "/api/v1/admin/roles", Some(s"dev:$plainKc"), newRole)
      created   <- post(xa, "/api/v1/admin/roles", Some(s"dev:$adminKc"), newRole)
    } yield expect(forbidden.status == Status.Forbidden) and expect(created.status == Status.Created)
  }

  test("admin can list roles (incl. the 9 presets)") { xa =>
    val adminKc = s"admin-${UUID.randomUUID()}"
    val plainKc = s"plain-${UUID.randomUUID()}"
    for {
      _    <- seedUsers(xa, adminKc, plainKc)
      resp <- get(xa, "/api/v1/admin/roles", Some(s"dev:$adminKc"))
      body <- resp.as[Json]
    } yield expect(resp.status == Status.Ok) and
      expect(body.asArray.exists(_.size >= 9))
  }
}
