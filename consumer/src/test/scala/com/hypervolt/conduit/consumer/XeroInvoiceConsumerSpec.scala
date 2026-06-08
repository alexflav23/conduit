package com.hypervolt.conduit.consumer

import com.hypervolt.conduit.event.EventEnvelope
import java.nio.charset.StandardCharsets
import java.util.UUID
import weaver.FunSuite

// M13-Void.3 — the pure event filters the Xero consumer runs. order.invoiced → push; invoice.voided → void the
// ERP invoice; anything else is ignored.
object XeroInvoiceConsumerSpec extends FunSuite {

  private def env(eventType: String, payload: String): EventEnvelope =
    EventEnvelope(
      UUID.randomUUID().toString,
      eventType,
      1,
      "order",
      UUID.randomUUID().toString,
      "k",
      None,
      None,
      None,
      "test",
      0L,
      payload.getBytes(StandardCharsets.UTF_8)
    )

  test("order.invoiced yields the invoice_no; invoice.voided does not") {
    expect(
      XeroInvoiceConsumer.extractInvoiceNo(env("order.invoiced", """{"invoice_no":"INV-1"}""")).contains("INV-1")
    ) and
      expect(XeroInvoiceConsumer.extractInvoiceNo(env("invoice.voided", """{"invoice_no":"INV-1"}""")).isEmpty)
  }

  test("invoice.voided yields (invoice_no, reason)") {
    val r = XeroInvoiceConsumer.extractVoid(env("invoice.voided", """{"invoice_no":"INV-9","reason":"cancelled"}"""))
    expect(r.contains(("INV-9", "cancelled")))
  }

  test("invoice.voided with no reason still yields the invoice_no; order.invoiced is not a void") {
    expect(
      XeroInvoiceConsumer.extractVoid(env("invoice.voided", """{"invoice_no":"INV-2"}""")).contains(("INV-2", ""))
    ) and
      expect(XeroInvoiceConsumer.extractVoid(env("order.invoiced", """{"invoice_no":"INV-2"}""")).isEmpty)
  }

  test("a void with no invoice_no is ignored, never a throw") {
    expect(XeroInvoiceConsumer.extractVoid(env("invoice.voided", "{}")).isEmpty) and
      expect(XeroInvoiceConsumer.extractVoid(env("invoice.voided", "not json")).isEmpty)
  }
}
