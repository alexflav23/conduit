package com.hypervolt.conduit.ingest

import cats.effect.IO
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import weaver.SimpleIOSuite

// M-Ingest slice 2 (spec doc 33 §4): the Xero connector's parse + cursor, fixture-driven (no live OAuth). A fake
// XeroApi returns canned Accounting-API bodies; the connector must lift each row to an IngestRecord keyed on the
// Xero id, advance the cursor to the page's max UpdatedDateUTC, and signal more-pages when the page is full.
object XeroConnectorSuite extends SimpleIOSuite {

  private def fakeApi(byEndpoint: Map[String, Json]): XeroApi[IO] =
    (endpoint: String, _: Option[String]) => IO.pure(byEndpoint.getOrElse(endpoint, Json.obj()))

  private val invoicesBody = parseJson("""
    {"Invoices":[
      {"InvoiceID":"inv-1","InvoiceNumber":"INV-001","Total":1200.00,"AmountDue":1200.00,"Status":"AUTHORISED","UpdatedDateUTC":"2026-06-01T10:00:00Z"},
      {"InvoiceID":"inv-2","InvoiceNumber":"INV-002","Total":600.00,"AmountDue":0.00,"Status":"PAID","UpdatedDateUTC":"2026-06-03T12:30:00Z"}
    ]}""").toOption.get

  test(
    "pullSince(invoices) lifts each Xero invoice to an IngestRecord and advances the cursor to the max UpdatedDateUTC"
  ) {
    val conn = new XeroConnector[IO](fakeApi(Map("Invoices" -> invoicesBody)))
    conn.pullSince("invoices", None).map { batch =>
      expect(batch.records.map(_.sourceId) == List("inv-1", "inv-2")) and
        expect(batch.records.forall(_.dataset == "invoices")) and
        expect(batch.records.head.payload.hcursor.get[String]("InvoiceNumber").toOption.contains("INV-001")) and
        expect(batch.nextCursor.contains(SyncCursor("2026-06-03T12:30:00Z"))) and
        expect(batch.complete) // 2 rows < page size ⇒ drained
    }
  }

  test("an empty page yields no records and no cursor advance (the runner holds the prior cursor)") {
    val conn = new XeroConnector[IO](fakeApi(Map.empty))
    conn.pullSince("contacts", Some(SyncCursor("2026-06-01T00:00:00Z"))).map { batch =>
      expect(batch.records.isEmpty) and expect(batch.nextCursor.isEmpty) and expect(batch.complete)
    }
  }

  test("datasets cover invoices/contacts/payments and an unknown dataset is rejected") {
    val conn = new XeroConnector[IO](fakeApi(Map.empty))
    conn.pullSince("widgets", None).attempt.map { e =>
      expect(conn.source == "xero") and
        expect(conn.datasets == List("contacts", "invoices", "payments")) and
        expect(e.isLeft)
    }
  }
}
