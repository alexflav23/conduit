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
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// ASC 606 revenue recognition on dispatch (doc 04 §Ledger, doc 13). On delivery, control transfers: revenue is
// recognised and proved in the TigerBeetle immutable ledger; COGS relieves inventory at the dispatched serials'
// SPECIFIC batch cost, so gross margin is exact.
object RevenueRecognitionSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def setup(xa: HikariTransactor[IO]): IO[(UUID, UUID, UUID, UUID, List[String])] =
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
      // landed cost £300/unit (unit_cost_usd 300 × fx 1.0, no freight/duty)
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
      // an order for 2 units @ £500 ex-VAT, £200 VAT (20%)
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'stripe', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield (e, billTo, ord, ol, serials)).transact(xa)

  test("revenue recognised on dispatch posts AR/Revenue/VAT and COGS/INV to the ledger; gross margin is exact") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val dispatch = new DispatchService[IO](xa)
      val rev      = new RevenueRecognitionService[IO](xa, ledger)
      for {
        s <- setup(xa)
        (e, billTo, ord, ol, serials) = s
        did     <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        _       <- dispatch.deliver(did)
        r1      <- rev.recognize(did)
        r2      <- rev.recognize(did) // idempotent — must not double-post
        arBal   <- ledger.balance(rev.ar(billTo))
        revBal  <- ledger.balance(rev.revenue(e))
        vatBal  <- ledger.balance(rev.vatAcc(e))
        cogsBal <- ledger.balance(rev.cogsAcc(e))
        invBal  <- ledger.balance(rev.inv(e))
        row <-
          sql"SELECT revenue_ex_vat, vat, cogs, gross_margin FROM revenue_recognition WHERE dispatch_id=$did"
            .query[(BigDecimal, BigDecimal, BigDecimal, BigDecimal)]
            .unique
            .transact(xa)
      } yield expect(r1.isRight) and expect(r2.isRight) and
        expect(arBal.debitsPosted == BigInt(120000)) and   // £1200 inc VAT
        expect(revBal.creditsPosted == BigInt(100000)) and // £1000 ex VAT
        expect(vatBal.creditsPosted == BigInt(20000)) and  // £200 VAT
        expect(cogsBal.debitsPosted == BigInt(60000)) and  // 2 × £300 specific batch cost
        expect(invBal.creditsPosted == BigInt(60000)) and
        expect(
          row == ((BigDecimal("1000.0000"), BigDecimal("200.0000"), BigDecimal("600.0000"), BigDecimal("400.0000")))
        )
  }
}
