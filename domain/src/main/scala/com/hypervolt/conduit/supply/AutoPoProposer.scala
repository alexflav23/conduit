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

final case class PoProposal(
    variant: UUID,
    demand: Int,
    committed: Int,
    available: Int,
    netNeed: Int,
    proposedDelta: Int,
    blocked: Int,
    zone: String
)

// Closes the loop to real-time. For each SKU with H6Q forward demand, net need = demand − firm commitment −
// available finished goods (real-time on hand); the proposer auto-proposes a PO delta ONLY within the time-fence
// headroom (the movable window). Whatever falls beyond the gate is `blocked` and raises a divergence warning —
// it cannot be auto-committed to a frozen/over-flex PO. So as forecasting goes continuous, every demand shift
// turns into a proposed PO within what's still movable, with the rest escalated, automatically.
final class AutoPoProposer[F[_]: Async](xa: Transactor[F], commitments: SupplyCommitmentService[F]) {

  def propose(supplier: UUID, market: UUID, period: LocalDate, scenario: UUID, asOf: LocalDate): F[List[PoProposal]] =
    skuDemand(market, period, scenario).transact(xa).flatMap { rows =>
      rows
        .traverse {
          case (variant, demand) =>
            for {
              available  <- SerialShelfRepo.onHand(variant).transact(xa)
              assessment <- commitments.assess(supplier, variant, period, asOf, demand)
              netNeed  = math.max(demand - assessment.committed - available, 0)
              proposed = math.min(netNeed, assessment.maxIncrease)
              blocked  = netNeed - proposed
              _ <- record(
                supplier,
                variant,
                period,
                demand,
                assessment.committed,
                available,
                netNeed,
                proposed,
                blocked,
                assessment.zone
              )
              _ <-
                if (blocked > 0) commitments.checkDemand(supplier, variant, period, asOf, demand, "automated").void
                else ().pure[F]
            } yield PoProposal(
              variant,
              demand,
              assessment.committed,
              available,
              netNeed,
              proposed,
              blocked,
              assessment.zone
            )
        }
        .map(_.filter(_.netNeed > 0))
    }

  private def skuDemand(market: UUID, period: LocalDate, scenario: UUID): ConnectionIO[List[(UUID, Int)]] =
    sql"""SELECT product_variant_id, forecast_qty FROM pipeline_coverage
          WHERE market_id = $market AND period_month = $period AND scenario_id = $scenario
            AND level = 'market' AND product_variant_id IS NOT NULL"""
      .query[(UUID, Int)]
      .to[List]

  private def record(
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      demand: Int,
      committed: Int,
      available: Int,
      netNeed: Int,
      proposed: Int,
      blocked: Int,
      zone: String
  ): F[Int] =
    (sql"""INSERT INTO po_proposal (supplier_id, product_variant_id, target_date, demand_qty, committed_qty, available_qty, net_need, proposed_delta, blocked_qty, zone)
           VALUES ($supplier, $variant, $target, $demand, $committed, $available, $netNeed, $proposed, $blocked, $zone)
           ON CONFLICT (supplier_id, product_variant_id, target_date)
           DO UPDATE SET demand_qty=$demand, committed_qty=$committed, available_qty=$available, net_need=$netNeed,
                         proposed_delta=$proposed, blocked_qty=$blocked, zone=$zone, status='proposed', created_at=now()""".update.run *>
      OutboxRepo.append(
        OutboxEvent(
          UUID.randomUUID(),
          "supply.po.proposed",
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
            "demand"             -> demand.asJson,
            "committed"          -> committed.asJson,
            "available"          -> available.asJson,
            "net_need"           -> netNeed.asJson,
            "proposed_delta"     -> proposed.asJson,
            "blocked_qty"        -> blocked.asJson,
            "zone"               -> zone.asJson
          ),
          Instant.now()
        )
      )).transact(xa)
}
