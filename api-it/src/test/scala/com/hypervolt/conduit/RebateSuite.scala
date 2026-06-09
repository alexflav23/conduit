package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.QuoteLine
import com.hypervolt.conduit.pricing.QuoteService
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

// M-Pricing slice 3 (doc 24 §5/§5.7) — retrospective volume rebate (ASC 606 variable consideration), the
// "must be perfect" piece. Proves end-to-end against TigerBeetle: ACCRUE (reproducible earned projection brought
// onto REBATE_ACCRUAL, idempotent), the firm entry-tier invoice price, and APPLY/SETTLE as a SEPARATE maker-checker,
// idempotent draw-down — plus conservation, the ledger tie, and that nothing settles unilaterally.
object RebateSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] =
    (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def seedCharger(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam,$sku,'g3') RETURNING id"
          .query[UUID]
          .unique
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

  private def entityId(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def placedCharger(xa: HikariTransactor[IO], buyer: UUID, entity: UUID, vid: UUID, qty: Int): IO[Unit] =
    (for {
      oid <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
              VALUES (${"O-" + UUID
          .randomUUID()}, 'trade', $entity, $buyer, $buyer, 'placed', 'GBP', 'invoice', 0, 0, 0) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, line_total_inc_vat)
                 VALUES ($oid, $vid, $qty, 600.00, 0, 0)""".update.run
    } yield ()).transact(xa)

  test("retrospective rebate: reproducible accrual, firm entry price, governed idempotent settlement, conservation") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val rebate   = new RebateService[IO](xa, ledger)
      val agr      = new AgreementService[IO](xa)
      val quote    = new QuoteService[IO](xa)
      val proposer = UUID.randomUUID()
      val approver = UUID.randomUUID()
      for {
        (vid, sku) <- seedCharger(xa)
        entity     <- entityId(xa)
        parent     <- party(xa, "Octopus parent")
        agent      <- party(xa, "Octopus agent")
        // retrospective ladder: entry @600 from 0, @560 from 100, @520 from 500. valid_from a fixed past instant so
        // the rolling contract year contains all of seeding, the orders, and the as-of read.
        agreement <- agr.request(
          TierRequest(
            "Octopus retro",
            "GBP",
            List(parent, agent),
            List(
              TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
              TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
              TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
            ),
            Instant.now().minusSeconds(3600),
            None,
            "cumulative_retrospective",
            Json.obj(),
            Some("annual rebate"),
            proposer
          )
        )
        _    <- agr.activate(agreement, approver)
        qNow <- IO.realTimeInstant
        // firm invoice price = entry tier (the better price is a rebate, never a provisional invoice price)
        invoicePrice <-
          quote
            .quote(channel, market, None, "GBP", List(QuoteLine(sku, 1, None)), Some(parent), qNow)
            .map(_.toOption.flatMap(_.lines.headOption).map(_.unitPriceExVat).getOrElse(BigDecimal(-1)))
        // group volume 300 + 300 = 600 → achieved tier @520; earned = 600 × (600−520) = 48000
        _       <- placedCharger(xa, parent, entity, vid, 300)
        _       <- placedCharger(xa, agent, entity, vid, 300)
        asOf    <- IO.realTimeInstant
        earned1 <- rebate.earnedRebate(agreement, asOf)
        earned2 <- rebate.earnedRebate(agreement, asOf) // reproducible
        // accrue (idempotent): REBATE_ACCRUAL gross credits == earned
        _         <- rebate.accrue(agreement, entity, Currency.GBP, asOf)
        _         <- rebate.accrue(agreement, entity, Currency.GBP, asOf)
        afterAccr <- ledger.balance(rebate.rebateAccrual(entity))
        // nothing settled yet (no unilateral application)
        // govern the settlement: maker-checker
        sid         <- rebate.proposeSettlement(agreement, entity, Currency.GBP, "year_end", asOf, proposer)
        selfDeny    <- sid.toOption.traverse(id => rebate.approveSettlement(id, proposer))
        approve     <- sid.toOption.traverse(id => rebate.approveSettlement(id, approver))
        reApprove   <- sid.toOption.traverse(id => rebate.approveSettlement(id, approver)) // idempotent
        afterSettle <- ledger.balance(rebate.rebateAccrual(entity))
        control     <- new com.hypervolt.conduit.close.ControlRunner[IO](xa).run("CTRL-REBATE-ACCRUAL", None)
      } yield expect(invoicePrice == BigDecimal("600.0000")) and                     // firm entry price
        expect(earned1 == BigDecimal("48000.00")) and expect(earned1 == earned2) and // reproducible
        expect(afterAccr.creditsPosted == BigInt(4800000)) and                       // ledger tie: accrual == earned (minor)
        expect(afterAccr.debitsPosted == BigInt(0)) and                              // nothing settled unilaterally
        expect(selfDeny.exists(_.isLeft)) and                                        // segregation of duties
        expect(approve.exists(_.isRight)) and
        expect(afterSettle.creditsPosted == BigInt(4800000)) and // accrual unchanged
        expect(afterSettle.debitsPosted == BigInt(4800000)) and  // drawn down once
        // conservation: settled + outstanding == earned  →  48000 + 0 == 48000
        expect(afterSettle.debitsPosted + (afterSettle.creditsPosted - afterSettle.debitsPosted) == BigInt(4800000)) and
        expect(reApprove.exists(_.isLeft)) and                                        // re-approve rejected (already approved) — credits/debits unchanged
        expect(control.toOption.exists(c => c.result == "pass" && c.violations == 0)) // CTRL-REBATE-ACCRUAL holds
  }
}
