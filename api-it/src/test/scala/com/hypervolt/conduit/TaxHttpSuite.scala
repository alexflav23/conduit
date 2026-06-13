package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.TaxRoutes
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import weaver.IOSuite

// M13-Tax.3 — the tax HTTP surface (doc 16 §10): the determination engine, the multi-level breakdown, maker-checker
// rate governance (tax_specialist proposes, CFO approves, no self-approval by role separation), and the access gate.
object TaxHttpSuite extends IOSuite {

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
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, Nil, None)
    } yield s"dev:$kc").transact(xa)
  }

  private def entity(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def quoteBody(e: UUID, to: String, region: Option[String], postcode: Option[String], currency: String): Json =
    Json.obj(
      "context"  -> "quote_preview".asJson,
      "entityId" -> e.toString.asJson,
      "shipFrom" -> Json.obj("jurisdiction" -> "GB".asJson, "region" -> Json.Null, "postcode" -> Json.Null),
      "shipTo" -> Json.obj(
        "jurisdiction" -> to.asJson,
        "region"       -> region.map(_.asJson).getOrElse(Json.Null),
        "postcode"     -> postcode.map(_.asJson).getOrElse(Json.Null)
      ),
      "partyTaxStatus" -> "consumer".asJson,
      "buyerTaxId"     -> Json.Null,
      "incoterm"       -> Json.Null,
      "currency"       -> currency.asJson,
      "asOf"           -> "2026-06-01".asJson,
      "lines" -> Json.arr(
        Json.obj(
          "ref"              -> "l1".asJson,
          "productVariantId" -> Json.Null,
          "taxCategoryCode"  -> "goods_standard".asJson,
          "hsCode"           -> Json.Null,
          "qty"              -> 1.asJson,
          "taxableAmount"    -> BigDecimal("100.00").asJson
        )
      )
    )

  test("POST /tax/quote returns a UK VAT determination for a tax_specialist") { xa =>
    for {
      ts <- userWithRole(xa, "tax_specialist")
      e  <- entity(xa)
      r  <- post(xa, "/api/v1/tax/quote", ts, quoteBody(e, "GB", None, None, "GBP"))
      j  <- r.as[Json]
    } yield expect(r.status.code == 200) and
      expect(j.hcursor.downField("supplyKind").as[String].toOption.contains("domestic")) and
      expect(j.hcursor.downField("taxTotal").as[BigDecimal].toOption.contains(BigDecimal("20.00")))
  }

  test("POST /tax/quote returns the US multi-level component breakdown") { xa =>
    for {
      ts <- userWithRole(xa, "tax_specialist")
      e  <- entity(xa)
      r  <- post(xa, "/api/v1/tax/quote", ts, quoteBody(e, "US", Some("CA"), Some("90001"), "USD"))
      j  <- r.as[Json]
      comps =
        j.hcursor.downField("lines").downArray.downField("components").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    } yield expect(r.status.code == 200) and
      expect(j.hcursor.downField("supplyKind").as[String].toOption.contains("us_destination")) and
      expect(comps.length == 3) and
      expect(
        j.hcursor
          .downField("lines")
          .downArray
          .downField("lineTaxTotal")
          .as[BigDecimal]
          .toOption
          .contains(BigDecimal("8.50"))
      )
  }

  test("a role without tax permission is forbidden from the quote engine and the rate table") { xa =>
    for {
      agent <- userWithRole(xa, "retail_sales_agent")
      e     <- entity(xa)
      q     <- post(xa, "/api/v1/tax/quote", agent, quoteBody(e, "GB", None, None, "GBP"))
      rates <- get(xa, "/api/v1/tax/rates?jurisdiction=GB", agent)
    } yield expect(q.status.code == 403) and expect(rates.status.code == 403)
  }

  test("maker-checker: tax_specialist proposes a rate (draft), cannot self-activate; CFO activates it") { xa =>
    val body = Json.obj(
      "tax_type"          -> "VAT".asJson,
      "jurisdiction"      -> "FR".asJson,
      "region"            -> Json.Null,
      "postcode_prefix"   -> Json.Null,
      "level"             -> "national".asJson,
      "tax_category_code" -> "goods_standard".asJson,
      "name"              -> "France VAT".asJson,
      "rate_pct"          -> BigDecimal("20.0").asJson,
      "kind"              -> "standard".asJson,
      "effective_from"    -> "2026-01-01".asJson
    )
    for {
      ts          <- userWithRole(xa, "tax_specialist")
      ceo         <- userWithRole(xa, "ceo")
      created     <- post(xa, "/api/v1/tax/rates", ts, body)
      createdJson <- created.as[Json]
      rid = createdJson.hcursor.downField("id").as[String].toOption.get
      selfActivate <- postEmpty(xa, s"/api/v1/tax/rates/$rid/activate", ts) // tax_specialist lacks approve:tax_rate
      cfoActivate  <- postEmpty(xa, s"/api/v1/tax/rates/$rid/activate", ceo)
      status       <- sql"SELECT status FROM tax_rate WHERE id = ${UUID.fromString(rid)}".query[String].unique.transact(xa)
    } yield expect(created.status.code == 200) and
      expect(createdJson.hcursor.downField("status").as[String].toOption.contains("draft")) and
      expect(selfActivate.status.code == 403) and
      expect(cfoActivate.status.code == 200) and
      expect(status == "active")
  }

  test("a future-dated rate activation closes the prior active row's effective window (effective-dating)") { xa =>
    def rateBody(rate: String, from: String): Json =
      Json.obj(
        "tax_type"          -> "VAT".asJson,
        "jurisdiction"      -> "ES".asJson,
        "level"             -> "national".asJson,
        "tax_category_code" -> "goods_standard".asJson,
        "name"              -> s"Spain VAT $rate".asJson,
        "rate_pct"          -> BigDecimal(rate).asJson,
        "effective_from"    -> from.asJson
      )
    for {
      ts  <- userWithRole(xa, "tax_specialist")
      ceo <- userWithRole(xa, "ceo")
      r1  <- post(xa, "/api/v1/tax/rates", ts, rateBody("21.0", "2020-01-01")).flatMap(_.as[Json])
      _   <- postEmpty(xa, s"/api/v1/tax/rates/${r1.hcursor.downField("id").as[String].toOption.get}/activate", ceo)
      r2  <- post(xa, "/api/v1/tax/rates", ts, rateBody("19.0", "2027-01-01")).flatMap(_.as[Json])
      _   <- postEmpty(xa, s"/api/v1/tax/rates/${r2.hcursor.downField("id").as[String].toOption.get}/activate", ceo)
      rows <-
        sql"SELECT rate_pct, effective_to, status FROM tax_rate WHERE jurisdiction='ES' ORDER BY effective_from"
          .query[(BigDecimal, Option[String], String)]
          .to[List]
          .transact(xa)
    } yield expect(rows.length == 2) and
      expect(
        rows.head == ((BigDecimal("21.0000"), Some("2027-01-01"), "superseded"))
      ) and // prior closed at the new from-date
      expect(rows(1)._3 == "active")
  }

  test("registrations + nexus create and list back for a tax_specialist") { xa =>
    for {
      ts <- userWithRole(xa, "tax_specialist")
      e  <- entity(xa)
      reg <- post(
        xa,
        "/api/v1/tax/registrations",
        ts,
        Json.obj(
          "entity_id"         -> e.toString.asJson,
          "tax_type"          -> "sales_tax".asJson,
          "number"            -> "US-CA-123".asJson,
          "jurisdiction"      -> "US".asJson,
          "region"            -> "CA".asJson,
          "registration_kind" -> "nexus".asJson,
          "effective_from"    -> "2026-01-01".asJson
        )
      )
      nx <- post(
        xa,
        "/api/v1/tax/nexus",
        ts,
        Json.obj(
          "entity_id"           -> e.toString.asJson,
          "jurisdiction"        -> "US".asJson,
          "region"              -> "CA".asJson,
          "threshold_amount"    -> BigDecimal("100000").asJson,
          "threshold_txn_count" -> 200.asJson
        )
      )
      nexusList <- get(xa, s"/api/v1/tax/nexus?entity_id=$e", ts).flatMap(_.as[Json])
    } yield expect(reg.status.code == 200) and expect(nx.status.code == 200) and
      expect(nexusList.asArray.exists(_.nonEmpty)) and
      expect(
        nexusList.hcursor.downArray
          .downField("threshold_amount")
          .as[BigDecimal]
          .toOption
          .contains(BigDecimal("100000.0000"))
      )
  }
}
