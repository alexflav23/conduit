package com.hypervolt.conduit.purchasing

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The rolling commitment ladder (M9c — the upgrade to the 10-week CM update ceremony): generated from the
// LIVE forecast (forecast_entry, current model rows), zoned firm (the contractual locked window) / flex
// (±tolerance) / indicative (the horizon), and ISSUED ON SIGNAL — a new version only when the forecast has
// moved more than the deviation threshold against what was last communicated, with the contractual calendar
// as the backstop. Every issued ladder is a versioned, immutable row set (what-was-promised-when is always
// reconstructable) and emits purchasing.commitment.issued for the document engine.
final class CommitmentService[F[_]: Async](xa: Transactor[F]) {

  def generate(
      supplier: UUID,
      asOf: LocalDate,
      firmWeeks: Int = 10,
      flexWeeks: Int = 10,
      horizonMonths: Int = 9,
      tolerancePct: BigDecimal = 20,
      deviationThresholdPct: BigDecimal = 10,
      force: Boolean = false
  ): F[Option[UUID]] = {
    val firmUntil = asOf.plusWeeks(firmWeeks.toLong)
    val flexUntil = firmUntil.plusWeeks(flexWeeks.toLong)
    val tx: ConnectionIO[Option[UUID]] = for {
      demand <- monthlyDemand(asOf, asOf.plusMonths(horizonMonths.toLong))
      last   <- latestLines(supplier)
      deviation = maxDeviation(demand, last, flexUntil)
      issue     = force || last.isEmpty || deviation > deviationThresholdPct
      result <-
        if (!issue) Option.empty[UUID].pure[ConnectionIO]
        else
          for {
            version <- sql"""SELECT COALESCE(MAX(version), 0) + 1 FROM cm_commitment
                             WHERE supplier_id = $supplier""".query[Int].unique
            reason = if (force || last.isEmpty) "calendar" else "forecast_deviation"
            id <- sql"""INSERT INTO cm_commitment (supplier_id, version, firm_until, flex_until, tolerance_pct, reason)
                        VALUES ($supplier, $version, $firmUntil, $flexUntil, $tolerancePct, $reason)
                        RETURNING id""".query[UUID].unique
            _ <- demand.toList.traverse_ {
              case ((variant, month), qty) =>
                val zone =
                  if (month.isBefore(firmUntil)) "firm"
                  else if (month.isBefore(flexUntil)) "flex"
                  else "indicative"
                sql"""INSERT INTO cm_commitment_line (commitment_id, product_variant_id, period_month, qty, zone)
                      VALUES ($id, $variant, $month, $qty, $zone)""".update.run
            }
            _ <- OutboxRepo.append(
              OutboxEvent(
                UUID.randomUUID(),
                "purchasing.commitment.issued",
                1,
                "purchasing",
                supplier,
                supplier.toString,
                None,
                None,
                None,
                Json.obj(
                  "supplier_id"   -> supplier.toString.asJson,
                  "commitment_id" -> id.toString.asJson,
                  "version"       -> version.asJson,
                  "reason"        -> reason.asJson,
                  "firm_until"    -> firmUntil.toString.asJson,
                  "deviation_pct" -> deviation.setScale(1, BigDecimal.RoundingMode.HALF_UP).toString.asJson
                ),
                Instant.now(),
                "service:purchasing"
              )
            )
          } yield Some(id)
    } yield result
    tx.transact(xa)
  }

  // total demand by variant×month from the CURRENT live model forecasts (the H6Q spine rows)
  private def monthlyDemand(from: LocalDate, until: LocalDate): ConnectionIO[Map[(UUID, LocalDate), BigDecimal]] =
    sql"""SELECT product_variant_id, period_month, SUM(qty)::numeric
          FROM forecast_entry
          WHERE source = 'model' AND superseded_by IS NULL
            AND period_month >= $from AND period_month < $until
          GROUP BY 1, 2"""
      .query[(UUID, LocalDate, BigDecimal)]
      .to[List]
      .map(_.map { case (v, m, q) => (v, m) -> q }.toMap)

  private def latestLines(supplier: UUID): ConnectionIO[Map[(UUID, LocalDate), BigDecimal]] =
    sql"""SELECT l.product_variant_id, l.period_month, l.qty
          FROM cm_commitment_line l
          JOIN cm_commitment c ON c.id = l.commitment_id
          WHERE c.supplier_id = $supplier
            AND c.version = (SELECT MAX(version) FROM cm_commitment WHERE supplier_id = $supplier)
            AND l.zone IN ('firm', 'flex')"""
      .query[(UUID, LocalDate, BigDecimal)]
      .to[List]
      .map(_.map { case (v, m, q) => (v, m) -> q }.toMap)

  // the signal: the largest relative move on any firm/flex bucket vs what was last communicated
  private def maxDeviation(
      now: Map[(UUID, LocalDate), BigDecimal],
      last: Map[(UUID, LocalDate), BigDecimal],
      flexUntil: LocalDate
  ): BigDecimal =
    last.toList
      .map {
        case (key @ (_, month), prev) if month.isBefore(flexUntil) && prev > 0 =>
          (now.getOrElse(key, BigDecimal(0)) - prev).abs / prev * 100
        case _ => BigDecimal(0)
      }
      .maxOption
      .getOrElse(BigDecimal(0))
}
