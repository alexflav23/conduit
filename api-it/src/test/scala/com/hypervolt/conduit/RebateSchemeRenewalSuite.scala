package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.QuoteLine
import com.hypervolt.conduit.pricing.QuoteService
import com.hypervolt.conduit.pricing.RebateSchemeService
import com.hypervolt.conduit.pricing.RenewalAnalytics
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M-Pricing slice 4 (doc 24 §4.4 / §5.8) — generalised rebate schemes, the sector taxonomy, and the renewal-rate
// analytic. Proves: a sector-scoped agreement prices its sector's parties (and only them); a flat rebate scheme
// earns over its own window via the applies-set spend; logo retention is derived from valid_to + renews_from, by sector.
object RebateSchemeRenewalSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def seedCharger(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation, product_class) VALUES ($fam,$sku,'g3','charger') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
                 VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 600.00, 1, 'active')""".update.run
    } yield (vid, sku)).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String, sector: Option[String]): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ($n,'wholesaler',true,$sector) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def placedCharger(xa: HikariTransactor[IO], buyer: UUID, vid: UUID, qty: Int): IO[Unit] =
    (for {
      oid <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
              VALUES (${"O-" + UUID
          .randomUUID()}, 'trade', $buyer, $buyer, 'placed', 'GBP', 'invoice', 0, 0, 0) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, line_total_inc_vat)
                 VALUES ($oid, $vid, $qty, 600.00, 0, 0)""".update.run
    } yield ()).transact(xa)

  private def unitFor(xa: HikariTransactor[IO], sku: String, customer: UUID): IO[BigDecimal] =
    new QuoteService[IO](xa)
      .quote(channel, market, None, "GBP", List(QuoteLine(sku, 1, None)), Some(customer), Instant.now())
      .map(_.toOption.flatMap(_.lines.headOption).map(_.unitPriceExVat).getOrElse(BigDecimal(-1)))

  test("a sector-scoped agreement prices its sector's parties, beats open_list, and never others") { xa =>
    for {
      (vid, sku) <- seedCharger(xa)
      energyCo   <- party(xa, "Energy Co", Some("energy"))
      retailCo   <- party(xa, "Retail Co", Some("retail"))
      agr <-
        sql"""INSERT INTO price_agreement (name, surface, currency, applies_to, base_volume_basis, scope_value, status, valid_from)
              VALUES ('Energy sector', 'customer', 'GBP', 'sector', 'per_order', 'energy', 'active', now() - interval '1 hour') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status, price_agreement_id)
              VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 540.00, 1, 'active', $agr)""".update.run.transact(xa)
      energyPrice <- unitFor(xa, sku, energyCo)
      retailPrice <- unitFor(xa, sku, retailCo)
    } yield expect(energyPrice == BigDecimal("540.0000")) and expect(retailPrice == BigDecimal("600.0000"))
  }

  test("a flat rebate scheme earns its percentage of the applies-set spend over its window") { xa =>
    val proposer = UUID.randomUUID()
    val approver = UUID.randomUUID()
    val svc      = new AgreementService[IO](xa)
    for {
      (vid, _) <- seedCharger(xa)
      octopus  <- party(xa, "Octopus", Some("energy"))
      agr <- svc.request(
        TierRequest(
          "Octopus base",
          "GBP",
          List(octopus),
          List(TierBand(vid, 1, None, BigDecimal("600.00"), "GB_STANDARD")),
          Instant.now().minusSeconds(3600),
          None,
          "per_order",
          Json.obj(),
          Some("base"),
          proposer
        )
      )
      _ <- svc.activate(agr, approver)
      // a 5% flat promo on chargers, over a wide window
      _ <-
        sql"""INSERT INTO rebate_scheme (agreement_id, name, valid_from, valid_to, basis, unit, applies_filter, ladder, status)
              VALUES ($agr, 'Q-promo', now() - interval '1 hour', now() + interval '30 days', 'flat', 'currency',
                      '{"product_class":["charger"]}'::jsonb, '[{"from_threshold":0,"value":5}]'::jsonb, 'active')""".update.run
          .transact(xa)
      _      <- placedCharger(xa, octopus, vid, 10) // spend 10 × 600 = 6000
      earned <- new RebateSchemeService[IO](xa).earnedSchemes(agr, Instant.now())
    } yield expect(earned == BigDecimal("300.00")) // 6000 × 5%
  }

  test("logo retention is derived from valid_to + renews_from, broken down by sector") { xa =>
    val start = Instant.parse("2025-01-01T00:00:00Z")
    val end   = Instant.parse("2025-12-31T23:59:59Z")
    for {
      octopus <- party(xa, "Octopus grp", Some("energy"))
      // agreement A is due in 2025 and IS renewed (B points at it); C is due in 2025 and is NOT renewed
      a <-
        sql"""INSERT INTO price_agreement (name, surface, currency, applies_to, status, valid_from, valid_to)
              VALUES ('A', 'customer', 'GBP', 'customer_set', 'superseded', '2024-06-01T00:00:00Z', '2025-06-01T00:00:00Z') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      c <-
        sql"""INSERT INTO price_agreement (name, surface, currency, applies_to, status, valid_from, valid_to)
              VALUES ('C', 'customer', 'GBP', 'customer_set', 'superseded', '2024-06-01T00:00:00Z', '2025-08-01T00:00:00Z') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"""INSERT INTO price_agreement (name, surface, currency, applies_to, status, valid_from, renews_from)
              VALUES ('B', 'customer', 'GBP', 'customer_set', 'active', '2025-06-01T00:00:00Z', $a)""".update.run
          .transact(xa)
      _ <-
        sql"INSERT INTO price_agreement_customer (agreement_id, party_id) VALUES ($a, $octopus)".update.run.transact(xa)
      _ <-
        sql"INSERT INTO price_agreement_customer (agreement_id, party_id) VALUES ($c, $octopus)".update.run.transact(xa)
      overall <- new RenewalAnalytics[IO](xa).logoRetention(start, end)
      sectors <- new RenewalAnalytics[IO](xa).bySector(start, end)
    } yield expect(overall._1 >= 2L) and expect(overall._2 >= 1L) and // ≥2 due, ≥1 renewed
      expect(sectors.exists { case (s, due, renewed) => s == "energy" && due >= 2L && renewed >= 1L })
  }
}
