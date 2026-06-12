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
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 2b (spec doc 28 §5.4b): hedges as rate-locks on the IC hop — negotiated at fiscal-period
// start with 6–12 month validities. Booking resolves hedge -> spot; a hedge-booked match is LOCKED (never
// remeasures); the drawdown is the live exposure (a return releases its share, a void the remainder, a
// double void nothing); insufficient capacity falls through to spot; CTRL-HEDGE-LOCK proves drawdown ==
// live hedged exposure exactly, and detects a corrupted drawdown.
object IcHedgeLockSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def user(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"$name-${UUID.randomUUID()}"}, $name) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def entities(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      sg <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
              VALUES (${s"SG-USD-${UUID.randomUUID().toString.take(6)}"}, 'SG', 'USD', 'procurement') RETURNING id"""
          .query[UUID]
          .unique
      op <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
              VALUES (${s"UK-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
          .query[UUID]
          .unique
    } yield (sg, op)).transact(xa)

  // The fiscal-year hedge: GBP->USD fixed at 1.2800 for 12 months, capacity in GBP notional.
  private def hedge(xa: HikariTransactor[IO], sg: UUID, notional: String): IO[UUID] =
    sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
          VALUES ($sg, 'GBP', 'USD', 1.28000000, ${BigDecimal(notional)},
                  ${LocalDate.now().minusMonths(2)}, ${LocalDate.now().plusMonths(10)}, 'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def recognized(
      xa: HikariTransactor[IO],
      client: Client,
      sg: UUID,
      op: UUID
  ): IO[(UUID, UUID, UUID, List[String])] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "hl-maker")
      check  <- user(xa, "hl-checker")
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
    } yield (ord, did, ol, serials)
  }

  private def matchStamp(xa: HikariTransactor[IO], did: UUID): IO[(BigDecimal, String)] =
    sql"SELECT booked_rate, rate_source FROM ic_match WHERE dispatch_id = $did"
      .query[(BigDecimal, String)]
      .unique
      .transact(xa)

  private def drawdown(xa: HikariTransactor[IO], hid: UUID): IO[(BigDecimal, BigDecimal)] =
    sql"SELECT notional_used, ic_drawdown FROM fx_hedge WHERE id = $hid"
      .query[(BigDecimal, BigDecimal)]
      .unique
      .transact(xa)

  private def control(xa: HikariTransactor[IO], code: String): IO[Long] =
    new ControlRunner[IO](xa).run(code, None).map(_.toOption.get.violations)

  test(
    "the fiscal-year hedge books the contracted rate, locks the exposure out of remeasurement, and releases on return then void"
  ) {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val returns = new ReturnService[IO](xa, ledger)
      val remeas  = new IcRemeasurementService[IO](xa, ledger)
      for {
        es <- entities(xa)
        (sg, op) = es
        hid <- hedge(xa, sg, "1000.00")
        // a spot also exists and is WORSE — proving the hedge wins the resolution, not a fallback
        _  <- sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
                VALUES ('GBP','USD', 1.35000000, 'spot', ${LocalDate.now().minusDays(1)}, 'test'),
                       ('GBP','USD', 1.35000000, 'closing', ${LocalDate.now()}, 'test')
                ON CONFLICT DO NOTHING""".update.run.transact(xa)
        rd <- recognized(xa, client, sg, op) // uplift 160 -> hedge-booked at 1.28
        (ord, did, ol, serials) = rd
        s  <- matchStamp(xa, did)
        d1 <- drawdown(xa, hid)
        // remeasurement at closing 1.35: the hedged exposure is LOCKED — no rows, no FX P&L
        r1 <- remeas.run(LocalDate.now())
        rows <-
          sql"SELECT count(*) FROM ic_remeasurement WHERE procurement_entity_id = $sg".query[Int].unique.transact(xa)
        // a returned unit releases its share (80 of 160)
        _ <- ledger.createAccounts(
          List(LedgerAccount(returns.cosClearing(op), Ledgers.forCurrency(Currency.GBP), LedgerAccountCode.CosClearing))
        )
        maker <- user(xa, "hl-rma")
        rma <- returns.raise(
          ord,
          "full_unit",
          "serial",
          "changed_mind",
          maker,
          List(RaiseLine(ol, serials.headOption, None, 1))
        )
        lineId <- sql"SELECT id FROM rma_line WHERE rma_id = $rma".query[UUID].unique.transact(xa)
        check2 <- user(xa, "hl-rma-check")
        _      <- returns.assess(rma, List((lineId, "a")), check2)
        _      <- returns.approve(rma, check2, None).flatMap(e => IO.raiseWhen(e.isLeft)(new RuntimeException(s"$e")))
        _      <- returns.receive(rma)
        _      <- returns.disposition(rma, lineId, "restock", None, check2)
        d2     <- drawdown(xa, hid)
        lock2  <- control(xa, "CTRL-HEDGE-LOCK")
        // the void releases the remainder; a second void releases nothing
        inv <-
          sql"SELECT id FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
            .query[UUID]
            .unique
            .transact(xa)
        voids = new InvoiceReversalService[IO](xa, ledger)
        _        <- voids.reverse(inv, "cancellation", "cancelled", "test")
        _        <- voids.reverse(inv, "cancellation", "again", "test")
        d3       <- drawdown(xa, hid)
        lock3    <- control(xa, "CTRL-HEDGE-LOCK")
        ceiling  <- control(xa, "CTRL-HEDGE-DRAWDOWN")
        remeasOk <- control(xa, "CTRL-IC-REMEASURE")
      } yield expect(r1.isRight) and
        expect.same(s._1, BigDecimal("1.28000000")) and
        expect.same(s._2, "hedge:" + hid.toString) and
        expect.same(d1, (BigDecimal("160.0000"), BigDecimal("160.0000"))) and
        expect.same(rows, 0) and // locked: nothing remeasured despite spot 1.35
        expect.same(d2, (BigDecimal("80.0000"), BigDecimal("80.0000"))) and
        expect.same(lock2, 0L) and
        expect.same(d3, (BigDecimal("0.0000"), BigDecimal("0.0000"))) and
        expect.same(lock3, 0L) and expect.same(ceiling, 0L) and expect.same(remeasOk, 0L)
  }

  test("insufficient hedge capacity falls through to spot — never a partial split at the match grain") {
    case (xa, client) =>
      for {
        es <- entities(xa)
        (sg, op) = es
        hid  <- hedge(xa, sg, "100.00") // uplift needs 160 — too small
        rd   <- recognized(xa, client, sg, op)
        s    <- matchStamp(xa, rd._2)
        d    <- drawdown(xa, hid)
        lock <- control(xa, "CTRL-HEDGE-LOCK")
      } yield expect.same(s._1, BigDecimal("1.35000000")) and
        expect(s._2.startsWith("spot:")) and
        expect.same(d, (BigDecimal("0.0000"), BigDecimal("0.0000"))) and
        expect.same(lock, 0L)
  }

  test("DETECTION: a corrupted drawdown fails CTRL-HEDGE-LOCK; restoring it returns the control to zero") {
    case (xa, client) =>
      for {
        es <- entities(xa)
        (sg, op) = es
        hid      <- hedge(xa, sg, "1000.00")
        _        <- recognized(xa, client, sg, op)
        _        <- sql"UPDATE fx_hedge SET ic_drawdown = ic_drawdown + 7 WHERE id = $hid".update.run.transact(xa)
        broken   <- control(xa, "CTRL-HEDGE-LOCK")
        _        <- sql"UPDATE fx_hedge SET ic_drawdown = ic_drawdown - 7 WHERE id = $hid".update.run.transact(xa)
        restored <- control(xa, "CTRL-HEDGE-LOCK")
      } yield expect(broken > 0L) and expect.same(restored, 0L)
  }
}
