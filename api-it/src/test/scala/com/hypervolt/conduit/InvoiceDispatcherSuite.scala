package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import com.hypervolt.conduit.accounting.AccountingConsumer
import com.hypervolt.conduit.accounting.InvoiceDispatcher
import com.hypervolt.conduit.accounting.InvoiceRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13 — the order.invoiced → accounting consumer step (doc 03/07 M13). Verifies the dispatcher builds the
// invoice from the order, pushes it to the (swappable) AccountingConsumer, stamps the returned external id back
// onto order_invoice, and is idempotent on event_id (at-least-once redelivery is a no-op).
object InvoiceDispatcherSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // A fake ERP that records what it was asked to create and returns a deterministic external id.
  private def fakeConsumer(seen: Ref[IO, List[InvoiceRequest]]): AccountingConsumer[IO] =
    new AccountingConsumer[IO] {
      def createInvoice(req: InvoiceRequest): IO[Either[String, String]] =
        seen.update(req :: _).as(Right(s"XERO-${req.invoiceNo}"))
      def voidInvoice(externalRef: String, invoiceNo: String, reason: String): IO[Either[String, Unit]] =
        IO.pure(Right(()))
    }

  private def setup(xa: HikariTransactor[IO], invoiceNo: String): IO[Unit] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"HV-${UUID.randomUUID()}"}, 'v3') RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Acme Ltd','Acme Limited','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00)".update.run
      _ <- sql"""INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, tax_regime)
                 VALUES ($ord, $invoiceNo, 1000.00, 200.00, 1200.00, 'GB_STANDARD')""".update.run
    } yield ()).transact(xa)

  test("order.invoiced pushes a built invoice to the accounting consumer and stamps the external id; idempotent") {
    xa =>
      val invoiceNo = s"INV-${UUID.randomUUID()}"
      val eventId   = UUID.randomUUID()
      for {
        _    <- setup(xa, invoiceNo)
        seen <- Ref.of[IO, List[InvoiceRequest]](Nil)
        dispatcher = new InvoiceDispatcher[IO](xa, fakeConsumer(seen), s"test-${UUID.randomUUID()}")
        first  <- dispatcher.handle(eventId, invoiceNo)
        second <- dispatcher.handle(eventId, invoiceNo) // redelivery — must be a no-op
        reqs   <- seen.get
        xeroId <-
          sql"SELECT xero_invoice_id FROM order_invoice WHERE invoice_no = $invoiceNo"
            .query[Option[String]]
            .unique
            .transact(xa)
      } yield {
        val req = reqs.headOption
        expect(first) and expect(!second) and // first did the work, redelivery skipped
          expect(reqs.size == 1) and          // pushed exactly once
          expect(req.exists(_.contactName == "Acme Limited")) and
          expect(req.exists(_.currency == "GBP")) and
          expect(req.exists(_.lines.exists(l => l.qty == 2 && l.unitAmountExVat == BigDecimal("500.0000")))) and
          expect(xeroId.contains("XERO-" + invoiceNo)) // external id stamped back
      }
  }
}
