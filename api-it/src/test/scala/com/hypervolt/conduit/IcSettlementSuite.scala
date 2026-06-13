package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.intercompany.IcRemeasurementService
import com.hypervolt.conduit.intercompany.IcSettlementService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 3 (spec doc 28 §5.4): settlement. The full open set of a pair settles maker<>checker:
// transaction-currency cash on both books nets the IC pair to exactly zero; realized FX = settled − booked
// with prior unrealized RECLASSIFIED (the adjunct clears — recognized exactly once, never twice); a
// hedge-booked exposure settles at its contracted rate with ZERO FX and releases its live drawdown; the
// post-settlement remeasure run finds nothing left to measure; CTRL-IC-SETTLE-ZERO re-derives every run and
// detects corruption.
object IcSettlementSuite extends IOSuite {

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

  private def recognized(xa: HikariTransactor[IO], client: Client, sg: UUID, op: UUID): IO[UUID] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "st-maker")
      check  <- user(xa, "st-checker")
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
    } yield did
  }

  private def rate(xa: HikariTransactor[IO], kind: String, asOf: LocalDate, r: String): IO[Unit] =
    sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
          VALUES ('GBP', 'USD', ${BigDecimal(r)}, $kind, $asOf, 'test') ON CONFLICT DO NOTHING""".update.run
      .transact(xa)
      .void

  private def balance(client: Client, key: String): IO[(BigInt, BigInt)] =
    TigerBeetleLedger.fromClient[IO](client).balance(TbIds.accountId(key)).map(b => (b.debitsPosted, b.creditsPosted))

  private def control(xa: HikariTransactor[IO], code: String): IO[Long] =
    new ControlRunner[IO](xa).run(code, None).map(_.toOption.get.violations)

  test("an identity-currency pair settles to zero: cash moves, the IC pair nets, no FX legs, governance enforced") {
    case (xa, client) =>
      val svc = new IcSettlementService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "GBP")
        (sg, op) = es
        _        <- recognized(xa, client, sg, op) // uplift 160
        proposer <- user(xa, "settle-maker")
        approver <- user(xa, "settle-checker")
        sid      <- svc.propose(sg, op, "GBP", LocalDate.now(), proposer).map(_.toOption.get)
        selfNo   <- svc.approve(sid, proposer)
        r        <- svc.approve(sid, approver)
        again    <- svc.approve(sid, approver)
        icAp     <- balance(client, s"IC_AP:$op:$sg")
        icAr     <- balance(client, s"IC_AR:$sg:$op")
        opBank   <- balance(client, s"BANK:$op")
        prBank   <- balance(client, s"BANK:$sg")
        claims   <- sql"""SELECT fx_final_tb_transfer_id IS NULL AND fx_reclass_tb_transfer_id IS NULL
                FROM ic_settlement WHERE id = $sid""".query[Boolean].unique.transact(xa)
        stamped  <- sql"SELECT count(*) FROM ic_match WHERE settlement_id = $sid".query[Int].unique.transact(xa)
        ctrl     <- control(xa, "CTRL-IC-SETTLE-ZERO")
        closure  <- control(xa, "CTRL-LINEAGE-CLOSURE")
      } yield expect(selfNo.isLeft) and expect(r.isRight) and expect(again.isLeft) and
        expect.same(r.toOption.get.netTxn, BigDecimal("160.0000")) and
        expect.same(r.toOption.get.realizedFx, BigDecimal("0.0000")) and
        expect.same(icAp._1, icAp._2) and // the pair nets to exactly zero
        expect.same(icAr._1, icAr._2) and
        expect.same(opBank._2, BigInt(16000)) and // the operating entity paid
        expect.same(prBank._1, BigInt(16000)) and // the principal received
        expect(claims) and                        // no FX legs on an identity settlement — claims iff posted
        expect.same(stamped, 1) and
        expect.same(ctrl, 0L) and expect.same(closure, 0L)
  }

  test(
    "cross-currency: realized = settled − booked, prior unrealized reclassifies once, the adjunct clears, the next close finds nothing"
  ) {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new IcSettlementService[IO](xa, ledger)
      val remeas = new IcRemeasurementService[IO](xa, ledger)
      for {
        es <- entities(xa, "USD")
        (sg, op) = es
        _        <- rate(xa, "spot", LocalDate.now().minusDays(1), "1.25") // booking: 160 -> 200.00 functional
        _        <- recognized(xa, client, sg, op)
        _        <- rate(xa, "closing", LocalDate.now(), "1.30")           // close: +8.00 unrealized
        _        <- remeas.run(LocalDate.now()).flatMap(r => IO.raiseWhen(r.isLeft)(new RuntimeException(s"$r")))
        _        <- rate(xa, "spot", LocalDate.now().plusDays(1), "1.32")  // settlement executes at 1.32
        proposer <- user(xa, "fx-settle-maker")
        approver <- user(xa, "fx-settle-checker")
        sid      <- svc.propose(sg, op, "GBP", LocalDate.now().plusDays(1), proposer).map(_.toOption.get)
        r        <- svc.approve(sid, approver)
        fx       <- balance(client, s"FX_GAINLOSS:$sg")
        adjunct  <- balance(client, s"IC_AR_REMEASURE:$sg:$op")
        settled  <- balance(client, s"FX_SETTLED:$sg")
        next     <- remeas.run(LocalDate.now().plusDays(1))
        nextRows <- sql"""SELECT COALESCE(SUM(ABS(delta)), 0) FROM ic_remeasurement
                WHERE procurement_entity_id = $sg AND created_at > now() - interval '2 seconds'
                  AND tb_transfer_id IS NULL""".query[BigDecimal].unique.transact(xa)
        ctrl     <- control(xa, "CTRL-IC-SETTLE-ZERO")
        remeasOk <- control(xa, "CTRL-IC-REMEASURE")
        closure  <- control(xa, "CTRL-LINEAGE-CLOSURE")
      } yield expect(r.isRight) and
        expect.same(r.toOption.get.realizedFx, BigDecimal("11.2000")) and // 160×1.32 − 200
        expect.same(fx._2 - fx._1, BigInt(1120)) and                      // P&L saw 11.20 in total: 8.00 + 3.20, once
        expect.same(adjunct._1, adjunct._2) and                           // the adjunct CLEARED — reclassified, not duplicated
        expect.same(settled._1, BigInt(1120)) and                         // realized parked for treasury conversion
        expect(next.isRight) and expect.same(nextRows, BigDecimal(0)) and // nothing left to measure
        expect.same(ctrl, 0L) and expect.same(remeasOk, 0L) and expect.same(closure, 0L)
  }

  test("4b decoupling: a hedge does NOT bind the booking — the IC balance books and settles at spot, untouched") {
    case (xa, client) =>
      val svc = new IcSettlementService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "USD")
        (sg, op) = es
        hid <-
          sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
                     VALUES ($sg, 'GBP', 'USD', 1.28000000, 1000.00,
                             ${LocalDate
            .now()
            .minusMonths(1)}, ${LocalDate.now().plusMonths(11)}, 'active') RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        // a spot exists — the match books at SPOT (1.30), NOT the hedge's 1.28: the hedge is a separate instrument
        _        <- rate(xa, "spot", LocalDate.now(), "1.30")
        did      <- recognized(xa, client, sg, op)
        src      <- sql"SELECT rate_source FROM ic_match WHERE dispatch_id = $did".query[String].unique.transact(xa)
        proposer <- user(xa, "hg-settle-maker")
        approver <- user(xa, "hg-settle-checker")
        sid      <- svc.propose(sg, op, "GBP", LocalDate.now(), proposer).map(_.toOption.get)
        r        <- svc.approve(sid, approver)
        d <-
          sql"SELECT notional_used, ic_drawdown FROM fx_hedge WHERE id = $hid"
            .query[(BigDecimal, BigDecimal)]
            .unique
            .transact(xa)
        ctrl    <- control(xa, "CTRL-IC-SETTLE-ZERO")
        closure <- control(xa, "CTRL-LINEAGE-CLOSURE")
      } yield expect(r.isRight) and
        expect(src.startsWith("spot:")) and                                       // booked at spot, not 'hedge:'
        expect.same(r.toOption.get.settledFunctional, BigDecimal("208.0000")) and // 160 × 1.30, at spot
        expect.same(d._1, BigDecimal("0.0000")) and                               // the hedge was NOT drawn down by the booking (4b)
        expect.same(d._2, BigDecimal("0.0000")) and
        expect.same(ctrl, 0L) and expect.same(closure, 0L)
  }

  test("DETECTION: a corrupted settlement fails CTRL-IC-SETTLE-ZERO with itself as evidence") {
    case (xa, _) =>
      for {
        sid      <- sql"SELECT id FROM ic_settlement WHERE status = 'settled' LIMIT 1".query[UUID].unique.transact(xa)
        saved    <- sql"SELECT net_txn FROM ic_settlement WHERE id = $sid".query[BigDecimal].unique.transact(xa)
        _        <- sql"UPDATE ic_settlement SET net_txn = net_txn + 1 WHERE id = $sid".update.run.transact(xa)
        broken   <- control(xa, "CTRL-IC-SETTLE-ZERO")
        _        <- sql"UPDATE ic_settlement SET net_txn = $saved WHERE id = $sid".update.run.transact(xa)
        restored <- control(xa, "CTRL-IC-SETTLE-ZERO")
      } yield expect(broken > 0L) and expect.same(restored, 0L)
  }
}
