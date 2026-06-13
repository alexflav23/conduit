package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.intercompany.HedgeValuationService
import com.hypervolt.conduit.intercompany.IcRemeasurementService
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

// M-IC-FX slice 4b (spec doc 28 §5.5, ASC 815 economic treatment). The GAAP-correct model for hedging a
// RECOGNIZED IC monetary balance: the balance floats at spot and remeasures through earnings (ASC 830), and
// the hedge is a SEPARATE instrument whose period MTM posts through earnings to OFFSET it. No per-match lock,
// no drawdown — the hedge never binds the booking rate. When the hedge's contracted rate equals the booking
// spot and its notional matches the exposure, the two movements cancel exactly and net FX_GAINLOSS is zero.
object IcHedgeEconomicSuite extends IOSuite {

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

  // a hedge GBP->USD at `contracted`, notional in GBP, designated economic (the default)
  private def hedge(xa: HikariTransactor[IO], e: UUID, contracted: String, notional: String): IO[UUID] =
    sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
          VALUES ($e, 'GBP', 'USD', ${BigDecimal(contracted)}, ${BigDecimal(notional)},
                  ${LocalDate.now().minusMonths(2)}, ${LocalDate.now().plusMonths(10)}, 'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def rate(xa: HikariTransactor[IO], kind: String, asOf: LocalDate, r: String): IO[Unit] =
    sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
          VALUES ('GBP','USD', ${BigDecimal(r)}, $kind, $asOf, 'test') ON CONFLICT DO NOTHING""".update.run
      .transact(xa)
      .void

  // recognise a flash order: uplift 380×2 − 300×2 = 160 GBP, booked at the prevailing spot
  private def recognised(xa: HikariTransactor[IO], client: Client, sg: UUID, op: UUID): IO[UUID] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "he-maker")
      check  <- user(xa, "he-check")
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
      _   <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"recognize failed: $r"))
    } yield did
  }

  private def fx(client: Client, e: UUID): IO[(BigInt, BigInt)] =
    TigerBeetleLedger
      .fromClient[IO](client)
      .balance(TbIds.accountId(s"FX_GAINLOSS:$e"))
      .map(b => (b.debitsPosted, b.creditsPosted))

  test("the economic offset: the balance remeasures at spot, the hedge MTM offsets it, net FX_GAINLOSS = 0") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val remeas = new IcRemeasurementService[IO](xa, ledger)
      val hedges = new HedgeValuationService[IO](xa, ledger)
      val d0     = LocalDate.now().minusDays(2)
      val d1     = LocalDate.now().minusDays(1)
      for {
        es <- entities(xa)
        (sg, op) = es
        // booking spot 1.25; hedge contracted AT 1.25 on the 160 GBP exposure (perfect offset)
        _   <- rate(xa, "spot", d0, "1.25")
        hid <- hedge(xa, sg, "1.25", "160.00")
        did <- recognised(xa, client, sg, op) // uplift 160 booked at 1.25 → 200 USD
        src <- sql"SELECT rate_source FROM ic_match WHERE dispatch_id = $did".query[String].unique.transact(xa)
        _   <- hedges.revalue(d0)             // hedge at 1.25 = 0 (nothing posts)
        // close at 1.20: balance loses (1.25→1.20)×160 = −8; hedge gains (1.25→1.20)×160 = +8
        _   <- rate(xa, "closing", d1, "1.20")
        rm  <- remeas.run(d1)
        _   <- hedges.revalue(d1)
        bal <- fx(client, sg)
        // the balance IS in the remeasure base now (slice 2 no longer excludes a hedged pair)
        rmRows <-
          sql"SELECT count(*) FROM ic_remeasurement WHERE procurement_entity_id = $sg".query[Int].unique.transact(xa)
        hvRows  <- sql"SELECT count(*) FROM hedge_valuation WHERE fx_hedge_id = $hid".query[Int].unique.transact(xa)
        closure <- new ControlRunner[IO](xa).run("CTRL-LINEAGE-CLOSURE", None).map(_.toOption.get.violations)
        perf    <- new ControlRunner[IO](xa).run("CTRL-HEDGE-PERF", None).map(_.toOption.get.violations)
      } yield expect(rm.isRight) and
        expect(src.startsWith("spot:")) and // booked at spot, NOT 'hedge:' — the hedge does not lock
        expect(rmRows >= 1) and             // the hedged balance remeasures (un-frozen, 4b)
        expect(hvRows >= 1) and
        expect.same(bal._1, bal._2) and // net FX_GAINLOSS = 0: remeasurement −8 + hedge MTM +8
        expect.same(closure, 0L) and expect.same(perf, 0L)
  }
}
