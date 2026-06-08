package com.hypervolt.conduit.payment

import weaver.FunSuite

// M13-Pay.2 — the Stripe webhook parser (ported boundary). Maps Stripe's event envelope to the typed ledger
// instruction; only charge/payout events move the ledger, everything else is Ignored (so Stripe stops retrying);
// a charge with no metadata.invoice_no is rejected (we can't settle AR without knowing the invoice).
object StripeWebhookSpec extends FunSuite {

  private def piBody(invoiceNo: String, amountMinor: Long, ccy: String): String =
    s"""{"id":"evt_1","type":"payment_intent.succeeded","data":{"object":{
       |  "id":"pi_123","object":"payment_intent","amount_received":$amountMinor,"currency":"$ccy",
       |  "metadata":{"invoice_no":"$invoiceNo"}}}}""".stripMargin

  test("payment_intent.succeeded → PaymentSucceeded with major-unit amount and upper-cased currency") {
    StripeWebhook.parse(piBody("INV-1", 120000L, "gbp")) match {
      case Right(StripeEvent.PaymentSucceeded(eid, pi, no, amt, ccy)) =>
        expect(eid == "evt_1") and expect(pi == "pi_123") and expect(no == "INV-1") and
          expect(amt == BigDecimal("1200.00")) and expect(ccy == "GBP")
      case other => failure(s"expected PaymentSucceeded, got $other")
    }
  }

  test("payout.paid → PayoutPaid: gross = net deposited + fee (clearing is relieved by gross)") {
    val body =
      """{"id":"evt_2","type":"payout.paid","data":{"object":{
        |  "id":"po_9","object":"payout","amount":97100,"currency":"gbp",
        |  "metadata":{"entity_id":"11111111-1111-1111-1111-111111111111","fee":2900}}}}""".stripMargin
    StripeWebhook.parse(body) match {
      case Right(StripeEvent.PayoutPaid(_, po, ent, ccy, gross, fee)) =>
        expect(po == "po_9") and expect(ent.toString == "11111111-1111-1111-1111-111111111111") and
          expect(ccy == "GBP") and expect(gross == BigDecimal("1000.00")) and expect(fee == BigDecimal("29.00"))
      case other => failure(s"expected PayoutPaid, got $other")
    }
  }

  test("unsupported event type is Ignored, not an error (Stripe must stop retrying)") {
    StripeWebhook.parse("""{"id":"evt_3","type":"customer.created","data":{"object":{}}}""") match {
      case Right(StripeEvent.Ignored(_, t)) => expect(t == "customer.created")
      case other                            => failure(s"expected Ignored, got $other")
    }
  }

  test("a charge with no invoice_no is rejected — we cannot settle AR blindly") {
    val body =
      """{"id":"evt_4","type":"payment_intent.succeeded","data":{"object":{
        |  "id":"pi_x","amount_received":1000,"currency":"gbp","metadata":{}}}}""".stripMargin
    expect(StripeWebhook.parse(body).isLeft)
  }

  test("malformed json is a Left, never a throw") {
    expect(StripeWebhook.parse("not json").isLeft)
  }
}
