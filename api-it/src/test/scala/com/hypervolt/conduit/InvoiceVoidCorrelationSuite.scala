package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.revenue.CollectionCycle
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.InvoiceVoidProcessor
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13-Void.5c — the perfect causal log. A void cycle is one correlated thread: invoice.voided, the refund
// payment.received, and the credit-note document.issued all carry the SAME correlation_id (= the reversal id),
// and invoice.voided's causation points back to the void request. So the back-and-forth isn't reassembled by
// hand — the causal chain is recorded on the immutable events.
object InvoiceVoidCorrelationSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], com.tigerbeetle.Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

  // A paid invoice with a finalised invoice document, ready to void+refund+credit-note. Returns (orderId, invId, no).
  private def paidInvoiceWithDoc(xa: HikariTransactor[IO], ledger: TigerBeetleLedger[IO]): IO[(UUID, UUID, String)] =
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'credit_note','GB','HV-UK-CN','{series}-{yyyy}-{seq:06d}')".update.run
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Corr Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                        VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1200.00) RETURNING id"""
              .query[UUID]
              .unique
          no = s"INV-${UUID.randomUUID()}"
          inv <-
            sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status) VALUES ($ord, $no, 1200, 0, 1200, 'open') RETURNING id"
              .query[UUID]
              .unique
          tmpl <-
            sql"SELECT id FROM document_template WHERE document_type='invoice' AND status='active' ORDER BY (jurisdiction IS NOT NULL) DESC, version DESC LIMIT 1"
              .query[UUID]
              .unique
          _ <-
            sql"""INSERT INTO document (document_type, entity_id, formatted_number, order_invoice_id, order_id, locale, jurisdiction,
                                        template_id, template_version, currency, total_amount, render_model, status, storage_uri, content_sha256, issued_at)
                  VALUES ('invoice', $e, 'HV-UK-INV-2026-000001', $inv, $ord, 'en','GB', $tmpl, 1, 'GBP', 1200, '{}'::jsonb, 'finalised', 'mem://x.pdf','sha', now())""".update.run
        } yield (e, billTo, ord, inv, no)).transact(xa)
      (e, billTo, ord, inv, no) = ids
      _ <- ledger.createAccounts(
        List(
          LedgerAccount(TbIds.accountId(s"AR:$billTo"), Ledgers.forCurrency(gbp), LedgerAccountCode.Ar),
          LedgerAccount(TbIds.accountId(s"REVENUE:$e"), Ledgers.forCurrency(gbp), LedgerAccountCode.Revenue)
        )
      )
      _ <- ledger.postTransfers(
        List(
          LedgerTransfer(
            TbIds.transferId(UUID.randomUUID(), 0),
            TbIds.accountId(s"AR:$billTo"),
            TbIds.accountId(s"REVENUE:$e"),
            BigInt(120000),
            Ledgers.forCurrency(gbp),
            LedgerTransferCode.Generic
          )
        )
      )
      _ <- new PaymentService[IO](xa, ledger).apply(no, BigDecimal("1200.00"), "bank", Some(s"P-$no"))
    } yield (ord, inv, no)

  test("the void → refund → credit-note events share one correlation_id, threaded back to the void request") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val processor =
        new InvoiceVoidProcessor[IO](xa, new InvoiceReversalService[IO](xa, ledger), new PaymentService[IO](xa, ledger))
      for {
        s <- paidInvoiceWithDoc(xa, ledger)
        (ord, inv, _) = s
        storage <- DocumentStorage.inMemory[IO]
        docs = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
        corr = CollectionCycle.correlationId(inv)
        vreq <- IO(UUID.randomUUID()) // the void-request event id (the cause)
        _    <- processor.process(inv, s._3, "refund", "customer returned the unit", "finance:e2e", Some(vreq))
        _    <- docs.invalidateInvoice(inv, "customer returned the unit", Some(corr), Some(corr))
        threaded <-
          sql"SELECT event_type, causation_id FROM outbox_event WHERE correlation_id = $corr ORDER BY seq"
            .query[(String, Option[UUID])]
            .to[List]
            .transact(xa)
      } yield {
        val types  = threaded.map(_._1).toSet
        val voided = threaded.find(_._1 == "invoice.voided")
        expect(types.contains("invoice.voided")) and
          expect(types.contains("payment.received")) and // the refund
          expect(types.contains("document.issued")) and  // the credit note
          expect(threaded.size >= 3) and                 // the whole cycle on one thread
          expect(voided.flatMap(_._2).contains(vreq))    // invoice.voided caused by the void request
      }
  }
}
