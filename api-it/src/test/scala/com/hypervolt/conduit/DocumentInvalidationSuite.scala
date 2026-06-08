package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13-Void.2 — on invoice.voided, the document layer carries the invalidation. The original invoice PDF is WORM:
// its bytes never change, but its row is stamped voided_at/void_reason, and a credit_note document is minted that
// `corrects_document_id` the original. Idempotent on a deterministic credit-note id.
object DocumentInvalidationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // entity + invoice & credit-note number series (templates are seeded globally by V1_0_27 / V1_0_31) + an invoice.
  private def invoiceReady(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'invoice','GB','HV-UK-INV','{series}-{yyyy}-{seq:06d}')".update.run
      _ <-
        sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'credit_note','GB','HV-UK-CN','{series}-{yyyy}-{seq:06d}')".update.run
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', false) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Void Cust','Void Customer Ltd','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, invoice_locale) VALUES ($billTo,'V','GBP',30,'en')".update.run
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                 VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00)".update.run
      inv <-
        sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat) VALUES ($ord, ${s"INV-${UUID.randomUUID()}"}, 1000.00, 200.00, 1200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield inv).transact(xa)

  test("invalidation stamps the original invoice document and mints a credit note that corrects it, idempotently") {
    xa =>
      for {
        storage <- DocumentStorage.inMemory[IO]
        docs = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
        inv     <- invoiceReady(xa)
        invoice <- docs.generateInvoice(inv).map(_.toOption.get)
        cn1     <- docs.invalidateInvoice(inv, "wrong customer on the PO").map(_.toOption.get.get)
        cn2     <- docs.invalidateInvoice(inv, "wrong customer on the PO").map(_.toOption.get.get) // idempotent
        origMarked <-
          sql"SELECT voided_at IS NOT NULL, void_reason FROM document WHERE id = ${invoice.documentId}"
            .query[(Boolean, Option[String])]
            .unique
            .transact(xa)
        cnRow <-
          sql"""SELECT document_type, corrects_document_id, total_amount, status FROM document WHERE id = ${cn1.documentId}"""
            .query[(String, Option[UUID], BigDecimal, String)]
            .unique
            .transact(xa)
        cnCount <-
          sql"SELECT count(*) FROM document WHERE document_type='credit_note' AND corrects_document_id = ${invoice.documentId}"
            .query[Long]
            .unique
            .transact(xa)
        cnPdf <- storage.get(s"mem://documents/${cn1.documentId}.pdf")
      } yield expect(origMarked._1) and expect(origMarked._2.contains("wrong customer on the PO")) and // WORM marker
        expect(cn1.documentId == cn2.documentId) and                                                   // idempotent
        expect(cn1.formattedNumber.startsWith("HV-UK-CN-")) and                                        // gapless CN series
        expect(cnRow._1 == "credit_note" && cnRow._2.contains(invoice.documentId)) and                 // corrects the original
        expect(cnRow._3 == BigDecimal("1200.0000") && cnRow._4 == "finalised") and
        expect(cnCount == 1L) and                                // exactly one credit note
        expect(new String(cnPdf.take(5), "US-ASCII") == "%PDF-") // the credit note is a real PDF
  }

  test("invalidating an invoice with no generated document is a clean no-op (nothing to correct)") { xa =>
    for {
      storage <- DocumentStorage.inMemory[IO]
      docs = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
      inv <- invoiceReady(xa) // never generate the invoice document
      r   <- docs.invalidateInvoice(inv, "no doc here")
    } yield expect(r == Right(None))
  }
}
