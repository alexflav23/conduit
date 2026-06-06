package com.hypervolt.conduit.credit

import doobie._
import doobie.implicits._
import io.circe.Json
import io.circe.syntax._

// Cash waterfall (doc 14 §AR): expected cash from OPEN invoices bucketed by contractual due-date month + currency.
// Because each invoice's due date is its bill-to contact's terms (set at dispatch), differing customer terms fall
// into the right buckets — the waterfall reflects the real contractual collection schedule.
object CashWaterfallRepo {

  def waterfall(currency: Option[String]): ConnectionIO[List[Json]] = {
    val base = fr"""SELECT to_char(i.due_date, 'YYYY-MM') AS bucket, o.txn_currency,
                           COALESCE(SUM(i.total_inc_vat), 0), COUNT(*)
                    FROM order_invoice i JOIN "order" o ON o.id = i.order_id
                    WHERE i.status = 'open' AND i.due_date IS NOT NULL"""
    val filt = currency.fold(Fragment.empty)(c => fr"AND o.txn_currency = $c")
    (base ++ filt ++ fr"GROUP BY 1, 2 ORDER BY 1, 2")
      .query[(String, String, BigDecimal, Long)]
      .to[List]
      .map(_.map {
        case (bucket, ccy, total, n) =>
          Json.obj(
            "due_month"     -> bucket.asJson,
            "currency"      -> ccy.asJson,
            "expected_cash" -> total.asJson,
            "invoices"      -> n.asJson
          )
      })
  }
}
