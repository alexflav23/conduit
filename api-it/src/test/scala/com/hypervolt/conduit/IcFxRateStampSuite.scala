package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 1 (spec doc 28 §5.1): one moment fixes everything — the dispatch instant binds the booked
// spot rate into the principal's functional currency alongside the catalogue version and the genealogy.
// Same-currency hops stamp identity; cross-currency books the latest provenanced spot on-or-before the
// dispatch date; a missing cross-currency rate FAILS CLOSED exactly like an unpriced hop.
object IcFxRateStampSuite extends IOSuite {

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

  // A priced, stocked, dispatched-and-delivered order under the principal — recognition is the act under test.
  private def dispatched(xa: HikariTransactor[IO], sg: UUID, op: UUID): IO[UUID] = {
    val dispatch = new DispatchService[IO](xa)
    for {
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "fx-maker")
      check  <- user(xa, "fx-checker")
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
    } yield did
  }

  private def stamp(xa: HikariTransactor[IO], did: UUID): IO[(BigDecimal, String, String, BigDecimal)] =
    sql"""SELECT booked_rate, rate_source, principal_functional_ccy, transfer_total_functional
          FROM ic_match WHERE dispatch_id = $did"""
      .query[(BigDecimal, String, String, BigDecimal)]
      .unique
      .transact(xa)

  private def rate(xa: HikariTransactor[IO], asOf: LocalDate, r: String): IO[Unit] =
    sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
          VALUES ('GBP', 'USD', ${BigDecimal(r)}, 'spot', $asOf, 'test') ON CONFLICT DO NOTHING""".update.run
      .transact(xa)
      .void

  test("a same-currency hop stamps the identity rate — the functional measure IS the transfer total") {
    case (xa, client) =>
      val rev = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "GBP")
        (sg, op) = es
        did <- dispatched(xa, sg, op)
        r   <- rev.recognize(did)
        s   <- stamp(xa, did)
      } yield expect(r.isRight) and
        expect.same(s._1, BigDecimal("1.00000000")) and
        expect.same(s._2, "identity") and
        expect.same(s._3, "GBP") and
        expect.same(s._4, BigDecimal("760.0000")) // 2 × 380, measured 1:1
  }

  test("a cross-currency hop with NO spot rate fails closed — recognition blocks like an unpriced hop") {
    case (xa, client) =>
      val rev = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "USD")
        (sg, op) = es
        did <- dispatched(xa, sg, op)
        r   <- rev.recognize(did)
        rec <- sql"SELECT count(*) FROM revenue_recognition WHERE dispatch_id = $did".query[Int].unique.transact(xa)
      } yield expect(r.isLeft) and expect.same(rec, 0) and
        expect(r.swap.toOption.get.contains("fails closed"))
  }

  test("two dispatches across a rate change carry different booked rates — the dispatch date binds the rate") {
    case (xa, client) =>
      val rev = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa, "USD")
        (sg, op) = es
        // first recognition sees only yesterday's 1.25
        _    <- rate(xa, LocalDate.now().minusDays(1), "1.25")
        did1 <- dispatched(xa, sg, op)
        r1   <- rev.recognize(did1)
        s1   <- stamp(xa, did1)
        // the rate moves; the SECOND dispatch books today's 1.27 — the first stamp is immutable
        _    <- rate(xa, LocalDate.now(), "1.27")
        did2 <- dispatched(xa, sg, op)
        r2   <- rev.recognize(did2)
        s2   <- stamp(xa, did2)
        s1b  <- stamp(xa, did1)
      } yield expect(r1.isRight) and expect(r2.isRight) and
        expect.same(s1._1, BigDecimal("1.25000000")) and
        expect.same(s1._4, BigDecimal("950.0000")) and // 760 × 1.25
        expect.same(s2._1, BigDecimal("1.27000000")) and
        expect.same(s2._4, BigDecimal("965.2000")) and // 760 × 1.27
        expect(s1._2.startsWith("spot:")) and
        expect.same(s1b, s1) // booked means booked: the earlier match did not move
  }
}
