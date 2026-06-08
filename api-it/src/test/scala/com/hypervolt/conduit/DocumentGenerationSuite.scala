package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.consumer.DocumentGenerationConsumer
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-Docs.4 — the document loop closes off the EVENT, not a direct call. Dispatch raises `order.invoiced`
// (ASC 606); that event must be self-describing (carry order_invoice_id) so the document-generation consumer
// can render the invoice PDF without a DB lookup. This feature test drives the real production extractor
// (DocumentGenerationConsumer.orderInvoiceId) off the real outbox row, then the real DocumentService
// (Apache FOP → WORM store), and asserts the artefact + idempotency.
object DocumentGenerationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val year       = LocalDate.now().getYear
  private val expectedNo = "HV-UK-INV-" + year + "-000001"

  // A fresh entity with an invoice series + GB/en template, a serialised product with two received+batched
  // serials, and a placed 2-unit order ready to dispatch. Returns (orderId, orderLineId, serials).
  private def orderReadyToDispatch(xa: HikariTransactor[IO]): IO[(UUID, UUID, List[String])] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'invoice','GB','HV-UK-INV','{series}-{yyyy}-{seq:06d}')".update.run
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
    } yield (ord, ol, serials)).transact(xa)

  // Reconstruct the EventEnvelope the consumer would receive off Pulsar, straight from the persisted outbox row.
  private def invoicedEnvelope(xa: HikariTransactor[IO], orderId: UUID): IO[EventEnvelope] =
    sql"""SELECT event_id::text, event_type, aggregate_type, aggregate_id::text, partition_key, payload::text
          FROM outbox_event WHERE event_type = 'order.invoiced' AND aggregate_id = $orderId ORDER BY created_at DESC LIMIT 1"""
      .query[(String, String, String, String, String, String)]
      .unique
      .transact(xa)
      .map {
        case (id, tpe, aggT, aggId, pk, payload) =>
          EventEnvelope(
            id,
            tpe,
            1,
            aggT,
            aggId,
            pk,
            None,
            None,
            None,
            "system:relay",
            0L,
            payload.getBytes(StandardCharsets.UTF_8)
          )
      }

  test(
    "dispatch raises a self-describing order.invoiced; the consumer extractor + DocumentService render the PDF, idempotently"
  ) { xa =>
    val disp = new DispatchService[IO](xa)
    for {
      storage <- DocumentStorage.inMemory[IO]
      docs = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
      s <- orderReadyToDispatch(xa)
      (ord, ol, serials) = s
      _   <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      env <- invoicedEnvelope(xa, ord)
      // the actual invoice id off the DB, to prove the event carried the right one
      actualInv <- sql"SELECT id FROM order_invoice WHERE order_id = $ord".query[UUID].unique.transact(xa)
      extracted = DocumentGenerationConsumer.orderInvoiceId(env)
      r1 <- extracted.traverse(docs.generateInvoice) // what the consumer does on first delivery
      r2 <- extracted.traverse(docs.generateInvoice) // redelivery — must mint nothing new
      finalised <-
        sql"SELECT status, content_sha256, storage_uri FROM document WHERE order_invoice_id = $actualInv"
          .query[(String, String, String)]
          .to[List]
          .transact(xa)
      pdf <- storage.get(finalised.head._3)
    } yield expect(extracted.contains(actualInv)) and // event was self-describing + correct
      expect(r1.flatMap(_.toOption).map(_.formattedNumber).contains(expectedNo)) and
      expect(
        r1.flatMap(_.toOption).map(_.contentSha256) == r2.flatMap(_.toOption).map(_.contentSha256)
      ) and                                                               // idempotent
      expect(finalised.size == 1 && finalised.head._1 == "finalised") and // exactly one WORM document
      expect(new String(pdf.take(5), "US-ASCII") == "%PDF-")              // the real Apache FOP artefact
  }

  test("a non-invoiced event (dispatch.created) drives no document generation") { xa =>
    for {
      s <- orderReadyToDispatch(xa)
      (ord, ol, serials) = s
      _ <- new DispatchService[IO](xa)
        .dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials)))
        .map(_.toOption.get)
      dispatchEnv <-
        sql"""SELECT event_id::text, event_type, aggregate_type, aggregate_id::text, partition_key, payload::text
                FROM outbox_event WHERE event_type = 'dispatch.created' AND aggregate_id = $ord ORDER BY created_at DESC LIMIT 1"""
          .query[(String, String, String, String, String, String)]
          .unique
          .transact(xa)
          .map {
            case (id, tpe, aggT, aggId, pk, p) =>
              EventEnvelope(id, tpe, 1, aggT, aggId, pk, None, None, None, "x", 0L, p.getBytes(StandardCharsets.UTF_8))
          }
    } yield expect(DocumentGenerationConsumer.orderInvoiceId(dispatchEnv).isEmpty)
  }
}
