package com.hypervolt.conduit.revenue

import java.nio.charset.StandardCharsets
import java.util.UUID

// The correlation id that threads one invoice-invalidation cycle (doc 13 §void): the `invoice.void_requested`,
// `invoice.voided`, the credit-note `document.issued`, and the refund `payment.received` all carry this id, so the
// collection ledger shows them as one causal thread — not events you have to reassemble by hand. Deterministic
// from the invoice (it equals the reversal id, which is also the invoice.voided event id), so every emit site can
// compute it independently without plumbing state through.
object CollectionCycle {
  def correlationId(orderInvoiceId: UUID): UUID =
    UUID.nameUUIDFromBytes(s"invoice-void:$orderInvoiceId".getBytes(StandardCharsets.UTF_8))
}
