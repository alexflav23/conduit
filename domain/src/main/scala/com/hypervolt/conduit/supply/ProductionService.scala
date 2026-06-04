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

final case class ProductionResult(committed: Int, produced: Int, shortfall: Int, carriedTo: Option[LocalDate])

// The contract manufacturer's production reality. A locked firm commitment becomes a PRODUCTION forecast that
// Volex may not fully meet; the shortfall (committed − produced) extends unmet demand into the next window
// (carried forward onto that window's commitment). This is the supply-side attrition in the demand→revenue
// waterfall — distinct from what was committed (doc 12 buy-side).
final class ProductionService[F[_]: Async](xa: Transactor[F]) {

  // Report what Volex actually produced against the firm commitment for a SKU/week; carry any shortfall forward.
  def report(supplier: UUID, variant: UUID, target: LocalDate, produced: Int): F[ProductionResult] =
    (for {
      committed <- committedQty(supplier, variant, target)
      shortfall = math.max(committed - produced, 0)
      carryTo   = if (shortfall > 0) Some(target.plusWeeks(1)) else None
      _ <-
        sql"""INSERT INTO production_actual (supplier_id, product_variant_id, target_date, committed_qty, produced_qty, shortfall_qty, carried_to_date)
                 VALUES ($supplier, $variant, $target, $committed, $produced, $shortfall, $carryTo)
                 ON CONFLICT (supplier_id, product_variant_id, target_date)
                 DO UPDATE SET produced_qty = EXCLUDED.produced_qty, committed_qty = EXCLUDED.committed_qty,
                               shortfall_qty = EXCLUDED.shortfall_qty, carried_to_date = EXCLUDED.carried_to_date,
                               reported_at = now()""".update.run
      // carry the shortfall onto the next window's firm commitment — unmet demand doesn't vanish, it rolls on.
      _ <- carryTo.fold(0.pure[ConnectionIO])(to =>
        sql"""INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone)
                   VALUES ($supplier, $variant, $to, $shortfall, 'flex')
                   ON CONFLICT (supplier_id, product_variant_id, target_date)
                   DO UPDATE SET qty = supply_commitment.qty + $shortfall, updated_at = now()""".update.run
      )
      _ <- OutboxRepo.append(
        event("production.reported", supplier, variant, target, committed, produced, shortfall, carryTo)
      )
      _ <-
        if (shortfall > 0)
          OutboxRepo.append(
            event("supply.shortfall.carried", supplier, variant, target, committed, produced, shortfall, carryTo)
          )
        else ().pure[ConnectionIO]
    } yield ProductionResult(committed, produced, shortfall, carryTo)).transact(xa)

  private def committedQty(supplier: UUID, variant: UUID, target: LocalDate): ConnectionIO[Int] =
    sql"SELECT COALESCE(qty,0) FROM supply_commitment WHERE supplier_id=$supplier AND product_variant_id=$variant AND target_date=$target"
      .query[Int]
      .option
      .map(_.getOrElse(0))

  private def event(
      t: String,
      supplier: UUID,
      variant: UUID,
      target: LocalDate,
      committed: Int,
      produced: Int,
      shortfall: Int,
      carryTo: Option[LocalDate]
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      t,
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
        "committed"          -> committed.asJson,
        "produced"           -> produced.asJson,
        "shortfall"          -> shortfall.asJson,
        "carried_to"         -> carryTo.map(_.toString).asJson
      ),
      Instant.now()
    )
}
