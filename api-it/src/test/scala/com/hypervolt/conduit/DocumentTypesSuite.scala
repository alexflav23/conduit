package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-Docs.7 — the remaining legal document types render through the same engine (doc 17 §5). proforma = a
// pre-payment projection of the order (money, no AR); packing_list = a VOLUME-only shipment doc (serials, no money).
// Both are gapless-numbered, WORM, real PDFs.
object DocumentTypesSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // entity (with proforma + packing_list series) + a serialised product, dispatched, so both docs have data.
  private def dispatched(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'proforma','GB','HV-UK-PF','{series}-{yyyy}-{seq:06d}')".update.run
      _ <-
        sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'packing_list','GB','HV-UK-PL','{series}-{yyyy}-{seq:06d}')".update.run
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Doc Cust','Doc Customer Ltd','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, invoice_locale) VALUES ($billTo,'Doc','GBP',30,'en')".update.run
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
    } yield (ord, ol, serials)).transact(xa).flatMap {
      case (ord, ol, serials) =>
        new DispatchService[IO](xa)
          .dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials)))
          .map(d => (ord, d.toOption.get))
    }

  test(
    "proforma renders a numbered money PDF from the order; packing list is a volume-only serial PDF; both idempotent"
  ) { xa =>
    for {
      storage <- DocumentStorage.inMemory[IO]
      docs = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
      s <- dispatched(xa)
      (ord, dsp) = s
      pf1 <- docs.generateProforma(ord).map(_.toOption.get)
      pf2 <- docs.generateProforma(ord).map(_.toOption.get) // idempotent
      pl1 <- docs.generatePackingList(dsp).map(_.toOption.get)
      pl2 <- docs.generatePackingList(dsp).map(_.toOption.get)
      pfRow <-
        sql"SELECT document_type, total_amount, status FROM document WHERE id = ${pf1.documentId}"
          .query[(String, Option[BigDecimal], String)]
          .unique
          .transact(xa)
      plRow <-
        sql"SELECT document_type, total_amount, dispatch_id IS NOT NULL FROM document WHERE id = ${pl1.documentId}"
          .query[(String, Option[BigDecimal], Boolean)]
          .unique
          .transact(xa)
      pfPdf <- storage.get(s"mem://documents/${pf1.documentId}.pdf")
      plPdf <- storage.get(s"mem://documents/${pl1.documentId}.pdf")
    } yield expect(pf1.formattedNumber.startsWith("HV-UK-PF-")) and                   // proforma series
      expect(pf1.documentId == pf2.documentId) and                                    // idempotent
      expect(pfRow == (("proforma", Some(BigDecimal("1200.0000")), "finalised"))) and // money doc
      expect(new String(pfPdf.take(5), "US-ASCII") == "%PDF-") and
      expect(pl1.formattedNumber.startsWith("HV-UK-PL-")) and // packing-list series
      expect(pl1.documentId == pl2.documentId) and
      expect(plRow == (("packing_list", None, true))) and // VOLUME-only: no money, tied to dispatch
      expect(new String(plPdf.take(5), "US-ASCII") == "%PDF-")
  }
}
