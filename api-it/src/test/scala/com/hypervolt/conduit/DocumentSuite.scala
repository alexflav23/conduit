package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.document.DocumentNumberAllocator
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13 — document generation (doc 17): gapless/immutable numbering (void consumes a number, never reused, no gap)
// and the invoice as a rendered projection of typed truth (totals READ from order_invoice, conservation-checked,
// deterministic sha, idempotent — a redelivery mints no second invoice or number).
object DocumentSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val year       = LocalDate.now().getYear
  private val expectedNo = "HV-UK-INV-" + year + "-000001"

  private def entityWithSeries(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <- sql"""INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format)
                 VALUES ($e, 'invoice', 'GB', 'HV-UK-INV', '{series}-{yyyy}-{seq:06d}')""".update.run
    } yield e).transact(xa)

  test("numbering is gapless and never reused: void consumes a number, the next allocation skips no value") { xa =>
    for {
      e      <- entityWithSeries(xa)
      a1     <- DocumentNumberAllocator.allocate(e, "invoice", "GB", year).transact(xa).map(_.get)
      a2     <- DocumentNumberAllocator.allocate(e, "invoice", "GB", year).transact(xa).map(_.get)
      a3     <- DocumentNumberAllocator.allocate(e, "invoice", "GB", year).transact(xa).map(_.get)
      _      <- DocumentNumberAllocator.void(a2.numberId, "issued in error").transact(xa)
      a4     <- DocumentNumberAllocator.allocate(e, "invoice", "GB", year).transact(xa).map(_.get)
      seqs   <- sql"""SELECT seq FROM document_number dn JOIN document_number_series s ON s.id = dn.series_id
                      WHERE s.entity_id = $e ORDER BY seq""".query[Long].to[List].transact(xa)
      voided <- sql"""SELECT count(*) FROM document_number dn JOIN document_number_series s ON s.id = dn.series_id
                        WHERE s.entity_id = $e AND dn.status = 'voided'""".query[Long].unique.transact(xa)
    } yield expect(List(a1.seq, a2.seq, a3.seq, a4.seq) == List(1L, 2L, 3L, 4L)) and // no skip, no reuse
      expect(seqs == List(1L, 2L, 3L, 4L)) and                                       // contiguous: no gap
      expect(a1.formatted == expectedNo) and
      expect(voided == 1L) // the void is recorded, its seq consumed
  }

  private def invoice(xa: HikariTransactor[IO], e: UUID): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', false) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Doc Cust','Doc Customer Ltd','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, invoice_locale) VALUES ($billTo, 'Doc', 'GBP', 30, 'en')".update.run
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

  test(
    "invoice is a projection of typed truth: total read off order_invoice, gapless number, deterministic, idempotent"
  ) { xa =>
    for {
      storage <- DocumentStorage.inMemory[IO]
      svc = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage) // real Apache FOP PDF engine + WORM store
      e   <- entityWithSeries(xa)
      inv <- invoice(xa, e)
      r1  <- svc.generateInvoice(inv)
      r2  <- svc.generateInvoice(inv) // redelivery — must not mint a second invoice/number
      finalised <-
        sql"SELECT status, total_amount, content_sha256, storage_uri FROM document WHERE order_invoice_id = $inv"
          .query[(String, BigDecimal, String, String)]
          .to[List]
          .transact(xa)
      storedBytes <- storage.get(finalised.head._4)
      issuedEvt <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='document.issued'".query[Long].unique.transact(xa)
    } yield {
      val a = r1.toOption.get
      val b = r2.toOption.get
      expect(r1.isRight) and
        expect(a.formattedNumber == expectedNo) and    // gapless, formatted
        expect(a.total == BigDecimal("1200.0000")) and // READ off order_invoice
        expect(a.contentSha256.length == 64) and       // rendered + hashed
        expect(
          b.documentId == a.documentId && b.formattedNumber == a.formattedNumber && b.contentSha256 == a.contentSha256
        ) and                                                               // idempotent
        expect(finalised.size == 1 && finalised.head._1 == "finalised") and // exactly one, WORM
        expect(finalised.head._4.startsWith("mem://documents/")) and        // stored to object storage, URI recorded
        expect(new String(storedBytes.take(5), "US-ASCII") == "%PDF-") and  // the stored artefact is the real PDF
        expect(issuedEvt == 1L)                                             // single document.issued
    }
  }
}
