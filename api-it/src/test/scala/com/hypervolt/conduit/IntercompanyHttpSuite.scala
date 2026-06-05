package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.IntercompanyRoutes
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

// M12 — intercompany HTTP surface (doc 13 §8/§9): transfer-price policy governance is maker-checker (finance
// proposes, CFO approves, proposer cannot self-approve) and the access wall holds (a fulfilment_agent sees the
// physical movement but not the transfer price).
object IntercompanyHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] = {
    val auth = new AuthService[IO](xa, devMode = true)
    new IntercompanyRoutes[IO](xa, auth).routes.orNotFound
  }

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

  private def entity(xa: HikariTransactor[IO], juris: String, ccy: String): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES (${s"E-${UUID.randomUUID()}"}, $juris, $ccy, 'operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  test("transfer-price policy is maker-checker: finance proposes, finance cannot self-approve, CFO approves") { xa =>
    for {
      finance <- userWithRole(xa, "finance")
      ceo     <- userWithRole(xa, "ceo")
      from    <- entity(xa, "SG", "USD")
      to      <- entity(xa, "GB", "GBP")
      body = Json.obj(
        "from_entity_id" -> from.toString.asJson,
        "to_entity_id"   -> to.toString.asJson,
        "method"         -> "cost_plus".asJson,
        "markup_pct"     -> BigDecimal(15).asJson
      )
      created     <- post(xa, "/api/v1/intercompany/policies", finance, body)
      createdJson <- created.as[Json]
      pid = createdJson.hcursor.downField("id").as[String].toOption.get
      selfApprove <- postEmpty(xa, s"/api/v1/intercompany/policies/$pid/approve", finance) // proposer ≠ approver
      cfoApprove  <- postEmpty(xa, s"/api/v1/intercompany/policies/$pid/approve", ceo)
      status <-
        sql"SELECT status FROM transfer_price_policy WHERE id = ${UUID.fromString(pid)}"
          .query[String]
          .unique
          .transact(xa)
    } yield expect(created.status.code == 200) and
      expect(
        selfApprove.status.code == 403 || selfApprove.status.code == 422
      ) and // finance can't approve (no grant / maker==checker)
      expect(cfoApprove.status.code == 200) and
      expect(status == "active")
  }

  test("the access wall holds: a fulfilment_agent sees the movement's volume but not the transfer price") { xa =>
    for {
      finance <- userWithRole(xa, "finance")
      agent   <- userWithRole(xa, "fulfilment_agent")
      from    <- entity(xa, "GB", "GBP")
      to      <- entity(xa, "GB", "GBP")
      // a posted movement row directly (the HTTP create-movement path needs the ledger; the read wall is what we assert)
      _ <-
        sql"""INSERT INTO intercompany_link (from_entity_id, to_entity_id, status, transfer_price_total, tp_currency, hop_seq, accounting_period_key)
                   VALUES ($from, $to, 'posted', 1150.00, 'GBP', 1, '2026-09')""".update.run.transact(xa)
      finView <- get(xa, "/api/v1/intercompany/movements", finance).flatMap(_.as[Json])
      agtView <- get(xa, "/api/v1/intercompany/movements", agent).flatMap(_.as[Json])
    } yield {
      val finRow = finView.asArray.flatMap(_.headOption).getOrElse(Json.Null)
      val agtRow = agtView.asArray.flatMap(_.headOption).getOrElse(Json.Null)
      expect(finRow.hcursor.downField("transfer_price_total").focus.exists(!_.isNull)) and    // finance sees the price
        expect(!agtRow.hcursor.downField("transfer_price_total").focus.exists(!_.isNull)) and // agent does NOT
        expect(agtRow.hcursor.downField("status").as[String].toOption.contains("posted"))     // but sees the move
    }
  }
}
