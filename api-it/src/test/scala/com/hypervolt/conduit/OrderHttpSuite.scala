package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.CommerceRoutes
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

object OrderHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] =
    new CommerceRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes.orNotFound

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

  // Catalogue + an active customer price rule for HV-310 at £587.50 (10% ADLP band).
  private def seedCatalogue(xa: HikariTransactor[IO]): IO[Unit] =
    (for {
      famId <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"fam-${UUID.randomUUID()}"}, 'Home 3 Pro') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($famId, 'HV-310', 'v3') ON CONFLICT (sku) DO NOTHING".update.run
      v <- sql"SELECT id FROM product_variant WHERE sku = 'HV-310'".query[UUID].unique
      _ <- sql"""INSERT INTO price_rule (surface, product_variant_id, channel_id, market_id, currency, tax_regime,
                   authorised_price, max_discount_pct, min_qty, status)
                 SELECT 'customer', $v, $channel, $market, 'GBP', 'GB_STANDARD', 587.50, 10.00, 1, 'active'
                 WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE product_variant_id=$v AND channel_id=$channel)""".update.run
    } yield ()).transact(xa)

  private def newParty(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($name, 'wholesaler', true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def retailAgent(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"agent-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Agent"))
      r   <- sql"SELECT id FROM role WHERE name='retail_sales_agent'".query[UUID].unique
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // A user with edit:order (the elevated amendment gate).
  private def amender(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"amender-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Amender"))
      r   <- AdminRepo.createRole(s"amender-${UUID.randomUUID()}", Some("amend"))
      _   <- AdminRepo.addPermission(r, "order", "edit", None, Nil, Nil, "all")
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  private def orderBody(soldTo: UUID, billTo: UUID, payment: String, lines: Json): Json =
    Json.obj(
      "type"          -> "trade".asJson,
      "soldToPartyId" -> soldTo.toString.asJson,
      "billToPartyId" -> billTo.toString.asJson,
      "channelId"     -> channel.toString.asJson,
      "marketId"      -> market.toString.asJson,
      "currency"      -> "GBP".asJson,
      "paymentMethod" -> payment.asJson,
      "lines"         -> lines
    )

  private def line(sku: String, qty: Int, price: Option[String] = None, schedule: Option[Json] = None): Json =
    Json.obj("sku" -> sku.asJson, "qty" -> qty.asJson, "unitPriceExVat" -> price.asJson, "schedule" -> schedule.asJson)

  test("a compliant multi-line order places (201) with correct ADLP pricing and fans out OrderPlaced") { xa =>
    for {
      _    <- seedCatalogue(xa)
      kc   <- retailAgent(xa)
      sold <- newParty(xa, "Branch A")
      bill <- newParty(xa, "Master A")
      body = orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 2), line("HV-310", 1), line("HV-310", 3)))
      resp <- post(xa, "/api/v1/orders", s"dev:$kc", body)
      json <- resp.as[Json]
      orderId = json.hcursor.get[String]("id").toOption.get
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.placed' AND aggregate_id=${UUID.fromString(orderId)}"
          .query[Long]
          .unique
          .transact(xa)
      audit <-
        sql"SELECT count(*) FROM audit_log WHERE entity_type='order' AND entity_id=${UUID.fromString(orderId)}"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(resp.status == Status.Created) and
      expect(json.hcursor.get[String]("status").contains("placed")) and
      expect(json.hcursor.get[String]("adlpCategory").contains("standard")) and
      expect(events == 1L) and expect(audit == 1L)
  }

  test("nobody types a price: a non-tier price is rejected (422); the exact tier price is an accepted no-op echo") {
    xa =>
      for {
        _    <- seedCatalogue(xa)
        kc   <- retailAgent(xa)
        sold <- newParty(xa, "Branch B")
        bill <- newParty(xa, "Master B")
        // a hand-crafted discount cannot be injected — rejected outright, no order row (doc 24 §3)
        rejected <- post(
          xa,
          "/api/v1/orders",
          s"dev:$kc",
          orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 1, Some("400.00"))))
        )
        rejJson <- rejected.as[Json]
        // supplying EXACTLY the authorized tier price is an idempotent re-quote — places normally
        echoed <- post(
          xa,
          "/api/v1/orders",
          s"dev:$kc",
          orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 1, Some("587.50"))))
        )
      } yield expect(rejected.status == Status.UnprocessableEntity) and
        expect(rejJson.hcursor.get[String]("error").contains("non_tier_price")) and
        expect(echoed.status == Status.Created)
  }

  test("a tier-request order holds pending_ceo (202); activation releases it re-quoted at the new tier") { xa =>
    val proposer = UUID.randomUUID()
    val approver = UUID.randomUUID()
    val agrSvc   = new com.hypervolt.conduit.pricing.AgreementService[IO](xa)
    val ordSvc   = new com.hypervolt.conduit.order.OrderService[IO](xa)
    for {
      _    <- seedCatalogue(xa)
      kc   <- retailAgent(xa)
      sold <- newParty(xa, "Branch D")
      bill <- newParty(xa, "Master D")
      vid  <- sql"SELECT id FROM product_variant WHERE sku='HV-310'".query[UUID].unique.transact(xa)
      // the agent's price-tier request: 480.00 for this customer — a DRAFT agreement (doc 24 §6.1)
      draft <- agrSvc.request(
        com.hypervolt.conduit.pricing.TierRequest(
          "Branch D tier",
          "GBP",
          List(sold),
          List(com.hypervolt.conduit.pricing.TierBand(vid, 1, None, BigDecimal("480.00"), "GB_STANDARD")),
          java.time.Instant.now().minusSeconds(60),
          None,
          "per_order",
          Json.obj(),
          Some("volume commitment"),
          proposer
        )
      )
      body = orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 1)))
        .deepMerge(Json.obj("draftAgreementId" -> draft.toString.asJson))
      resp <- post(xa, "/api/v1/orders", s"dev:$kc", body)
      json <- resp.as[Json]
      orderId = UUID.fromString(json.hcursor.get[String]("id").toOption.get)
      placedBefore <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.placed' AND aggregate_id=$orderId"
          .query[Long]
          .unique
          .transact(xa)
      exc <-
        sql"SELECT count(*) FROM adlp_exception WHERE order_id=$orderId AND agreement_id=$draft"
          .query[Long]
          .unique
          .transact(xa)
      // the governed decision IS the activation (doc 24 §6.2) — releases + re-quotes the held order
      _ <- agrSvc.activate(draft, approver)
      _ <- ordSvc.releaseForAgreement(draft, approver, java.time.Instant.now())
      released <-
        sql"""SELECT o.status, ol.unit_price_ex_vat FROM "order" o JOIN order_line ol ON ol.order_id = o.id
              WHERE o.id = $orderId"""
          .query[(String, BigDecimal)]
          .unique
          .transact(xa)
      placedAfter <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.placed' AND aggregate_id=$orderId"
          .query[Long]
          .unique
          .transact(xa)
      excAfter <-
        sql"SELECT status FROM adlp_exception WHERE order_id=$orderId AND agreement_id=$draft"
          .query[String]
          .unique
          .transact(xa)
    } yield expect(resp.status == Status.Accepted) and
      expect(json.hcursor.get[String]("status").contains("pending_ceo")) and
      expect(placedBefore == 0L) and expect(exc == 1L) and         // held, no fan-out, desk artifact exists
      expect(released == (("placed", BigDecimal("480.0000")))) and // released + RE-QUOTED at the new tier
      expect(placedAfter == 1L) and expect(excAfter == "approved")
  }

  test("a credit-limit breach is blocked (422); a party with no credit profile is not billable (422)") { xa =>
    for {
      _    <- seedCatalogue(xa)
      kc   <- retailAgent(xa)
      sold <- newParty(xa, "Branch C")
      bill <- newParty(xa, "Master C")
      _ <- post(
        xa,
        s"/api/v1/parties/$bill/credit-profile",
        s"dev:$kc",
        Json.obj(
          "creditLimit" -> "100.00".asJson,
          "currency"    -> "GBP".asJson,
          "termsDays"   -> 30.asJson,
          "policy"      -> "block".asJson
        )
      )
      blocked <- post(xa, "/api/v1/orders", s"dev:$kc", orderBody(sold, bill, "invoice", Json.arr(line("HV-310", 2))))
      billD   <- newParty(xa, "Master D")
      notBillable <-
        post(xa, "/api/v1/orders", s"dev:$kc", orderBody(sold, billD, "invoice", Json.arr(line("HV-310", 1))))
    } yield expect(blocked.status == Status.UnprocessableEntity) and expect(
      notBillable.status == Status.UnprocessableEntity
    )
  }

  test("a 500-unit line scheduled 2x250 creates two independently-fulfillable tranches") { xa =>
    for {
      _    <- seedCatalogue(xa)
      kc   <- retailAgent(xa)
      sold <- newParty(xa, "Branch E")
      bill <- newParty(xa, "Master E")
      sched = Json.arr(
        Json.obj("seq" -> 1.asJson, "qty" -> 250.asJson, "requestedDate" -> "2026-07-01".asJson),
        Json.obj("seq" -> 2.asJson, "qty" -> 250.asJson, "requestedDate" -> "2026-08-01".asJson)
      )
      body = orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 500, None, Some(sched))))
      resp <- post(xa, "/api/v1/orders", s"dev:$kc", body)
      json <- resp.as[Json]
      orderId = json.hcursor.get[String]("id").toOption.get
      view  <- get(xa, s"/api/v1/orders/$orderId", s"dev:$kc")
      vjson <- view.as[Json]
    } yield {
      val tranches = vjson.hcursor.downField("lines").downArray.downField("tranches").values.toList.flatten
      expect(resp.status == Status.Created) and expect(view.status == Status.Ok) and
        expect(tranches.size == 2) and
        expect(tranches.flatMap(_.hcursor.get[Int]("qty").toOption).forall(_ == 250))
    }
  }

  test("amend pre-dispatch re-prices and records an amendment; after cutoff -> 409; without edit:order -> 403") { xa =>
    for {
      _       <- seedCatalogue(xa)
      kc      <- retailAgent(xa)
      amendKc <- amender(xa)
      sold    <- newParty(xa, "Branch F")
      bill    <- newParty(xa, "Master F")
      created <- post(xa, "/api/v1/orders", s"dev:$kc", orderBody(sold, bill, "stripe", Json.arr(line("HV-310", 2))))
      cjson   <- created.as[Json]
      orderId   = cjson.hcursor.get[String]("id").toOption.get
      amendBody = Json.obj("lines" -> Json.arr(line("HV-310", 5)), "reason" -> "customer increased".asJson)
      // retail (no edit:order) cannot amend
      forbidden <- post(xa, s"/api/v1/orders/$orderId/amend", s"dev:$kc", amendBody)
      // amender can, pre-dispatch
      amended <- post(xa, s"/api/v1/orders/$orderId/amend", s"dev:$amendKc", amendBody)
      amendments <-
        sql"SELECT count(*) FROM order_amendment WHERE order_id=${UUID.fromString(orderId)}"
          .query[Long]
          .unique
          .transact(xa)
      amendedEv <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.amended' AND aggregate_id=${UUID.fromString(orderId)}"
          .query[Long]
          .unique
          .transact(xa)
      // move the cutoff into the past, then a further amend is rejected
      _ <- sql"""UPDATE "order" SET amend_cutoff = now() - interval '1 hour' WHERE id=${UUID.fromString(
        orderId
      )}""".update.run.transact(xa)
      tooLate <- post(xa, s"/api/v1/orders/$orderId/amend", s"dev:$amendKc", amendBody)
    } yield expect(forbidden.status == Status.Forbidden) and
      expect(amended.status == Status.Ok) and
      expect(amendments == 1L) and expect(amendedEv == 1L) and
      expect(tooLate.status == Status.Conflict)
  }
}
