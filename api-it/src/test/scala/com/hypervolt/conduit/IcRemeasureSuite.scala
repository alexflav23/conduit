package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.intercompany.IcRemeasurementService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 2 (spec doc 28 §5.3): ASC 830 remeasurement, delta method. A gain posts CR FX_GAINLOSS on
// the principal's FUNCTIONAL ledger; a later weaker rate posts only the decrement; a void trues the
// cumulative deltas back to exactly zero (the L2 void law extends through remeasurement); a missing closing
// rate fails closed; same-currency pairs never remeasure; CTRL-IC-REMEASURE re-derives every row and
// detects a corrupted one. Lineage closure holds over the new legs.
object IcRemeasureSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def user(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"$name-${UUID.randomUUID()}"}, $name) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def entities(xa: HikariTransactor[IO], principalCcy: String): IO[(UUID, UUID)] =
    (for {
      sg <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
              VALUES (${s"SG-$principalCcy-${UUID.randomUUID().toString.take(6)}"}, 'SG', $principalCcy, 'procurement') RETURNING id"""
          .query[UUID]
          .unique
      op <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
              VALUES (${s"UK-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
          .query[UUID]
          .unique
    } yield (sg, op)).transact(xa)

  // A recognized flash order: landed 600, transfer 760, uplift 160 — the open IC exposure under test.
  private def recognized(xa: HikariTransactor[IO], client: Client, sg: UUID, op: UUID): IO[(UUID, UUID)] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "rm-maker")
      check  <- user(xa, "rm-checker")
      ids <- (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
              .query[UUID]
              .unique
          v <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                   VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id""".query[UUID].unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          loc <- InventoryRepo.createLocation(Some(op), s"W-${UUID.randomUUID().toString.take(6)}", "W")
          b <- LotBatchRepo.create(
            NewBatch(
              s"B-${UUID.randomUUID()}",
              None,
              v,
              2,
              BigDecimal("300.00"),
              BigDecimal("1.0"),
              "spot",
              None,
              BigDecimal("0"),
              BigDecimal("0"),
              "GBP"
            ),
            LocalDate.parse("2026-01-01")
          )
          _       <- InventoryRepo.receive(Some(op), v, loc, 2)
          s1      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(op), loc)
          s2      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(op), loc)
          _       <- LotBatchRepo.assignSerial(s1, b)
          _       <- LotBatchRepo.assignSerial(s2, b)
          serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1, $s2)".query[String].to[List]
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $op, $billTo, $billTo, $market, 'placed', 'GBP', 'stripe', 1000.00, 200.00, 1200.00) RETURNING id"""
              .query[UUID]
              .unique
          ol <-
            sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
              .query[UUID]
              .unique
        } yield (v, ord, ol, serials)).transact(xa)
      (v, ord, ol, serials) = ids
      lst <-
        ProcurementCatalogue
          .propose(sg, market, "GBP", List(ProcurementCatalogue.PriceListLine(v, BigDecimal("380.00"))), maker)
          .transact(xa)
          .map(_.toOption.get)
      _   <- ProcurementCatalogue.activate(lst, check).transact(xa)
      did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      _   <- dispatch.deliver(did)
      r   <- rev.recognize(did)
      _   <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"recognition failed: $r"))
    } yield (ord, did)
  }

  private def rate(xa: HikariTransactor[IO], kind: String, asOf: LocalDate, r: String): IO[Unit] =
    sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
          VALUES ('GBP', 'USD', ${BigDecimal(r)}, $kind, $asOf, 'test') ON CONFLICT DO NOTHING""".update.run
      .transact(xa)
      .void

  private def fxBalance(client: Client, sg: UUID): IO[(BigInt, BigInt)] =
    TigerBeetleLedger
      .fromClient[IO](client)
      .balance(TbIds.accountId(s"FX_GAINLOSS:$sg"))
      .map(b => (b.debitsPosted, b.creditsPosted))

  private def control(xa: HikariTransactor[IO]): IO[Long] =
    new ControlRunner[IO](xa).run("CTRL-IC-REMEASURE", None).map(_.toOption.get.violations)

  test("gain then partial give-back then a void truing to zero — the delta method, exactly") {
    case (xa, client) =>
      val svc = new IcRemeasurementService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "USD")
        (sg, op) = es
        _  <- rate(xa, "spot", LocalDate.now().minusDays(1), "1.25")
        rd <- recognized(xa, client, sg, op) // uplift 160 booked at 1.25 -> functional 200.00
        (ord, _) = rd
        // close 1: GBP strengthens to 1.30 -> measured 208.00, delta +8.00 (unrealized gain)
        _   <- rate(xa, "closing", LocalDate.now(), "1.30")
        r1  <- svc.run(LocalDate.now())
        fx1 <- fxBalance(client, sg)
        // close 2 (next day): 1.27 -> measured 203.20, delta -4.80 (give-back, only the decrement posts)
        _   <- rate(xa, "closing", LocalDate.now().plusDays(1), "1.27")
        r2  <- svc.run(LocalDate.now().plusDays(1))
        fx2 <- fxBalance(client, sg)
        // the customer cancels: the match reverses; the next close trues cumulative deltas to exactly zero
        inv <-
          sql"SELECT id FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
            .query[UUID]
            .unique
            .transact(xa)
        _ <- new InvoiceReversalService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
          .reverse(inv, "cancellation", "cancelled", "test")
        r3  <- svc.run(LocalDate.now().plusDays(1))
        fx3 <- fxBalance(client, sg)
        deltas <-
          sql"""SELECT COALESCE(SUM(delta), 0) FROM ic_remeasurement
                WHERE procurement_entity_id = $sg AND operating_entity_id = $op"""
            .query[BigDecimal]
            .unique
            .transact(xa)
        ctrl    <- control(xa)
        closure <- new ControlRunner[IO](xa).run("CTRL-LINEAGE-CLOSURE", None).map(_.toOption.get.violations)
      } yield expect(r1.isRight) and expect(r2.isRight) and expect(r3.isRight) and
        expect.same(fx1._2, BigInt(800)) and // +8.00 gain credited
        expect.same(fx2._1, BigInt(480)) and // 4.80 give-back debited
        expect.same(fx3._1, BigInt(800)) and // the void's truing: another 3.20 debit -> 4.80 + 3.20 = 8.00
        expect.same(fx3._2, BigInt(800)) and // FX_GAINLOSS nets to zero — the void law holds through FX
        expect.same(deltas, BigDecimal("0.0000")) and
        expect.same(ctrl, 0L) and
        expect.same(closure, 0L) // the remeasurement legs are claimed — lineage closes over them
  }

  test("a missing closing rate fails the close — remeasurement is fail-closed like everything else") {
    case (xa, client) =>
      val svc = new IcRemeasurementService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "CHF") // no GBP->CHF closing rate exists
        (sg, op) = es
        _ <- rate(xa, "spot", LocalDate.now().minusDays(1), "1.25") // GBP->USD spot, irrelevant to CHF
        _ <-
          sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
                VALUES ('GBP','CHF', 1.10, 'spot', ${LocalDate
            .now()
            .minusDays(1)}, 'test') ON CONFLICT DO NOTHING""".update.run
            .transact(xa)
        _ <- recognized(xa, client, sg, op)
        r <- svc.run(LocalDate.now())
        // cure the pair so later tests' GLOBAL closes can run (a real close would do exactly this: add the rate)
        _ <-
          sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
                VALUES ('GBP','CHF', 1.12, 'closing', ${LocalDate.now()}, 'test') ON CONFLICT DO NOTHING""".update.run
            .transact(xa)
      } yield expect(r.isLeft) and expect(r.swap.toOption.get.contains("fails closed"))
  }

  test("same-currency pairs never remeasure — identity exposure has no FX to absorb") {
    case (xa, client) =>
      val svc = new IcRemeasurementService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "GBP")
        (sg, op) = es
        _ <- recognized(xa, client, sg, op)
        r <- svc.run(LocalDate.now())
        rows <-
          sql"SELECT count(*) FROM ic_remeasurement WHERE procurement_entity_id = $sg".query[Int].unique.transact(xa)
      } yield expect(r.isRight) and expect.same(rows, 0)
  }

  test("DETECTION: a corrupted remeasurement row fails CTRL-IC-REMEASURE with itself as evidence") {
    case (xa, _) =>
      for {
        clean <- control(xa)
        rid <-
          sql"SELECT id FROM ic_remeasurement WHERE tb_transfer_id IS NOT NULL LIMIT 1".query[UUID].unique.transact(xa)
        saved    <- sql"SELECT measured FROM ic_remeasurement WHERE id = $rid".query[BigDecimal].unique.transact(xa)
        _        <- sql"UPDATE ic_remeasurement SET measured = measured + 1 WHERE id = $rid".update.run.transact(xa)
        broken   <- control(xa)
        _        <- sql"UPDATE ic_remeasurement SET measured = $saved WHERE id = $rid".update.run.transact(xa)
        restored <- control(xa)
      } yield expect.same(clean, 0L) and expect(broken > 0L) and expect.same(restored, 0L)
  }
}
