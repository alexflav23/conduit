package com.hypervolt.conduit.consumer

import com.hypervolt.conduit.event.EventEnvelope
import java.nio.charset.StandardCharsets
import java.util.UUID
import weaver.FunSuite

// M13-Void.4 — the pure filter the void consumer runs: invoice.void_requested → (id, no, kind, reason, actor).
object InvoiceVoidConsumerSpec extends FunSuite {

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

  test("invoice.void_requested yields the full instruction") {
    val id = UUID.randomUUID()
    val payload =
      s"""{"order_invoice_id":"$id","invoice_no":"INV-1","kind":"refund","reason":"returned","requested_by":"u1"}"""
    expect(
      InvoiceVoidConsumer
        .voidRequested(env("invoice.void_requested", payload))
        .contains((id, "INV-1", "refund", "returned", "u1"))
    )
  }

  test("missing optional fields default; actor defaults to system") {
    val id      = UUID.randomUUID()
    val payload = s"""{"order_invoice_id":"$id","invoice_no":"INV-2"}"""
    expect(
      InvoiceVoidConsumer
        .voidRequested(env("invoice.void_requested", payload))
        .contains((id, "INV-2", "", "", "system"))
    )
  }

  test("a non-void event is ignored; malformed payload is ignored, never a throw") {
    val id      = UUID.randomUUID()
    val payload = s"""{"order_invoice_id":"$id","invoice_no":"X"}"""
    expect(InvoiceVoidConsumer.voidRequested(env("order.invoiced", payload)).isEmpty) and
      expect(InvoiceVoidConsumer.voidRequested(env("invoice.void_requested", "not json")).isEmpty) and
      expect(InvoiceVoidConsumer.voidRequested(env("invoice.void_requested", """{"invoice_no":"X"}""")).isEmpty)
  }
}
