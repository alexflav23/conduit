package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.order.OrderService
import com.hypervolt.conduit.order.PlaceLineInput
import com.hypervolt.conduit.order.PlaceOrderInput
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M13-VAT.6 — order placement stamps the seller-of-record entity from the market's jurisdiction via the
// `selling_entity` config (when not set explicitly). So a sale into a market books against the right Hypervolt
// entity, and re-pointing the market's jurisdiction → entity is configuration.
object OrderEntityResolutionSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()

  // a GB market, a GB operating entity mapped as its seller of record, a priced SKU
  private def setup(xa: HikariTransactor[IO]): IO[(UUID, UUID, UUID, String)] =
    (for {
      market <-
        sql"INSERT INTO market (code, name, jurisdiction, currency) VALUES (${s"M-${UUID.randomUUID()}"},'M','GB','GBP') RETURNING id"
          .query[UUID]
          .unique
      entity <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <- sql"INSERT INTO selling_entity (jurisdiction, entity_id, status) VALUES ('GB', $entity, 'active')".update.run
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      sku = s"K-${UUID.randomUUID()}".take(20)
      _ <- sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, $sku, 'v3')".update.run
      v <- sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].unique
      _ <- sql"""INSERT INTO price_rule (surface, product_variant_id, channel_id, market_id, currency, tax_regime,
                   authorised_price, max_discount_pct, min_qty, status, effective_from)
                 VALUES ('customer', $v, $channel, $market, 'GBP', 'GB_STANDARD', 500.00, 10.00, 1, 'active', DATE '2020-01-01')""".update.run
      party <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
    } yield (market, entity, party, sku)).transact(xa)

  test("an order placed with no entity is stamped with the market's configured seller-of-record entity") { xa =>
    val svc = new OrderService[IO](xa)
    for {
      s <- setup(xa)
      (market, entity, party, sku) = s
      in = PlaceOrderInput(
        orderType = "trade",
        entityId = None, // ← not set; resolved from the market's jurisdiction via selling_entity
        soldToPartyId = party,
        billToPartyId = party,
        channelId = channel,
        marketId = market,
        currency = "GBP",
        paymentMethod = "stripe", // no credit gate — keeps the test focused on entity resolution
        customerPoNumber = None,
        requestedDelivery = None,
        createdBy = None,
        lines = List(PlaceLineInput(sku, 1, None, Nil))
      )
      placed <- svc.place(in, Instant.parse("2026-06-01T00:00:00Z")).flatMap {
        case Right(p) => IO.pure(p)
        case Left(e)  => IO.raiseError(new RuntimeException(s"place failed: $e"))
      }
      stamped <- sql"""SELECT entity_id FROM "order" WHERE id = ${placed.id}""".query[Option[UUID]].unique.transact(xa)
    } yield expect(stamped.contains(entity)) // booked against the configured GB entity
  }
}
