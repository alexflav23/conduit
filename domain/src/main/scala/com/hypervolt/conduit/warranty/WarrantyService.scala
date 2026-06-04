package com.hypervolt.conduit.warranty

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// Warranty register: straight-line release, consolidated exposure, claim draw-down, and retroactive backfill
// (doc 04 §Warranty). Balance-sheet posting is downstream; Conduit owns the exposure + release cycle.
final class WarrantyService[F[_]: Async](xa: Transactor[F]) {

  def release(provisionId: UUID, asOf: LocalDate): F[Unit] = releaseCIO(provisionId, asOf).transact(xa)

  def releaseAllOpen(asOf: LocalDate): F[Int] =
    sql"SELECT id FROM warranty_provision WHERE status = 'open'".query[UUID].to[List]
      .flatMap(ids => ids.traverse_(id => releaseCIO(id, asOf)).as(ids.size))
      .transact(xa)

  def consolidatedExposure(entityId: UUID): F[BigDecimal] =
    sql"SELECT COALESCE(SUM(outstanding), 0) FROM warranty_provision WHERE entity_id = $entityId AND status = 'open'"
      .query[BigDecimal].unique.transact(xa)

  def claim(serialUnitId: UUID, cost: BigDecimal): F[Unit] =
    (for {
      _ <- sql"""UPDATE warranty_provision SET consumed_by_claims = consumed_by_claims + $cost,
                   outstanding = estimated_provision - released_to_date - (consumed_by_claims + $cost)
                 WHERE serial_unit_id = $serialUnitId""".update.run
      _ <- sql"""UPDATE warranty_provision SET status = 'claimed_out'
                 WHERE serial_unit_id = $serialUnitId AND consumed_by_claims >= estimated_provision""".update.run
    } yield ()).transact(xa)

  // Rebuild the register by replaying activations through provisioning, then rolling release to asOf.
  def backfill(asOf: LocalDate): F[Int] = {
    val rebuild: ConnectionIO[Int] =
      sql"""SELECT s.id, s.entity_id, s.lot_batch_id, pv.family_id, a.activated_at
            FROM activation a JOIN serial_unit s ON s.serial_no = a.serial
            JOIN product_variant pv ON pv.id = s.product_variant_id"""
        .query[(UUID, Option[UUID], Option[UUID], UUID, Instant)]
        .to[List]
        .flatMap { acts =>
          acts.traverse_ { case (sid, ent, batch, fam, at) =>
            val start = at.atZone(ZoneOffset.UTC).toLocalDate
            for {
              months <- WarrantyProvisioning.legalMonths(fam)
              extra  <- WarrantyProvisioning.extensionMonths(sid)
              _      <- WarrantyProvisioning.open(sid, ent, batch, fam, start, start.plusMonths((months + extra).toLong))
            } yield ()
          }.as(acts.size)
        }
    rebuild.transact(xa).flatMap(n => releaseAllOpen(asOf).as(n))
  }

  private def releaseCIO(provisionId: UUID, asOf: LocalDate): ConnectionIO[Unit] =
    sql"""SELECT estimated_provision, warranty_start, warranty_end, consumed_by_claims
          FROM warranty_provision WHERE id = $provisionId"""
      .query[(BigDecimal, LocalDate, LocalDate, BigDecimal)].option.flatMap {
        case None => ().pure[ConnectionIO]
        case Some((estimated, start, end, consumed)) =>
          val released    = WarrantyMath.released(estimated, start, end, asOf)
          val outstanding = WarrantyMath.outstanding(estimated, released, consumed)
          val status      = if (!asOf.isBefore(end) && outstanding <= 0) "expired" else "open"
          sql"""UPDATE warranty_provision SET released_to_date = $released, outstanding = $outstanding, status = $status
                WHERE id = $provisionId""".update.run.void
      }
}
