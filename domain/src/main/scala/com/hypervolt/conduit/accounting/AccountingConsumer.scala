package com.hypervolt.conduit.accounting

import java.time.LocalDate

// The swappable accounting/ERP boundary (doc 01 §4: TigerBeetle is the system of record; the accounting system
// is a downstream CONSUMER). Conduit emits invoice events; an AccountingConsumer turns them into the external
// system's invoices. Xero is today's implementation; swapping to another ERP is a new impl of this trait — no
// core change (doc 07 M13 gate). ERP-agnostic DTOs only; nothing Xero-shaped leaks across this boundary.

final case class InvoiceLine(
    description: String,
    sku: String,
    qty: Int,
    unitAmountExVat: BigDecimal,
    taxType: Option[String]
)

final case class InvoiceRequest(
    reference: String, // stable idempotency key (the Conduit invoice_no) — re-sends never duplicate
    invoiceNo: String,
    contactName: String, // the bill-to party
    currency: String,
    dueDate: LocalDate,
    lines: List[InvoiceLine]
)

trait AccountingConsumer[F[_]] {
  // Create (idempotently) the invoice in the external system; Right(externalInvoiceId) on success.
  def createInvoice(req: InvoiceRequest): F[Either[String, String]]
}
