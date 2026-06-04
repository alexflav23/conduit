package com.hypervolt.conduit.revenue

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The immutable-log read surface: recognised revenue with the TigerBeetle transfer ids that posted it. Each
// recognition row is written atomically with the ledger post (doc 04 §Ledger), so its amounts + transfer ids
// ARE the proof — a money figure on the desk traces to these transfers, not to a spreadsheet.
object RevenueQueryRepo {

  def recognitions(market: UUID, period: LocalDate): ConnectionIO[List[Json]] =
    sql"""SELECT rr.dispatch_id, rr.invoice_no, rr.currency, rr.revenue_ex_vat, rr.vat, rr.cogs, rr.gross_margin,
                 rr.ar_transfer_id::text, rr.vat_transfer_id::text, rr.cogs_transfer_id::text, rr.recognized_at
          FROM revenue_recognition rr JOIN "order" o ON o.id = rr.order_id
          WHERE o.market_id = $market AND date_trunc('month', rr.recognized_at)::date = $period
          ORDER BY rr.recognized_at DESC"""
      .query[
        (
            UUID,
            Option[String],
            String,
            BigDecimal,
            BigDecimal,
            BigDecimal,
            BigDecimal,
            Option[String],
            Option[String],
            Option[String],
            Instant
        )
      ]
      .to[List]
      .map(_.map {
        case (dispatch, inv, ccy, rev, vat, cogs, gm, arT, vatT, cogsT, at) =>
          Json.obj(
            "dispatch_id"      -> dispatch.toString.asJson,
            "invoice_no"       -> inv.asJson,
            "currency"         -> ccy.asJson,
            "revenue_ex_vat"   -> rev.toString.asJson,
            "vat"              -> vat.toString.asJson,
            "cogs"             -> cogs.toString.asJson,
            "gross_margin"     -> gm.toString.asJson,
            "ar_transfer_id"   -> arT.asJson,
            "vat_transfer_id"  -> vatT.asJson,
            "cogs_transfer_id" -> cogsT.asJson,
            "recognized_at"    -> at.toString.asJson
          )
      })

  // Period totals (the headline the recognitions roll up to).
  def totals(market: UUID, period: LocalDate): ConnectionIO[Json] =
    sql"""SELECT COALESCE(SUM(rr.revenue_ex_vat),0), COALESCE(SUM(rr.vat),0), COALESCE(SUM(rr.cogs),0), COALESCE(SUM(rr.gross_margin),0)
          FROM revenue_recognition rr JOIN "order" o ON o.id = rr.order_id
          WHERE o.market_id = $market AND date_trunc('month', rr.recognized_at)::date = $period"""
      .query[(BigDecimal, BigDecimal, BigDecimal, BigDecimal)]
      .unique
      .map {
        case (rev, vat, cogs, gm) =>
          Json.obj(
            "revenue_ex_vat" -> rev.toString.asJson,
            "vat"            -> vat.toString.asJson,
            "cogs"           -> cogs.toString.asJson,
            "gross_margin"   -> gm.toString.asJson
          )
      }
}
