package com.hypervolt.conduit.consumer

import com.hypervolt.conduit.event.EventEnvelope
import java.nio.charset.StandardCharsets
import java.util.UUID
import weaver.FunSuite

// M13-Docs.4 — the pure event filter the document-generation consumer runs (unit-testable without Pulsar,
// matching the house pattern for XeroInvoiceConsumer/RevenueRecognitionConsumer). Only `order.invoiced` with a
// resolvable order_invoice_id should trigger generation; everything else is ignored.
object DocumentGenerationConsumerSpec extends FunSuite {

  private def env(eventType: String, payload: String): EventEnvelope =
    EventEnvelope(
      event_id = UUID.randomUUID().toString,
      event_type = eventType,
      schema_version = 1,
      aggregate_type = "order",
      aggregate_id = UUID.randomUUID().toString,
      partition_key = "k",
      scope = None,
      correlation_id = None,
      causation_id = None,
      actor = "test",
      occurred_at = 0L,
      payload = payload.getBytes(StandardCharsets.UTF_8)
    )

  test("order.invoiced with an order_invoice_id yields that id") {
    val id = UUID.randomUUID()
    val e  = env("order.invoiced", s"""{"invoice_no":"INV-1","order_invoice_id":"$id","tranche_id":null}""")
    expect(DocumentGenerationConsumer.orderInvoiceId(e).contains(id))
  }

  test("a non-invoiced event type is ignored") {
    val e = env("dispatch.created", s"""{"order_invoice_id":"${UUID.randomUUID()}"}""")
    expect(DocumentGenerationConsumer.orderInvoiceId(e).isEmpty)
  }

  test("order.invoiced without an order_invoice_id is ignored (the consumer logs + skips, never throws)") {
    val e = env("order.invoiced", """{"invoice_no":"INV-9"}""")
    expect(DocumentGenerationConsumer.orderInvoiceId(e).isEmpty)
  }

  test("a malformed payload is ignored, never a throw") {
    expect(DocumentGenerationConsumer.orderInvoiceId(env("order.invoiced", "not json")).isEmpty) and
      expect(
        DocumentGenerationConsumer
          .orderInvoiceId(env("order.invoiced", """{"order_invoice_id":"not-a-uuid"}"""))
          .isEmpty
      )
  }
}
