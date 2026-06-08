package com.hypervolt.conduit.payment

import doobie._
import doobie.implicits._
import io.circe.Json
import io.circe.syntax._

// Order→cash analytics off the data Conduit now owns (doc 13 §payments). AR aging buckets the OUTSTANDING
// balance (invoice total − allocations) by how overdue it is; DSO is the realised days-to-pay. This is the
// statistical value of holding payment status in Conduit rather than only in the ERP.
object PaymentQueryRepo {

  def arAging(currency: Option[String]): ConnectionIO[List[Json]] = {
    val filt = currency.fold(Fragment.empty)(c => fr"AND o.txn_currency = $c")
    (fr"""SELECT bucket, COALESCE(SUM(outstanding),0), COUNT(*) FROM (
            SELECT i.id,
              i.total_inc_vat - COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.order_invoice_id = i.id), 0) AS outstanding,
              CASE WHEN i.due_date IS NULL OR i.due_date >= current_date THEN 'current'
                   WHEN current_date - i.due_date <= 30 THEN '1-30'
                   WHEN current_date - i.due_date <= 60 THEN '31-60'
                   WHEN current_date - i.due_date <= 90 THEN '61-90'
                   ELSE '90+' END AS bucket
            FROM order_invoice i JOIN "order" o ON o.id = i.order_id
            WHERE i.status IN ('open','part_paid') """ ++ filt ++ fr""") x
          WHERE outstanding > 0 GROUP BY bucket ORDER BY bucket""")
      .query[(String, BigDecimal, Long)]
      .to[List]
      .map(_.map {
        case (bucket, amt, n) =>
          Json.obj("bucket" -> bucket.asJson, "outstanding" -> amt.asJson, "invoices" -> n.asJson)
      })
  }

  // Days Sales Outstanding (realised): mean days from invoice issue to full payment, over paid invoices.
  def dso(currency: Option[String]): ConnectionIO[Json] = {
    val filt = currency.fold(Fragment.empty)(c => fr"AND o.txn_currency = $c")
    (fr"""SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (i.paid_at - i.issued_at)) / 86400), 0), COUNT(*)
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id
          WHERE i.status = 'paid' AND i.paid_at IS NOT NULL """ ++ filt)
      .query[(BigDecimal, Long)]
      .unique
      .map {
        case (days, n) =>
          Json.obj("dso_days" -> days.setScale(1, BigDecimal.RoundingMode.HALF_UP).asJson, "paid_invoices" -> n.asJson)
      }
  }
}
