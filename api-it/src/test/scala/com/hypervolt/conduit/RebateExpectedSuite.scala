package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.RebateService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M-Pricing §5.3 (doc 24) — recognition net of the EXPECTED rebate (ASC 606 variable consideration, estimated from
// the contract commitment / H6Q floor). Proves: expected > earned in-year while volume trails the commitment; the
// accrual carries the EXPECTED liability; expected CONVERGES to earned as volume lands (no further true-up); and a
// drop in the estimate (an order cancellation) posts a downward RELEASE — never reopening posted entries.
object RebateExpectedSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] =
    (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def seedCharger(xa: HikariTransactor[IO]): IO[UUID] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam,$sku,'g3') RETURNING id"
          .query[UUID]
          .unique
    } yield vid).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($n,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def entityId(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def placedCharger(xa: HikariTransactor[IO], buyer: UUID, vid: UUID, qty: Int): IO[UUID] =
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
    } yield oid).transact(xa)

  test("expected accrual under a commitment; convergence as volume lands; a cancellation releases downward") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val rebate   = new RebateService[IO](xa, ledger)
      val agr      = new AgreementService[IO](xa)
      val proposer = UUID.randomUUID()
      val approver = UUID.randomUUID()
      for {
        vid     <- seedCharger(xa)
        entity  <- entityId(xa)
        octopus <- party(xa, "Octopus")
        // retrospective ladder entry @600, @560 from 100, @520 from 500 — with a COMMITTED annual volume of 500
        agreement <- agr.request(
          TierRequest(
            "Octopus committed",
            "GBP",
            List(octopus),
            List(
              TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
              TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
              TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
            ),
            Instant.now().minusSeconds(3600),
            None,
            "cumulative_retrospective",
            Json.obj("min_commitment_units" -> Json.fromInt(500)),
            Some("committed annual volume"),
            proposer
          )
        )
        _    <- agr.activate(agreement, approver)
        _    <- placedCharger(xa, octopus, vid, 100)
        asOf <- IO.realTimeInstant
        // earned = 100 × (600−560) = 4000 ; expected (commitment 500 → tier @520) = 100 × 80 = 8000
        earned1   <- rebate.earnedRebate(agreement, asOf)
        expected1 <- rebate.expectedRebate(agreement, asOf)
        _         <- rebate.accrueExpected(agreement, entity, Currency.GBP, asOf)
        _         <- rebate.accrueExpected(agreement, entity, Currency.GBP, asOf) // idempotent (same state, no-op)
        bal1      <- ledger.balance(rebate.rebateAccrual(entity))
        // volume lands: +400 → 500 total → achieved tier @520 → earned 500×80 = 40000 = expected (converged)
        _         <- placedCharger(xa, octopus, vid, 400)
        asOf2     <- IO.realTimeInstant
        earned2   <- rebate.earnedRebate(agreement, asOf2)
        expected2 <- rebate.expectedRebate(agreement, asOf2)
        _         <- rebate.accrueExpected(agreement, entity, Currency.GBP, asOf2)
        bal2      <- ledger.balance(rebate.rebateAccrual(entity))
        // the estimate DROPS: cancel the 400-unit order → volume back to 100 → expected back to 8000 → RELEASE
        _         <- sql"""UPDATE "order" SET status='cancelled'
                WHERE id IN (SELECT o.id FROM "order" o JOIN order_line ol ON ol.order_id = o.id
                             WHERE o.sold_to_party_id = $octopus AND ol.qty = 400)""".update.run.transact(xa).void
        asOf3     <- IO.realTimeInstant
        expected3 <- rebate.expectedRebate(agreement, asOf3)
        _         <- rebate.accrueExpected(agreement, entity, Currency.GBP, asOf3)
        bal3      <- ledger.balance(rebate.rebateAccrual(entity))
        released <-
          sql"SELECT count(*) FROM outbox_event WHERE event_type='pricing.rebate.trued_up' AND aggregate_id=$agreement"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(earned1 == BigDecimal("4000.00")) and
        expect(expected1 == BigDecimal("8000.00")) and                                 // net-of-EXPECTED, not earned
        expect(bal1.creditsPosted - bal1.debitsPosted == BigInt(800000)) and           // accrual carries expected
        expect(earned2 == BigDecimal("40000.00")) and expect(expected2 == earned2) and // converged at volume
        expect(bal2.creditsPosted - bal2.debitsPosted == BigInt(4000000)) and
        expect(expected3 == BigDecimal("8000.00")) and                       // estimate dropped
        expect(bal3.creditsPosted - bal3.debitsPosted == BigInt(800000)) and // released back down
        expect(released >= 1L)                                               // a release event was posted
  }
}
