package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.QuoteLine
import com.hypervolt.conduit.pricing.QuoteService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M-Pricing slice 2 (doc 24 §4(b)) — cumulative_prospective. The base band improves GOING FORWARD as the agreement's
// running cumulative qualifying (charger) volume crosses a threshold, aggregated across the WHOLE customer set (the
// "Authorised Agent" group clause), within the rolling contract year. Cumulative volume is a derived projection over
// the order stream (no stored counter). Per-order open_list pricing is unaffected.
object ContractCumulativeSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def seedCharger(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8) // 'hv3' ⇒ product_class charger (back-fill rule)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam,$sku,'g3') RETURNING id"
          .query[UUID]
          .unique
      // open_list per-order standard list @600
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
                 VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 600.00, 1, 'active')""".update.run
    } yield (vid, sku)).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($n,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  // a prior PLACED order with a charger line — builds the agreement's cumulative qualifying volume
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
                 VALUES ($oid, $vid, $qty, 0, 0, 0)""".update.run
    } yield ()).transact(xa)

  private def unitFor(xa: HikariTransactor[IO], sku: String, customer: UUID): IO[BigDecimal] =
    new QuoteService[IO](xa)
      .quote(channel, market, None, "GBP", List(QuoteLine(sku, 1, None)), Some(customer), Instant.now())
      .map(_.toOption.flatMap(_.lines.headOption).map(_.unitPriceExVat).getOrElse(BigDecimal(-1)))

  test(
    "cumulative_prospective: band crosses on group-aggregated charger volume; entry tier from zero; per-order intact"
  ) { xa =>
    val proposer = UUID.randomUUID()
    val approver = UUID.randomUUID()
    val svc      = new AgreementService[IO](xa)
    for {
      (vid, sku) <- seedCharger(xa)
      parent     <- party(xa, "Octopus parent")
      agent      <- party(xa, "Octopus agent")
      stranger   <- party(xa, "Unrelated")
      // a cumulative ladder for the group: tier1 @560 from 0, tier2 @520 from 100, tier3 @480 from 500
      agr <- svc.request(
        TierRequest(
          "Octopus cumulative",
          "GBP",
          List(parent, agent),
          List(
            TierBand(vid, 0, Some(99), BigDecimal("560.00"), "GB_STANDARD"),
            TierBand(vid, 100, Some(499), BigDecimal("520.00"), "GB_STANDARD"),
            TierBand(vid, 500, None, BigDecimal("480.00"), "GB_STANDARD")
          ),
          Instant.now().minusSeconds(60),
          None,
          "cumulative_prospective",
          Json.obj(),
          Some("annual volume commitment"),
          proposer
        )
      )
      _          <- svc.activate(agr, approver)
      entry      <- unitFor(xa, sku, parent)          // zero prior volume → entry tier
      _          <- placedCharger(xa, parent, vid, 60)
      _          <- placedCharger(xa, agent, vid, 50) // group total 110 → crosses tier2 (100)
      afterCross <- unitFor(xa, sku, parent)
      strangerP  <- unitFor(xa, sku, stranger)        // not in the group → open_list per-order
    } yield expect(entry == BigDecimal("560.0000")) and // tier 1 holds from unit zero
      expect(afterCross == BigDecimal("520.0000")) and  // group volume (60+50) unlocked tier 2 going forward
      expect(strangerP == BigDecimal("600.0000"))       // per-order open_list unaffected
  }
}
