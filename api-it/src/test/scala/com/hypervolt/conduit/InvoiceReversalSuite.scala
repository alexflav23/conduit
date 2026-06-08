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

// M13-Void.1 — invoice invalidation reverses the recognition on the immutable ledger (ASC 606). A recognised sale
// (DR AR / CR Revenue+VAT, DR COGS / CR INV) is voided: the reversal negates every leg so AR, Revenue, VAT, COGS
// and INV all net back to zero — the invoice stops counting. The invoice row carries the void marker, an immutable
// invoice_reversal fact is recorded, an invoice.voided event is emitted, and a re-run is a no-op.
object InvoiceReversalSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  // A recognised sale on a fresh entity: 2 serialised units @ £500 ex-VAT (+£200 VAT), £300 specific batch cost.
  // Returns (entity, billTo, orderInvoiceId).
  private def recognisedSale(xa: HikariTransactor[IO], ledger: TigerBeetleLedger[IO]): IO[(UUID, UUID, UUID)] = {
    val disp = new DispatchService[IO](xa)
    val rev  = new RevenueRecognitionService[IO](xa, ledger)
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
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
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Void Cust','wholesaler',true) RETURNING id"
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
        } yield (e, billTo, ord, ol, serials)).transact(xa)
      (e, billTo, ord, ol, serials) = ids
      did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      _   <- rev.recognize(did)
      inv <- sql"SELECT id FROM order_invoice WHERE order_id = $ord".query[UUID].unique.transact(xa)
    } yield (e, billTo, inv)
  }

  private def bal(ledger: TigerBeetleLedger[IO], acc: BigInt): IO[BigInt] =
    ledger.balance(acc).map(b => b.debitsPosted - b.creditsPosted)

  test("voiding a recognised invoice reverses every leg: AR, revenue, VAT, COGS and INV all net to zero") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val rev    = new RevenueRecognitionService[IO](xa, ledger)
      val voider = new InvoiceReversalService[IO](xa, ledger)
      for {
        s <- recognisedSale(xa, ledger)
        (entity, billTo, inv) = s
        arBefore  <- bal(ledger, rev.ar(billTo))
        revBefore <- bal(ledger, rev.revenue(entity))
        r         <- voider.reverse(inv, "mistake", "wrong customer on the PO", "finance:e2e")
        arAfter   <- bal(ledger, rev.ar(billTo))
        revAfter  <- bal(ledger, rev.revenue(entity))
        vatAfter  <- bal(ledger, rev.vatAcc(entity, "GB"))
        cogsAfter <- bal(ledger, rev.cogsAcc(entity))
        invAfter  <- bal(ledger, rev.inv(entity))
        row <-
          sql"SELECT status, void_kind, void_reason IS NOT NULL FROM order_invoice WHERE id = $inv"
            .query[(String, String, Boolean)]
            .unique
            .transact(xa)
        recCount <-
          sql"SELECT count(*) FROM invoice_reversal WHERE order_invoice_id = $inv".query[Long].unique.transact(xa)
        evt <- sql"SELECT count(*) FROM outbox_event WHERE event_type='invoice.voided'".query[Long].unique.transact(xa)
      } yield expect(arBefore == BigInt(120000)) and expect(
        revBefore == BigInt(-100000)
      ) and // recognised: AR +£1200, Revenue credit £1000
        expect(r.isRight) and
        expect(arAfter == BigInt(0)) and expect(revAfter == BigInt(0)) and // both unwound
        expect(vatAfter == BigInt(0)) and expect(cogsAfter == BigInt(0)) and expect(invAfter == BigInt(0)) and
        expect(row == (("void", "mistake", true))) and // invoice carries the marker
        expect(recCount == 1L) and expect(evt == 1L)
  }

  test("a second void of the same invoice is an idempotent no-op (no double reversal on the ledger)") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val rev    = new RevenueRecognitionService[IO](xa, ledger)
      val voider = new InvoiceReversalService[IO](xa, ledger)
      for {
        s <- recognisedSale(xa, ledger)
        (entity, billTo, inv) = s
        r1      <- voider.reverse(inv, "cancellation", "customer cancelled", "finance:e2e")
        r2      <- voider.reverse(inv, "cancellation", "customer cancelled", "finance:e2e")
        arAfter <- bal(ledger, rev.ar(billTo))
        recCount <-
          sql"SELECT count(*) FROM invoice_reversal WHERE order_invoice_id = $inv".query[Long].unique.transact(xa)
      } yield expect(r1.isRight && r2.isRight) and
        expect(r1.toOption.map(_.reversalId) == r2.toOption.map(_.reversalId)) and // same deterministic id
        expect(arAfter == BigInt(0)) and                                           // not double-credited
        expect(recCount == 1L)                                                     // exactly one reversal fact
  }

  test("an invalid kind or an empty reason is rejected") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val voider = new InvoiceReversalService[IO](xa, ledger)
      for {
        s <- recognisedSale(xa, ledger)
        (_, _, inv) = s
        badKind   <- voider.reverse(inv, "explode", "x", "a")
        badReason <- voider.reverse(inv, "mistake", "   ", "a")
      } yield expect(badKind.isLeft) and expect(badReason.isLeft)
  }
}
