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
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-VAT.2b — the per-event reversal model proven with a NEW cost category. Outbound carriage is a first-class
// recognised leg; cancelling/voiding the invoice recalls the FULL set — revenue, VAT, COGS AND shipping — every
// account netting to zero on the immutable ledger. Adding the carriage category required no change to the void logic.
object CarriageReversalSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

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
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield (e, ord, ol, serials)).transact(xa)

  test(
    "a sale with outbound carriage recognises a carriage leg; voiding it reverses revenue, VAT, COGS AND shipping to zero"
  ) {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val dispatch = new DispatchService[IO](xa)
      val rev      = new RevenueRecognitionService[IO](xa, ledger)
      val reversal = new InvoiceReversalService[IO](xa, ledger)
      for {
        s <- setup(xa)
        (e, ord, ol, serials) = s
        did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        // £50 outbound carriage incurred on this dispatch
        _      <- sql"UPDATE dispatch SET shipping_cost = 50.00 WHERE id = $did".update.run.transact(xa)
        _      <- dispatch.deliver(did)
        _      <- rev.recognize(did).map(_.toOption.get)
        carExp <- ledger.balance(rev.carriageExp(e))
        carAcc <- ledger.balance(rev.carriageAccr(e))
        invId <-
          sql"SELECT id FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
            .query[UUID]
            .unique
            .transact(xa)
        _           <- reversal.reverse(invId, "cancellation", "customer cancelled the order", "test").map(_.toOption.get)
        carExpAfter <- ledger.balance(rev.carriageExp(e))
        carAccAfter <- ledger.balance(rev.carriageAccr(e))
        revAfter    <- ledger.balance(rev.revenue(e))
        vatAfter    <- ledger.balance(rev.vatAcc(e))
        shipRow <-
          sql"SELECT shipping_cost FROM revenue_recognition WHERE dispatch_id = $did"
            .query[BigDecimal]
            .unique
            .transact(xa)
      } yield
      // recognition posted the carriage leg
      expect(carExp.debitsPosted == BigInt(5000)) and
        expect(carAcc.creditsPosted == BigInt(5000)) and
        expect(shipRow == BigDecimal("50.0000")) and
        // after the void, every account nets to zero — including shipping
        expect(carExpAfter.debitsPosted == carExpAfter.creditsPosted) and
        expect(carAccAfter.debitsPosted == carAccAfter.creditsPosted) and
        expect(revAfter.debitsPosted == revAfter.creditsPosted) and
        expect(vatAfter.debitsPosted == vatAfter.creditsPosted)
  }
}
