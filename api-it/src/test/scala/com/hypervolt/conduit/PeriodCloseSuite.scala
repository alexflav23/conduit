package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.close.LineageService
import com.hypervolt.conduit.close.PeriodCloseService
import com.hypervolt.conduit.close.ReconciliationService
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.document.DocumentRenderer
import com.hypervolt.conduit.document.DocumentService
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

// M13b — period close + reconciliation + control runner + auditability lineage (doc 14 §5–6). A recognised,
// invoiced, documented sale flows through the close: reconciliations tie out, the controls pass, the lineage
// reconstructs figure → ledger → events → PDF, and the period locks only over clean books.
object PeriodCloseSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  // A full recognised + invoiced + documented sale on a fresh entity. Returns (entity, orderInvoiceId).
  private def soldAndDocumented(xa: HikariTransactor[IO], ledger: TigerBeetleLedger[IO]): IO[(UUID, UUID)] = {
    val disp = new DispatchService[IO](xa)
    val rev  = new RevenueRecognitionService[IO](xa, ledger)
    val docs = new DocumentService[IO](xa, DocumentRenderer.deterministic[IO])
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'invoice','GB','HV-UK-INV','{series}-{yyyy}-{seq:06d}')".update.run
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
              .query[UUID]
              .unique
          v <-
            sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
              .query[UUID]
              .unique
          billTo <-
            sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Close Cust','Close Customer Ltd','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, invoice_locale) VALUES ($billTo,'C','GBP',30,'en')".update.run
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
        } yield (e, v, ord, ol, serials)).transact(xa)
      (e, _, ord, ol, serials) = ids
      did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      _   <- rev.recognize(did)
      inv <- sql"SELECT id FROM order_invoice WHERE order_id = $ord".query[UUID].unique.transact(xa)
      _   <- docs.generateInvoice(inv)
    } yield (e, inv)
  }

  test("reconciliations tie out, the auditability lineage reconstructs, and a clean period locks") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val recon   = new ReconciliationService[IO](xa, ledger)
      val close   = new PeriodCloseService[IO](xa)
      val lineage = new LineageService[IO](xa)
      for {
        s <- soldAndDocumented(xa, ledger)
        (entity, inv) = s
        period <- close.ensurePeriod(entity, "month", "2026-09", "Europe/London")
        ar     <- recon.arVsInvoices(period, entity, "GBP")
        tb     <- recon.tbVsGl(period, entity, "GBP")
        lin    <- lineage.forInvoice(inv)
        closer = UUID.randomUUID()
        locker = UUID.randomUUID()
        _       <- close.close(period, closer)
        locked  <- close.lock(period, locker)
        posting <- close.postingAllowed(entity, "month", "2026-09")
      } yield {
        val transfers = lin.flatMap(_.hcursor.downField("ledger_transfers").as[List[String]].toOption).getOrElse(Nil)
        val hasDoc =
          lin.flatMap(_.hcursor.downField("document").downField("formatted_number").as[String].toOption).isDefined
        expect(ar.status == "matched") and expect(
          ar.expected == BigDecimal("1200.00") && ar.actual == BigDecimal("1200.00")
        ) and
          expect(tb.status == "matched") and expect(tb.variance.signum == 0) and // ledger ties out
          expect(transfers.size == 3 && hasDoc) and                              // figure → 3 transfers + the PDF
          expect(locked.isRight) and expect(!posting)                            // clean books lock; posting now barred
      }
  }

  test("a period will not lock over an unsigned reconciliation exception; signing it off clears the gate") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val recon  = new ReconciliationService[IO](xa, ledger)
      val close  = new PeriodCloseService[IO](xa)
      for {
        s <- soldAndDocumented(xa, ledger)
        (entity, _) = s
        period <- close.ensurePeriod(entity, "month", "2026-10", "Europe/London")
        // an extra unpaid invoice with no matching AR ledger posting → AR↔invoices breaks
        _ <-
          sql"""INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat)
                   SELECT o.id, ${s"INV-X-${UUID.randomUUID()}"}, 500, 100, 600 FROM "order" o WHERE o.entity_id=$entity LIMIT 1""".update.run
            .transact(xa)
        ar      <- recon.arVsInvoices(period, entity, "GBP")
        _       <- close.close(period, UUID.randomUUID())
        blocked <- close.lock(period, UUID.randomUUID())
        _       <- recon.signOff(ar.id, UUID.randomUUID())
        nowOk   <- close.lock(period, UUID.randomUUID())
      } yield expect(ar.status == "exception") and expect(ar.variance != BigDecimal("0.00")) and
        expect(blocked.isLeft) and expect(blocked.left.toOption.exists(_.contains("exception"))) and
        expect(nowOk.isRight) // sign-off cleared the gate
  }

  test("control runner: gapless-numbering passes on clean data and fails when a number is missing") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val runner = new ControlRunner[IO](xa)
      for {
        _        <- soldAndDocumented(xa, ledger) // mints one gapless invoice number (seq 1) on a fresh series
        clean    <- runner.run("CTRL-DOC-GAPLESS", None)
        seriesId <- sql"SELECT id FROM document_number_series ORDER BY id DESC LIMIT 1".query[UUID].unique.transact(xa)
        _ <-
          sql"""INSERT INTO document_number (series_id, seq, formatted_number, status)
                          VALUES ($seriesId, 2, 'X-2', 'issued'), ($seriesId, 3, 'X-3', 'issued')""".update.run
            .transact(xa)
        // punch a MIDDLE hole: 1,_,3 → max(seq)=3 but count=2
        _      <- sql"DELETE FROM document_number WHERE series_id=$seriesId AND seq=2".update.run.transact(xa)
        broken <- runner.run("CTRL-DOC-GAPLESS", None)
      } yield expect(clean.toOption.exists(_.result == "pass")) and
        expect(broken.toOption.exists(o => o.result == "fail" && o.violations >= 1))
  }
}
