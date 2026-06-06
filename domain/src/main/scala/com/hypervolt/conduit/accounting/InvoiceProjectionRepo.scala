package com.hypervolt.conduit.accounting

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate

// Builds the ERP-agnostic InvoiceRequest for a Conduit invoice_no from order_invoice + order + bill-to party +
// order lines (sku, qty, unit price ex VAT, tax regime). The Reference is the invoice_no, so the accounting
// consumer can be idempotent on it. Also stamps the external id back onto order_invoice.
object InvoiceProjectionRepo {

  private final case class Head(orderId: java.util.UUID, billTo: String, currency: String, dueDate: Option[LocalDate])

  def load(invoiceNo: String): ConnectionIO[Option[InvoiceRequest]] =
    head(invoiceNo).flatMap {
      case None => Option.empty[InvoiceRequest].pure[ConnectionIO]
      case Some(h) =>
        lines(h.orderId).map { ls =>
          Some(
            InvoiceRequest(
              reference = invoiceNo,
              invoiceNo = invoiceNo,
              contactName = h.billTo,
              currency = h.currency,
              dueDate = h.dueDate.getOrElse(LocalDate.now()), // the contractual due date set at dispatch
              lines = ls
            )
          )
        }
    }

  private def head(invoiceNo: String): ConnectionIO[Option[Head]] =
    sql"""SELECT o.id, COALESCE(p.legal_name, p.display_name), o.txn_currency, i.due_date
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id JOIN party p ON p.id = o.bill_to_party_id
          WHERE i.invoice_no = $invoiceNo"""
      .query[Head]
      .option

  private def lines(orderId: java.util.UUID): ConnectionIO[List[InvoiceLine]] =
    sql"""SELECT COALESCE(f.name, v.sku), v.sku, ol.qty, ol.unit_price_ex_vat, ol.tax_regime
          FROM order_line ol JOIN product_variant v ON v.id = ol.product_variant_id
            LEFT JOIN product_family f ON f.id = v.family_id
          WHERE ol.order_id = $orderId ORDER BY ol.id"""
      .query[(String, String, Int, BigDecimal, Option[String])]
      .to[List]
      .map(_.map { case (desc, sku, qty, price, regime) => InvoiceLine(desc, sku, qty, price, regime) })

  def setExternalId(invoiceNo: String, externalId: String): ConnectionIO[Int] =
    sql"UPDATE order_invoice SET xero_invoice_id = $externalId WHERE invoice_no = $invoiceNo".update.run
}
