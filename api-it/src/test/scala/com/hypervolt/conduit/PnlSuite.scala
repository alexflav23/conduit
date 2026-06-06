package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueQueryRepo
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13 — ASC-606 recognition at dispatch + the P&L read-model (doc 07 M13). Recognising posts AR/Revenue/VAT and
// COGS/INV to the immutable ledger at specific batch cost; the P&L (RevenueQueryRepo.totals) reads the
// revenue_recognition rows written atomically with that post — so the P&L is proof, not a re-computation.
object PnlSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val market = UUID.randomUUID()

  private def setup(xa: HikariTransactor[IO]): IO[(UUID, UUID, UUID, List[String])] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
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
      _       <- InventoryRepo.receive(Some(e), v, loc, 2)
      s1      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      s2      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      _       <- LotBatchRepo.assignSerial(s1, b)
      _       <- LotBatchRepo.assignSerial(s2, b)
      serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1, $s2)".query[String].to[List]
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, $market, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield (e, ord, ol, serials)).transact(xa)

  private def num(j: io.circe.Json, field: String): Option[BigDecimal] =
    j.hcursor.get[String](field).toOption.map(BigDecimal(_))

  test("recognition at dispatch posts to the ledger and the P&L read-model shows matched revenue + COGS + margin") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val disp   = new DispatchService[IO](xa)
      val rev    = new RevenueRecognitionService[IO](xa, ledger)
      for {
        s <- setup(xa)
        (entity, ord, ol, serials) = s
        did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        // dispatch.created carries the dispatch_id the recognition consumer fires on
        evt <-
          sql"SELECT payload ->> 'dispatch_id' FROM outbox_event WHERE event_type='dispatch.created' AND aggregate_id=$ord"
            .query[String]
            .unique
            .transact(xa)
        r      <- rev.recognize(did)
        totals <- RevenueQueryRepo.totals(market, LocalDate.now().withDayOfMonth(1)).transact(xa)
        revBal <- ledger.balance(rev.revenue(entity))
      } yield expect(r.isRight) and
        expect(evt == did.toString) and // consumer would recognise on this id
        expect(num(totals, "revenue_ex_vat").contains(BigDecimal("1000.0000"))) and
        expect(num(totals, "cogs").contains(BigDecimal("600.0000"))) and // 2 × £300 specific batch cost
        expect(num(totals, "gross_margin").contains(BigDecimal("400.0000"))) and
        expect(revBal.creditsPosted == BigInt(100000)) // £1000 revenue proved in the ledger
  }
}
