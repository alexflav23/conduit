package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.consumer.RebateAccrualConsumer
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.order.OrderService
import com.hypervolt.conduit.order.PlaceLineInput
import com.hypervolt.conduit.order.PlaceOrderInput
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.RebateAccrualService
import com.hypervolt.conduit.pricing.RebateService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import weaver.IOSuite

// Rebate accrual VERIFICATION (doc 24 §5.2) — the production chain and the properties an auditor would check:
// (1) the REAL order.placed event drives the accrual: place via OrderService → outbox event → the consumer's
//     extractor → RebateAccrualService → REBATE_ACCRUAL carries the expected liability (and the invoice was at the
//     FIRM entry price);
// (2) accessory volume neither advances nor earns the charger tier (§4.5 — separate regimen);
// (3) a unit never counts toward two contract years (§5.7.3 — year-boundary isolation);
// (4) conservation across settlement: settled + outstanding == expected (§5.7.2), and CTRL-REBATE-ACCRUAL holds.
object RebateAccrualVerificationSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] =
    (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def seedVariant(xa: HikariTransactor[IO], cls: String): IO[(UUID, String)] = {
    val sku = (if (cls == "charger") "HV3-" else "ACC-") + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation, product_class) VALUES ($fam,$sku,'g3',$cls) RETURNING id"
          .query[UUID]
          .unique
      // an open-list rule so accessory orders price without the agreement
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
              VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 50.00, 1, 'active')""".update.run
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

  // a charger agreement: entry @600, @560 from 100, @520 from 500; retrospective; committed annual volume 500
  private def activeAgreement(xa: HikariTransactor[IO], vid: UUID, customer: UUID): IO[UUID] = {
    val svc = new AgreementService[IO](xa)
    for {
      id <- svc.request(
        TierRequest(
          "Octopus verified",
          "GBP",
          List(customer),
          List(
            TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
            TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
            TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
          ),
          Instant.now().minusSeconds(3600),
          None,
          "cumulative_retrospective",
          Json.obj("min_commitment_units" -> Json.fromInt(500)),
          Some("verification"),
          UUID.randomUUID()
        )
      )
      _ <- svc.activate(id, UUID.randomUUID())
    } yield id
  }

  private def rawOrder(xa: HikariTransactor[IO], buyer: UUID, entity: UUID, vid: UUID, qty: Int): IO[UUID] =
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
    } yield oid).transact(xa)

  test("the production chain: a REAL order.placed event drives the expected accrual onto the ledger") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val rebates = new RebateService[IO](xa, ledger)
      val accrual = new RebateAccrualService[IO](xa, ledger)
      val orders  = new OrderService[IO](xa)
      for {
        (vid, sku) <- seedVariant(xa, "charger")
        entity     <- entityId(xa)
        octopus    <- party(xa, "Octopus E2E")
        _          <- activeAgreement(xa, vid, octopus)
        // a genuine placement through the production path (stripe → no credit profile needed)
        placed <- orders.place(
          PlaceOrderInput(
            "trade",
            Some(entity),
            octopus,
            octopus,
            channel,
            market,
            "GBP",
            "stripe",
            None,
            None,
            None,
            List(PlaceLineInput(sku, 100, None, Nil))
          ),
          Instant.now()
        )
        order = placed.toOption.get
        // the REAL outbox event row the relay would publish
        eventRow <-
          sql"""SELECT event_type, aggregate_type, aggregate_id, occurred_at FROM outbox_event
                WHERE event_type = 'order.placed' AND aggregate_id = ${order.id}"""
            .query[(String, String, UUID, Instant)]
            .unique
            .transact(xa)
        env = EventEnvelope(
          UUID.randomUUID().toString,
          eventRow._1,
          1,
          eventRow._2,
          eventRow._3.toString,
          eventRow._3.toString,
          None,
          None,
          None,
          "service:order",
          eventRow._4.toEpochMilli,
          Array.emptyByteArray
        )
        // the consumer's pure extractor recognises it; the service trues up the buyer's agreements
        extracted = RebateAccrualConsumer.orderOf(env)
        n   <- accrual.accrueForOrder(extracted.get, Instant.now())
        bal <- ledger.balance(rebates.rebateAccrual(entity))
        // invoiced at the FIRM entry tier; accrued at the EXPECTED (commitment 500 → @520): 100 × 80 = 8000
        linePrice <-
          sql"SELECT unit_price_ex_vat FROM order_line WHERE order_id = ${order.id}"
            .query[BigDecimal]
            .unique
            .transact(xa)
      } yield expect(extracted.contains(order.id)) and
        expect(n == 1) and
        expect(linePrice == BigDecimal("600.0000")) and                // firm entry price on the invoice
        expect(bal.creditsPosted - bal.debitsPosted == BigInt(800000)) // expected liability on the ledger
  }

  test("accessory volume neither advances nor earns the charger tier (separate regimen)") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val rebates = new RebateService[IO](xa, ledger)
      for {
        (vid, _)    <- seedVariant(xa, "charger")
        (accVid, _) <- seedVariant(xa, "accessory")
        entity      <- entityId(xa)
        octopus     <- party(xa, "Octopus ACC")
        agreement   <- activeAgreement(xa, vid, octopus)
        _           <- rawOrder(xa, octopus, entity, vid, 100)    // 100 chargers → tier @560
        _           <- rawOrder(xa, octopus, entity, accVid, 600) // 600 accessories — must NOT advance the charger tier
        asOf        <- IO.realTimeInstant
        earned      <- rebates.earnedRebate(agreement, asOf)
      } yield expect(earned == BigDecimal("4000.00")) // 100 × (600−560); were accessories counted: 100 × 80 = 8000
  }

  test("a unit never counts toward two contract years: only the current year's volume accrues") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val rebates = new RebateService[IO](xa, ledger)
      for {
        (vid, _)  <- seedVariant(xa, "charger")
        entity    <- entityId(xa)
        octopus   <- party(xa, "Octopus YR")
        agreement <- activeAgreement(xa, vid, octopus)
        // anchor the agreement 18 months back — we are now in contract year 1
        _ <-
          sql"UPDATE price_agreement SET valid_from = ${Instant.now().minus(548, ChronoUnit.DAYS)} WHERE id = $agreement".update.run
            .transact(xa)
        yr0 <- rawOrder(xa, octopus, entity, vid, 300)
        // backdate the 300-unit order into contract year 0
        _ <-
          sql"""UPDATE "order" SET created_at = ${Instant
            .now()
            .minus(500, ChronoUnit.DAYS)} WHERE id = $yr0""".update.run
            .transact(xa)
        _      <- rawOrder(xa, octopus, entity, vid, 100) // year-1 volume
        asOf   <- IO.realTimeInstant
        earned <- rebates.earnedRebate(agreement, asOf)
        // year-1 cumVol = 100 (tier @560), units = 100 → 4000. Were year-0's 300 counted: cumVol 400, units 400 → 16000.
      } yield expect(earned == BigDecimal("4000.00"))
  }

  test("conservation across settlement: settled + outstanding == expected; CTRL-REBATE-ACCRUAL holds") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val rebates = new RebateService[IO](xa, ledger)
      val maker   = UUID.randomUUID()
      val checker = UUID.randomUUID()
      for {
        (vid, _)  <- seedVariant(xa, "charger")
        entity    <- entityId(xa)
        octopus   <- party(xa, "Octopus CONS")
        agreement <- activeAgreement(xa, vid, octopus)
        _         <- rawOrder(xa, octopus, entity, vid, 100)
        asOf      <- IO.realTimeInstant
        _         <- rebates.accrueExpected(agreement, entity, Currency.GBP, asOf) // expected = 8000
        // settle the EARNED portion (4000) mid-year — governed maker-checker
        sid <- rebates.proposeSettlement(agreement, entity, Currency.GBP, "milestone", asOf, maker)
        _   <- sid.toOption.traverse(rebates.approveSettlement(_, checker))
        // continued accrual after settlement: outstanding must target expected − settled, never re-accrue the settled part
        _        <- rebates.accrueExpected(agreement, entity, Currency.GBP, asOf)
        expected <- rebates.expectedRebate(agreement, asOf)
        settled <-
          sql"SELECT COALESCE(SUM(amount),0) FROM rebate_settlement WHERE agreement_id=$agreement AND status='approved'"
            .query[BigDecimal]
            .unique
            .transact(xa)
        bal     <- ledger.balance(rebates.rebateAccrual(entity))
        control <- new com.hypervolt.conduit.close.ControlRunner[IO](xa).run("CTRL-REBATE-ACCRUAL", None)
        outstandingMinor = bal.creditsPosted - bal.debitsPosted
      } yield expect(expected == BigDecimal("8000.00")) and
        expect(settled == BigDecimal("4000.00")) and
        // §5.7.2 conservation: settled + outstanding == expected (in minor units)
        expect((settled * 100).toBigInt + outstandingMinor == (expected * 100).toBigInt) and
        expect(control.toOption.exists(c => c.result == "pass" && c.violations == 0))
  }
}
