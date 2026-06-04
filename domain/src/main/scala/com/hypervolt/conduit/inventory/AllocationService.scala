package com.hypervolt.conduit.inventory

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

final case class AllocationResult(allocated: Int, status: String)

// Concurrency-safe allocation (doc 04 §ATP): the stock row is locked FOR UPDATE so two desks hitting the
// last unit serialise — exactly one wins. Serialised lines pick specific units FOR UPDATE SKIP LOCKED.
final class AllocationService[F[_]: Async](xa: Transactor[F]) {

  def allocate(
      orderLineId: UUID,
      trancheId: Option[UUID],
      entity: UUID,
      variant: UUID,
      needed: Int,
      serialised: Boolean
  ): F[AllocationResult] = {
    val program: ConnectionIO[AllocationResult] =
      for {
        stockRows <- sql"""SELECT id, location_id FROM stock_item
                           WHERE entity_id = $entity AND product_variant_id = $variant
                           ORDER BY location_id""".query[(UUID, UUID)].to[List]
        allocated <- allocateAcross(stockRows, orderLineId, trancheId, variant, needed, serialised, 0)
        _         <- updateLineAndTranche(orderLineId, trancheId, allocated, needed)
      } yield AllocationResult(allocated, if (allocated >= needed) "allocated" else "backordered")
    program.transact(xa)
  }

  private def allocateAcross(
      rows: List[(UUID, UUID)],
      line: UUID,
      tranche: Option[UUID],
      variant: UUID,
      needed: Int,
      serialised: Boolean,
      acc: Int
  ): ConnectionIO[Int] =
    rows match {
      case (stockId, locId) :: rest if acc < needed =>
        for {
          // FOR UPDATE serialises concurrent allocators on this stock row.
          avail <-
            sql"SELECT qty_on_hand - qty_allocated FROM stock_item WHERE id = $stockId FOR UPDATE".query[Int].unique
          take = math.min(needed - acc, math.max(avail, 0))
          _ <-
            if (take > 0)
              sql"UPDATE stock_item SET qty_allocated = qty_allocated + $take, updated_at = now() WHERE id = $stockId".update.run
            else Applicative[ConnectionIO].pure(0)
          _ <-
            if (take > 0 && serialised) allocateSerials(line, tranche, locId, variant, take)
            else if (take > 0)
              sql"INSERT INTO allocation (order_line_id, tranche_id, location_id, qty) VALUES ($line, $tranche, $locId, $take)".update.run.void
            else Applicative[ConnectionIO].unit
          result <- allocateAcross(rest, line, tranche, variant, needed, serialised, acc + take)
        } yield result
      case _ => Applicative[ConnectionIO].pure(acc)
    }

  private def allocateSerials(
      line: UUID,
      tranche: Option[UUID],
      loc: UUID,
      variant: UUID,
      take: Int
  ): ConnectionIO[Unit] =
    sql"""SELECT id FROM serial_unit
          WHERE product_variant_id = $variant AND location_id = $loc AND status = 'in_stock'
          ORDER BY created_at LIMIT $take FOR UPDATE SKIP LOCKED""".query[UUID].to[List].flatMap { serials =>
      serials.traverse_ { sid =>
        sql"UPDATE serial_unit SET status = 'allocated', order_line_id = $line WHERE id = $sid".update.run *>
          sql"INSERT INTO allocation (order_line_id, tranche_id, location_id, serial_unit_id, qty) VALUES ($line, $tranche, $loc, $sid, 1)".update.run.void
      }
    }

  private def updateLineAndTranche(
      line: UUID,
      tranche: Option[UUID],
      allocated: Int,
      needed: Int
  ): ConnectionIO[Unit] = {
    val status = if (allocated >= needed) "allocated" else "backordered"
    sql"UPDATE order_line SET qty_allocated = qty_allocated + $allocated, status = $status WHERE id = $line".update.run.void *>
      tranche.fold(Applicative[ConnectionIO].unit)(t =>
        sql"UPDATE delivery_tranche SET qty_allocated = qty_allocated + $allocated, status = $status WHERE id = $t".update.run.void
      )
  }
}
