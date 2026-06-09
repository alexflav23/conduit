package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Revenue as a PROJECTION over the unit forecast (doc 26 §2 — H6Q still doesn't own money): the account's current
// model forecast (forecast_entry source='model') priced through the M-Pricing engine — the customer's authorized
// tier — net of the EXPECTED per-unit retrospective rebate (entry tier − tier at the contract commitment). The
// revenue forecast is contract-consistent by construction: it can never quote a price the customer wouldn't pay.
final class RevenueProjectionService[F[_]: Async](xa: Transactor[F]) {

  def project(company: UUID, channel: UUID, market: UUID, currency: String, asOf: Instant): F[Json] =
    program(company, channel, market, currency, asOf).transact(xa)

  private def program(company: UUID, channel: UUID, market: UUID, currency: String, asOf: Instant): ConnectionIO[Json] =
    currentModelForecast(company).flatMap {
      _.traverse {
        case (variant, cls, period, qty) =>
          RevenueProjection
            .priceAndRebate(company, variant, cls, channel, market, currency, qty.toInt.max(1), asOf)
            .map {
              case (price, rebate) =>
                val net     = (price.getOrElse(BigDecimal(0)) - rebate).max(BigDecimal(0))
                val revenue = (net * qty).setScale(2, RoundingMode.HALF_UP)
                Json.obj(
                  "product_variant_id" -> variant.toString.asJson,
                  "period_month"       -> period.toString.asJson,
                  "forecast_qty"       -> qty.toString.asJson,
                  "unit_price"         -> price.map(_.toString).asJson,
                  "expected_rebate_pu" -> rebate.toString.asJson,
                  "forecast_revenue"   -> revenue.toString.asJson
                ) -> revenue
            }
      }.map { lines =>
        Json.obj(
          "company_id" -> company.toString.asJson,
          "currency"   -> currency.asJson,
          "lines"      -> Json.fromValues(lines.map(_._1)),
          "total"      -> lines.map(_._2).sum.toString.asJson
        )
      }
    }

  private def currentModelForecast(company: UUID): ConnectionIO[List[(UUID, String, LocalDate, BigDecimal)]] =
    sql"""SELECT fe.product_variant_id, pv.product_class, fe.period_month, fe.qty::numeric
          FROM forecast_entry fe JOIN product_variant pv ON pv.id = fe.product_variant_id
          WHERE fe.company_id = $company AND fe.source = 'model' AND fe.superseded_by IS NULL
            AND fe.product_variant_id IS NOT NULL
          ORDER BY fe.period_month, fe.product_variant_id"""
      .query[(UUID, String, LocalDate, BigDecimal)]
      .to[List]

}
