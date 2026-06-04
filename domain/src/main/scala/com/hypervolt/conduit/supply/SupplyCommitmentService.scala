package com.hypervolt.conduit.supply

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

// The classification of a proposed forward-demand change for a SKU/week against the manufacturer's gates.
final case class CommitmentAssessment(
    zone: String,
    committed: Int,
    requested: Int,
    maxIncrease: Int,
    maxDecrease: Int,
    admissible: Boolean
)

// Manages the firm-commitment window with a contract manufacturer (doc 12 buy-side; forecasting guide §1/§4).
// Forward demand from H6Q is committed into firm POs per SKU per week, gated by the time fence: a frozen-window
// change is blocked (it would breach the firm PO and incur penalty); a flex-window change is allowed within
// tolerance; a free-window change is unconstrained. `assess` answers, in real time, how far a forecast can still
// move for any week — the refined mechanism for continuous forecasting.
final class SupplyCommitmentService[F[_]: Async](xa: Transactor[F]) {

  def assess(
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      asOf: LocalDate,
      requested: Int
  ): F[CommitmentAssessment] =
    (policyFor(supplier), committed(supplier, variant, target)).tupled.transact(xa).map {
      case (p, c) =>
        val hr = TimeFence.headroom(asOf, target, p, c)
        CommitmentAssessment(hr.zone.name, c, requested, hr.maxIncrease, hr.maxDecrease, hr.admits(c, requested))
    }

  // Place/adjust the firm PO for a SKU/week. Admissible-by-the-fence unless `force` (an escalated, liability-
  // bearing override — the >20% executive approval path, guide §4). Emits supply.commitment.* for the CM feed.
  def commit(
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      qty: Int,
      asOf: LocalDate,
      force: Boolean
  ): F[Either[String, CommitmentAssessment]] =
    (policyFor(supplier), committed(supplier, variant, target)).tupled.transact(xa).flatMap {
      case (p, c) =>
        val hr         = TimeFence.headroom(asOf, target, p, c)
        val assessment = CommitmentAssessment(hr.zone.name, c, qty, hr.maxIncrease, hr.maxDecrease, hr.admits(c, qty))
        if (!assessment.admissible && !force)
          (if (hr.zone == TimeFence.Zone.Frozen) "frozen_window" else "exceeds_flex_tolerance")
            .asLeft[CommitmentAssessment]
            .pure[F]
        else
          (upsert(supplier, variant, target, qty, hr.zone.name) *>
            OutboxRepo.append(event(supplier, variant, target, qty, hr.zone.name, c, force)))
            .transact(xa)
            .as(assessment.asRight[String])
    }

  // ----- internals -----

  private def policyFor(supplier: UUID): ConnectionIO[TimeFence.Policy] =
    sql"""SELECT lead_time_days, flex_horizon_days, flex_tolerance_pct, frozen_tolerance_pct FROM supply_commitment_policy
          WHERE active AND (supplier_id = $supplier OR supplier_id IS NULL)
          ORDER BY (supplier_id IS NOT NULL) DESC LIMIT 1"""
      .query[(Int, Int, BigDecimal, BigDecimal)]
      .option
      .map(_.fold(TimeFence.Policy(56, 180, BigDecimal(20), BigDecimal(0))) {
        case (lt, fh, tol, ftol) => TimeFence.Policy(lt, fh, tol, ftol)
      })

  // Warn (don't silently reject) when sales reality or an automated trigger diverges from a FROZEN/over-flex firm
  // PO: the PO can't move, so a divergence means we will over- or under-supply. Records a commitment_warning +
  // emits supply.commitment.divergence; returns the message if raised. Called from the forecast/order paths.
  def checkDemand(
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      asOf: LocalDate,
      demand: Int,
      source: String
  ): F[Option[String]] =
    (policyFor(supplier), committed(supplier, variant, target)).tupled
      .flatMap {
        case (p, c) =>
          val hr = TimeFence.headroom(asOf, target, p, c)
          if (hr.zone != TimeFence.Zone.Free && c > 0 && !hr.admits(c, demand)) {
            val delta = demand - c
            val sev   = if (hr.zone == TimeFence.Zone.Frozen) "block" else "warn"
            val msg =
              s"$source demand $demand diverges from the ${hr.zone.name} firm PO of $c (delta $delta) for $target"
            (sql"""INSERT INTO commitment_warning (supplier_id, product_variant_id, target_date, zone, committed_qty, demand_qty, delta, source, severity, message)
                 VALUES ($supplier, $variant, $target, ${hr.zone.name}, $c, $demand, $delta, $source, $sev, $msg)""".update.run *>
              OutboxRepo.append(
                OutboxEvent(
                  UUID.randomUUID(),
                  "supply.commitment.divergence",
                  1,
                  "supply",
                  supplier,
                  s"$supplier:$variant:$target",
                  None,
                  None,
                  None,
                  Json.obj(
                    "supplier_id"        -> supplier.toString.asJson,
                    "product_variant_id" -> variant.toString.asJson,
                    "target_date"        -> target.toString.asJson,
                    "zone"               -> hr.zone.name.asJson,
                    "committed"          -> c.asJson,
                    "demand"             -> demand.asJson,
                    "delta"              -> delta.asJson,
                    "source"             -> source.asJson,
                    "severity"           -> sev.asJson
                  ),
                  Instant.now()
                )
              )).as(Some(msg): Option[String])
          } else (None: Option[String]).pure[ConnectionIO]
      }
      .transact(xa)

  private def committed(supplier: UUID, variant: UUID, target: LocalDate): ConnectionIO[Int] =
    sql"SELECT qty FROM supply_commitment WHERE supplier_id = $supplier AND product_variant_id = $variant AND target_date = $target"
      .query[Int]
      .option
      .map(_.getOrElse(0))

  private def upsert(supplier: UUID, variant: UUID, target: LocalDate, qty: Int, zone: String): ConnectionIO[Int] =
    sql"""INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone)
          VALUES ($supplier, $variant, $target, $qty, $zone)
          ON CONFLICT (supplier_id, product_variant_id, target_date)
          DO UPDATE SET qty = EXCLUDED.qty, zone = EXCLUDED.zone, updated_at = now()""".update.run

  private def event(
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      qty: Int,
      zone: String,
      prior: Int,
      force: Boolean
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "supply.commitment.placed",
      1,
      "supply",
      supplier,
      s"$supplier:$variant:$target",
      None,
      None,
      None,
      Json.obj(
        "supplier_id"        -> supplier.toString.asJson,
        "product_variant_id" -> variant.toString.asJson,
        "target_date"        -> target.toString.asJson,
        "qty"                -> qty.asJson,
        "prior_qty"          -> prior.asJson,
        "zone"               -> zone.asJson,
        "forced"             -> force.asJson
      ),
      Instant.now()
    )
}
