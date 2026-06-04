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
    ).routes).orNotFound
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
      _   <- AdminRepo.assign(uid, rid, Nil, Nil, Nil, None)
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

  // Place an out-of-band order (32% discount > 10% band) -> 202 pending_ceo + an adlp_exception.
  private def placeOutOfBand(xa: HikariTransactor[IO], agent: String): IO[(UUID, UUID)] =
    for {
      sold <- party(xa)
      bill <- party(xa)
      body = Json.obj(
        "type"          -> "trade".asJson,
        "soldToPartyId" -> sold.toString.asJson,
        "billToPartyId" -> bill.toString.asJson,
        "channelId"     -> channel.toString.asJson,
        "marketId"      -> market.toString.asJson,
        "currency"      -> "GBP".asJson,
        "paymentMethod" -> "stripe".asJson,
        "lines"         -> Json.arr(Json.obj("sku" -> "HV-310".asJson, "qty" -> 1.asJson, "unitPriceExVat" -> "400.00".asJson))
      )
      resp <- post(xa, "/api/v1/orders", s"dev:$agent", body)
      json <- resp.as[Json]
      orderId = UUID.fromString(json.hcursor.get[String]("id").toOption.get)
      excId <- sql"SELECT id FROM adlp_exception WHERE order_id=$orderId LIMIT 1".query[UUID].unique.transact(xa)
    } yield (orderId, excId)

  test(
    "the Deal Desk workflow: agent proposes a narrative; only the CEO can approve; approval is timed, volume-contingent and customer-specific; the order releases"
  ) { xa =>
    for {
      _      <- seedCatalogue(xa)
      agent  <- userWithRole(xa, "retail_sales_agent")
      ceo    <- userWithRole(xa, "ceo")
      admin  <- userWithRole(xa, "admin")
      placed <- placeOutOfBand(xa, agent)
      (orderId, excId) = placed
      // agent assembles the narrative + volume expectation + value notes
      narrative = Json.obj(
        "justification"       -> "Strategic Octopus rollout; competitive displacement".asJson,
        "volumeExpectation"   -> 500.asJson,
        "volumeDenomination"  -> "P50".asJson,
        "strategicImportance" -> "Anchors the energy channel for FY27".asJson,
        "notes"               -> "Customer commits to 500 units across 2 tranches".asJson
      )
      submitted <- post(xa, s"/api/v1/adlp/exceptions/$excId/submit", s"dev:$agent", narrative)
      // an admin (not the CEO) cannot approve a price deviation
      adminDecision <- post(
        xa,
        s"/api/v1/adlp/exceptions/$excId/decision",
        s"dev:$admin",
        Json.obj("decision" -> "approve".asJson, "memo" -> "x".asJson)
      )
      // the CEO cannot approve without a memo
      ceoNoMemo <-
        post(xa, s"/api/v1/adlp/exceptions/$excId/decision", s"dev:$ceo", Json.obj("decision" -> "approve".asJson))
      // the CEO approves: timed (validity window) + volume-contingent (min 400) + memo
      ceoApprove <- post(
        xa,
        s"/api/v1/adlp/exceptions/$excId/decision",
        s"dev:$ceo",
        Json.obj(
          "decision"  -> "approve".asJson,
          "memo"      -> "Approved for Octopus; 500-unit commitment".asJson,
          "validFrom" -> "2026-06-01T00:00:00Z".asJson,
          "validTo"   -> "2026-09-01T00:00:00Z".asJson,
          "volumeMin" -> 400.asJson
        )
      )
      detail      <- get(xa, s"/api/v1/adlp/exceptions/$excId", s"dev:$ceo").flatMap(_.as[Json])
      orderStatus <- sql"""SELECT status FROM "order" WHERE id=$orderId""".query[String].unique.transact(xa)
      approvedEv <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='adlp.exception.approved' AND aggregate_id=$orderId"
          .query[Long]
          .unique
          .transact(xa)
      releasedEv <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='order.placed' AND aggregate_id=$orderId"
          .query[Long]
          .unique
          .transact(xa)
    } yield {
      val c = detail.hcursor
      expect(submitted.status == Status.Ok) and
        expect(adminDecision.status == Status.Forbidden) and       // only the CEO may approve
        expect(ceoNoMemo.status == Status.UnprocessableEntity) and // memo required
        expect(ceoApprove.status == Status.Ok) and
        expect(c.get[String]("status").contains("approved")) and
        expect(c.get[String]("list_price").contains("587.5000")) and // clear price banding
        expect(c.get[String]("max_discount_pct").contains("10.00")) and
        expect(c.get[Int]("volume_expectation").contains(500)) and
        expect(c.get[Int]("approved_volume_min").contains(400)) and          // volume-contingent
        expect(c.downField("approved_valid_to").focus.exists(!_.isNull)) and // timed
        expect(c.downField("party_id").focus.exists(!_.isNull)) and          // customer-specific
        expect(orderStatus == "placed") and expect(approvedEv == 1L) and expect(releasedEv == 1L)
    }
  }

  test("a volume-only viewer sees the exception but not its price banding (layer projection)") { xa =>
    for {
      _      <- seedCatalogue(xa)
      agent  <- userWithRole(xa, "retail_sales_agent")
      placed <- placeOutOfBand(xa, agent)
      (_, excId) = placed
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
