package com.hypervolt.conduit.supply

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.inventory.InventoryRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// Replenishment from observed run-rate (doc 02 §H). Integer-only (units) — no float anywhere.
object Replenishment {

  // Reorder point covers lead time + safety at the observed run-rate; suggest the shortfall vs available.
  def suggestedQty(runRateUnits: Int, windowDays: Int, leadTimeDays: Int, safetyDays: Int, available: Int): Int =
    if (windowDays <= 0) 0
    else {
      val coverDays    = leadTimeDays + safetyDays
      val reorderPoint = (runRateUnits * coverDays + windowDays - 1) / windowDays // integer ceiling
      math.max(0, reorderPoint - available)
    }
}

final class ReplenishmentService[F[_]: Async](xa: Transactor[F]) {

  // Run-rate = units dispatched for the variant within the window; a sustained rate change moves the suggestion.
  def suggest(entity: UUID, variant: UUID, windowDays: Int, leadTimeDays: Int, safetyDays: Int): F[Int] =
    for {
      runRate   <- sql"""SELECT COALESCE(-SUM(qty), 0) FROM stock_movement
                       WHERE product_variant_id = $variant AND type = 'dispatch'
                         AND occurred_at > now() - make_interval(days => $windowDays)""".query[Int].unique.transact(xa)
      available <- InventoryRepo.available(entity, variant).transact(xa)
    } yield Replenishment.suggestedQty(runRate, windowDays, leadTimeDays, safetyDays, available)
}
