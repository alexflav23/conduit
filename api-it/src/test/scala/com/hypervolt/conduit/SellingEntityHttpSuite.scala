package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.TaxRoutes
import com.hypervolt.conduit.orgconfig.SellingEntityRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import weaver.IOSuite

// M13-VAT.1 — the seller-of-record resolution config: which Hypervolt entity books a sale into a jurisdiction.
// Maker-checker (admin proposes → CFO activates), effective-dated (a remap is a new dated row), and the resolver
// returns the right entity for any as_of — so re-pointing DE from HV-UK to HV-GmbH is governed config, not code.
object SellingEntityHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] =
    new TaxRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes.orNotFound

  private def post(xa: HikariTransactor[IO], path: String, token: String, body: Json): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .withEntity(body)
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )
  private def postEmpty(xa: HikariTransactor[IO], path: String, token: String): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )
  private def get(xa: HikariTransactor[IO], path: String, token: String): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.GET, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

  private def userWithRole(xa: HikariTransactor[IO], role: String): IO[String] = {
    val kc = s"$role-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some(role))
      rid <- sql"SELECT id FROM role WHERE name = $role".query[UUID].unique
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, None)
    } yield s"dev:$kc").transact(xa)
  }

  private def entity(xa: HikariTransactor[IO], name: String, ccy: String): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ($name, 'DE', $ccy, 'operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  test("maker-checker + effective-dating: admin proposes the DE→entity map, CFO activates, a remap supersedes it") {
    xa =>
      for {
        admin <- userWithRole(xa, "admin")
        ceo   <- userWithRole(xa, "ceo")
        hvUk  <- entity(xa, s"HV UK ${UUID.randomUUID()}", "GBP")
        hvDe  <- entity(xa, s"HV GmbH ${UUID.randomUUID()}", "EUR")
        // 1) map DE → HV UK from 2020 (home-country serves the region)
        r1 <- post(
          xa,
          "/api/v1/tax/selling-entities",
          admin,
          Json.obj(
            "jurisdiction"   -> "DE".asJson,
            "entity_id"      -> hvUk.toString.asJson,
            "effective_from" -> "2020-01-01".asJson
          )
        ).flatMap(_.as[Json])
        id1 = r1.hcursor.downField("id").as[String].toOption.get
        selfActivate <- postEmpty(xa, s"/api/v1/tax/selling-entities/$id1/activate", admin) // admin lacks approve
        _            <- postEmpty(xa, s"/api/v1/tax/selling-entities/$id1/activate", ceo)
        // 2) remap DE → HV GmbH from 2027 (local entity opens) — supersedes the prior at the new from-date
        r2 <- post(
          xa,
          "/api/v1/tax/selling-entities",
          admin,
          Json.obj(
            "jurisdiction"   -> "DE".asJson,
            "entity_id"      -> hvDe.toString.asJson,
            "effective_from" -> "2027-01-01".asJson
          )
        ).flatMap(_.as[Json])
        id2 = r2.hcursor.downField("id").as[String].toOption.get
        _ <- postEmpty(xa, s"/api/v1/tax/selling-entities/$id2/activate", ceo)
        // resolution honours the as_of
        before <- SellingEntityRepo.active("DE", LocalDate.parse("2026-06-01")).transact(xa)
        after  <- SellingEntityRepo.active("DE", LocalDate.parse("2027-06-01")).transact(xa)
        listed <- get(xa, "/api/v1/tax/selling-entities", ceo).flatMap(_.as[Json])
      } yield expect(selfActivate.status.code == 403) and
        expect(before.contains(hvUk)) and // 2026 → HV UK
        expect(after.contains(hvDe)) and  // 2027 → HV GmbH (the remap)
        expect(listed.asArray.exists(_.exists(_.hcursor.downField("jurisdiction").as[String].toOption.contains("DE"))))
  }
}
