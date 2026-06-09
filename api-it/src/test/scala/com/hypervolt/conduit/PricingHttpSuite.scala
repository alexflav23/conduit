package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.PricingRoutes
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

object PricingHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] = {
    val auth = new AuthService[IO](xa, devMode = true)
    (new AccessRoutes[IO](xa, auth).routes <+> new PricingRoutes[IO](xa, auth).routes).orNotFound
  }

  private def post(xa: HikariTransactor[IO], path: String, token: String, body: Json): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .withEntity(body)
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

  private def get(xa: HikariTransactor[IO], path: String, token: String): IO[Response[IO]] =
    app(xa).run(
      Request[IO](Method.GET, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

  // Seed catalogue + an active customer price rule + an inter-entity rule, and three role-scoped users.
  private def seed(xa: HikariTransactor[IO]): IO[(String, String, String)] = {
    val retailKc  = s"retail-${UUID.randomUUID()}"
    val financeKc = s"finance-${UUID.randomUUID()}"
    val ceoKc     = s"ceo-${UUID.randomUUID()}"
    val sku       = "HV-310"
    val prog = for {
      famId <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"fam-${UUID.randomUUID()}"}, 'Home 3 Pro') RETURNING id"
          .query[UUID]
          .unique
      _         <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                 VALUES ($famId, $sku, 'v3', true) ON CONFLICT (sku) DO NOTHING""".update.run
      variantId <- sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].unique
      // a GB market + its seller-of-record entity, so the quote preview can run the tax engine for the jurisdiction
      _ <- sql"INSERT INTO market (id, code, name, jurisdiction, currency) VALUES ($market, ${s"M-${UUID.randomUUID()}"
        .take(12)}, 'M', 'GB', 'GBP') ON CONFLICT (id) DO NOTHING".update.run
      ent <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO selling_entity (jurisdiction, entity_id, status) VALUES ('GB', $ent, 'active') ON CONFLICT DO NOTHING".update.run
      _           <- sql"""INSERT INTO price_rule (surface, product_variant_id, channel_id, market_id, currency, tax_regime,
                   authorised_price, max_discount_pct, min_qty, status)
                 VALUES ('customer', $variantId, $channel, $market, 'GBP', 'GB_STANDARD', 587.50, 10.00, 1, 'active')""".update.run
      _           <- sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price,
                   max_discount_pct, tp_method, tp_markup_pct, from_entity_id, to_entity_id, status)
                 VALUES ('inter_entity', $variantId, 'USD', 'TAX_FREE', 400.00, 0, 'cost_plus', 12.5000,
                   ${UUID.randomUUID()}, ${UUID.randomUUID()}, 'active')""".update.run
      rId         <- AdminRepo.ensureUser(retailKc, Some("Retail"))
      fId         <- AdminRepo.ensureUser(financeKc, Some("Finance"))
      cId         <- AdminRepo.ensureUser(ceoKc, Some("CEO"))
      retailRole  <- sql"SELECT id FROM role WHERE name = 'retail_sales_agent'".query[UUID].unique
      financeRole <- sql"SELECT id FROM role WHERE name = 'finance'".query[UUID].unique
      ceoRole     <- sql"SELECT id FROM role WHERE name = 'ceo'".query[UUID].unique
      _           <- AdminRepo.assign(rId, retailRole, Nil, Nil, Nil, None)
      _           <- AdminRepo.assign(fId, financeRole, Nil, Nil, Nil, None)
      _           <- AdminRepo.assign(cId, ceoRole, Nil, Nil, Nil, None)
    } yield (retailKc, financeKc, ceoKc)
    prog.transact(xa)
  }

  private def quoteBody(lines: Json): Json =
    Json.obj(
      "channelId" -> channel.toString.asJson,
      "marketId"  -> market.toString.asJson,
      "currency"  -> "GBP".asJson,
      "lines"     -> lines
    )

  test("a compliant quote returns correct ex/inc-VAT and standard ADLP category") { xa =>
    for {
      kcs <- seed(xa)
      body = quoteBody(Json.arr(Json.obj("sku" -> "HV-310".asJson, "qty" -> 2.asJson)))
      resp <- post(xa, "/api/v1/pricing/quote", s"dev:${kcs._1}", body)
      json <- resp.as[Json]
    } yield {
      val c = json.hcursor
      expect(resp.status == Status.Ok) and
        expect(c.get[String]("vatTotal").contains("235.00")) and
        expect(c.get[String]("totalIncVat").contains("1410.00")) and
        expect(c.get[Boolean]("requiresException").contains(false)) and
        expect(c.downField("lines").downArray.get[String]("adlpCategory").contains("standard"))
    }
  }

  test("the quote preview also runs the tax engine for the market's jurisdiction (supply_kind + engine VAT)") { xa =>
    for {
      kcs <- seed(xa)
      body = quoteBody(Json.arr(Json.obj("sku" -> "HV-310".asJson, "qty" -> 2.asJson)))
      resp <- post(xa, "/api/v1/pricing/quote", s"dev:${kcs._1}", body)
      json <- resp.as[Json]
    } yield {
      val c = json.hcursor
      expect(resp.status == Status.Ok) and
        expect(c.get[String]("supplyKind").contains("domestic")) and // GB market → domestic place of supply
        expect(c.get[String]("engineVatTotal").contains("235.00"))   // engine VAT matches the priced VAT
    }
  }

  test("an out-of-band discount routes the quote to an ADLP exception") { xa =>
    for {
      kcs <- seed(xa)
      body =
        quoteBody(Json.arr(Json.obj("sku" -> "HV-310".asJson, "qty" -> 1.asJson, "unitPriceExVat" -> "400.00".asJson)))
      resp <- post(xa, "/api/v1/pricing/quote", s"dev:${kcs._1}", body)
      json <- resp.as[Json]
    } yield expect(resp.status == Status.Ok) and
      expect(json.hcursor.get[Boolean]("requiresException").contains(true)) and
      expect(json.hcursor.downField("lines").downArray.get[String]("adlpCategory").contains("exception"))
  }

  test("inter-entity rules are layer-walled: retail sees no tp_method, finance does") { xa =>
    for {
      kcs        <- seed(xa)
      retailResp <- get(xa, "/api/v1/pricing/rules", s"dev:${kcs._1}")
      retailJson <- retailResp.as[Json]
      finResp    <- get(xa, "/api/v1/pricing/rules", s"dev:${kcs._2}")
      finJson    <- finResp.as[Json]
    } yield {
      val retailHasTp  = retailJson.asArray.toList.flatten.exists(_.hcursor.keys.exists(_.toList.contains("tp_method")))
      val financeHasTp = finJson.asArray.toList.flatten.exists(_.hcursor.keys.exists(_.toList.contains("tp_method")))
      expect(retailResp.status == Status.Ok) and expect(!retailHasTp) and expect(financeHasTp)
    }
  }

  test("a CEO can create and activate a price rule (governed, audited, immediately effective)") { xa =>
    for {
      kcs <- seed(xa)
      create = Json.obj(
        "surface"         -> "customer".asJson,
        "currency"        -> "GBP".asJson,
        "taxRegime"       -> "GB_STANDARD".asJson,
        "authorisedPrice" -> "699.00".asJson,
        "maxDiscountPct"  -> "5.00".asJson
      )
      cResp <- post(xa, "/api/v1/pricing/rules", s"dev:${kcs._3}", create)
      cJson <- cResp.as[Json]
      ruleId = cJson.hcursor.get[String]("id").toOption.get
      aResp <- post(xa, s"/api/v1/pricing/rules/$ruleId/activate", s"dev:${kcs._3}", Json.obj())
      status <-
        sql"SELECT status FROM price_rule WHERE id = ${UUID.fromString(ruleId)}".query[String].unique.transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='pricing.rule.changed' AND aggregate_id=${UUID.fromString(ruleId)}"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(cResp.status == Status.Created) and expect(aResp.status == Status.Ok) and
      expect(status == "active") and expect(events == 1L)
  }
}
