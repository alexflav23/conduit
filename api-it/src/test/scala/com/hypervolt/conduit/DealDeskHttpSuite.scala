package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.CommerceRoutes
import com.hypervolt.conduit.api.routes.DealDeskRoutes
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

object DealDeskHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private implicit val jsonDecoder: EntityDecoder[IO, Json] = jsonOf[IO, Json]
  private val channel                                       = UUID.randomUUID()
  private val market                                        = UUID.randomUUID()

  private def app(xa: HikariTransactor[IO]): HttpApp[IO] = {
    val auth = new AuthService[IO](xa, devMode = true)
    (new AccessRoutes[IO](xa, auth).routes <+> new CommerceRoutes[IO](xa, auth).routes <+> new DealDeskRoutes[IO](
      xa,
      auth
    ).routes <+> new com.hypervolt.conduit.api.routes.PricingRoutes[IO](xa, auth).routes).orNotFound
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

  private def userWithRole(xa: HikariTransactor[IO], role: String): IO[String] = {
    val kc = s"$role-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some(role))
      rid <- sql"SELECT id FROM role WHERE name = $role".query[UUID].unique
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  private def seedCatalogue(xa: HikariTransactor[IO]): IO[Unit] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3 Pro') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam,'HV-310','v3') ON CONFLICT (sku) DO NOTHING".update.run
      v <- sql"SELECT id FROM product_variant WHERE sku='HV-310'".query[UUID].unique
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, channel_id, market_id, currency, tax_regime, authorised_price, max_discount_pct, min_qty, status)
                 SELECT 'customer',$v,$channel,$market,'GBP','GB_STANDARD',587.50,10.00,1,'active'
                 WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE product_variant_id=$v AND channel_id=$channel)""".update.run
    } yield ()).transact(xa)

  private def party(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  // The new trigger (doc 24 §6.3): a price-tier REQUEST (a draft agreement at 400.00 for this customer) + an order
  // placed against it -> 202 pending_ceo + an agreement-linked adlp_exception (the desk worklist artifact).
  private def placeHeld(xa: HikariTransactor[IO], agent: String, proposer: UUID): IO[(UUID, UUID, UUID)] = {
    val agrSvc = new com.hypervolt.conduit.pricing.AgreementService[IO](xa)
    for {
      sold <- party(xa)
      bill <- party(xa)
      vid  <- sql"SELECT id FROM product_variant WHERE sku='HV-310'".query[UUID].unique.transact(xa)
      draft <- agrSvc.request(
        com.hypervolt.conduit.pricing.TierRequest(
          "Octopus strategic tier",
          "GBP",
          List(sold),
          List(com.hypervolt.conduit.pricing.TierBand(vid, 1, None, BigDecimal("400.00"), "GB_STANDARD")),
          java.time.Instant.now().minusSeconds(60),
          None,
          "per_order",
          Json.obj(),
          Some("competitive displacement"),
          proposer
        )
      )
      body = Json.obj(
        "type"             -> "trade".asJson,
        "soldToPartyId"    -> sold.toString.asJson,
        "billToPartyId"    -> bill.toString.asJson,
        "channelId"        -> channel.toString.asJson,
        "marketId"         -> market.toString.asJson,
        "currency"         -> "GBP".asJson,
        "paymentMethod"    -> "stripe".asJson,
        "draftAgreementId" -> draft.toString.asJson,
        "lines"            -> Json.arr(Json.obj("sku" -> "HV-310".asJson, "qty" -> 1.asJson))
      )
      resp <- post(xa, "/api/v1/orders", s"dev:$agent", body)
      json <- resp.as[Json]
      orderId = UUID.fromString(json.hcursor.get[String]("id").toOption.get)
      excId <- sql"SELECT id FROM adlp_exception WHERE order_id=$orderId LIMIT 1".query[UUID].unique.transact(xa)
    } yield (orderId, excId, draft)
  }

  test(
    "the Deal Desk workflow (doc 24 §6): agent proposes the narrative; the decision IS the agreement activation — maker-checker; the order releases re-quoted at the approved tier"
  ) { xa =>
    val proposer = UUID.randomUUID() // the tier request's maker
    for {
      _      <- seedCatalogue(xa)
      agent  <- userWithRole(xa, "retail_sales_agent")
      ceo    <- userWithRole(xa, "ceo")
      admin  <- userWithRole(xa, "admin")
      placed <- placeHeld(xa, agent, proposer)
      (orderId, excId, draft) = placed
      // agent assembles the narrative + volume expectation + value notes on the desk artifact (unchanged flow)
      narrative = Json.obj(
        "justification"       -> "Strategic Octopus rollout; competitive displacement".asJson,
        "volumeExpectation"   -> 500.asJson,
        "volumeDenomination"  -> "P50".asJson,
        "strategicImportance" -> "Anchors the energy channel for FY27".asJson,
        "notes"               -> "Customer commits to 500 units across 2 tranches".asJson
      )
      submitted <- post(xa, s"/api/v1/adlp/exceptions/$excId/submit", s"dev:$agent", narrative)
      // an admin (not the CEO) still cannot touch the decision endpoint (role gate unchanged)
      adminDecision <- post(
        xa,
        s"/api/v1/adlp/exceptions/$excId/decision",
        s"dev:$admin",
        Json.obj("decision" -> "approve".asJson, "memo" -> "x".asJson)
      )
      // an order-scoped price decision no longer exists for a tier request — it redirects to the activation
      ceoOldPath <- post(
        xa,
        s"/api/v1/adlp/exceptions/$excId/decision",
        s"dev:$ceo",
        Json.obj("decision" -> "approve".asJson, "memo" -> "x".asJson)
      )
      // the decision IS the agreement activation (doc 24 §6.2): governed by edit:price_rule + maker-checker.
      // The agent cannot activate (no edit:price_rule)...
      agentActivate <- post(xa, s"/api/v1/pricing/agreements/$draft/activate", s"dev:$agent", Json.obj())
      // ...the CEO can (proposer ≠ approver) — the order releases, RE-QUOTED at the approved tier
      ceoActivate <- post(xa, s"/api/v1/pricing/agreements/$draft/activate", s"dev:$ceo", Json.obj())
      detail      <- get(xa, s"/api/v1/adlp/exceptions/$excId", s"dev:$ceo").flatMap(_.as[Json])
      released <-
        sql"""SELECT o.status, ol.unit_price_ex_vat FROM "order" o JOIN order_line ol ON ol.order_id = o.id
              WHERE o.id = $orderId"""
          .query[(String, BigDecimal)]
          .unique
          .transact(xa)
      releasedEv <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.placed' AND aggregate_id=$orderId"
          .query[Long]
          .unique
          .transact(xa)
      activatedEv <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='pricing.agreement.activated' AND aggregate_id=$draft"
          .query[Long]
          .unique
          .transact(xa)
    } yield {
      val c = detail.hcursor
      expect(submitted.status == Status.Ok) and
        expect(adminDecision.status == Status.Forbidden) and        // only the CEO may decide (role gate)
        expect(ceoOldPath.status == Status.UnprocessableEntity) and // the decision is the activation, not a number
        expect(agentActivate.status == Status.Forbidden) and        // agents cannot activate tiers
        expect(ceoActivate.status == Status.Ok) and
        expect(c.get[String]("status").contains("approved")) and
        expect(c.get[Int]("volume_expectation").contains(500)) and   // narrative intact
        expect(c.downField("party_id").focus.exists(!_.isNull)) and  // customer-specific
        expect(released == (("placed", BigDecimal("400.0000")))) and // released, RE-QUOTED at the tier
        expect(releasedEv == 1L) and expect(activatedEv == 1L)
    }
  }

  test("a volume-only viewer sees the exception but not its price banding (layer projection)") { xa =>
    for {
      _      <- seedCatalogue(xa)
      agent  <- userWithRole(xa, "retail_sales_agent")
      placed <- placeHeld(xa, agent, UUID.randomUUID())
      (_, excId, _) = placed
      _ <- post(
        xa,
        s"/api/v1/adlp/exceptions/$excId/submit",
        s"dev:$agent",
        Json.obj("justification" -> "x".asJson, "volumeExpectation" -> 100.asJson, "volumeDenomination" -> "P50".asJson)
      )
      // fulfilment_agent has volume only and no adlp_exception grant -> cannot view
      ful  <- userWithRole(xa, "fulfilment_agent")
      resp <- get(xa, s"/api/v1/adlp/exceptions/$excId", s"dev:$ful")
    } yield expect(resp.status == Status.Forbidden)
  }
}
