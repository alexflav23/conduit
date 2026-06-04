package com.hypervolt.conduit.warranty

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Shared provisioning helpers (used by both activation ingest and the retroactive backfill).
object WarrantyProvisioning {

  def legalMonths(familyId: UUID): ConnectionIO[Int] =
    sql"""SELECT statutory_months FROM legal_warranty
          WHERE (product_family_id = $familyId OR product_family_id IS NULL) AND effective_to IS NULL
          ORDER BY product_family_id NULLS LAST LIMIT 1""".query[Int].option.map(_.getOrElse(0))

  def extensionMonths(serialUnitId: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(SUM(extra_months), 0) FROM warranty_extension WHERE serial_unit_id = $serialUnitId"
      .query[Int]
      .unique

  private def ratePct(familyId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(provision_rate_pct, 0) FROM warranty_rate
          WHERE (product_family_id = $familyId OR product_family_id IS NULL) AND effective_to IS NULL
          ORDER BY product_family_id NULLS LAST LIMIT 1""".query[BigDecimal].option.map(_.getOrElse(BigDecimal(0)))

  // Provision = batch landed cost × warranty_rate%. Idempotent (UNIQUE serial_unit_id, ON CONFLICT DO NOTHING).
  def open(
      serialUnitId: UUID,
      entityId: Option[UUID],
      lotBatchId: Option[UUID],
      familyId: UUID,
      start: LocalDate,
      end: LocalDate
  ): ConnectionIO[Unit] =
    for {
      rate <- ratePct(familyId)
      batch <- lotBatchId.flatTraverse(b =>
        sql"SELECT landed_unit_cost, currency FROM lot_batch WHERE id = $b".query[(BigDecimal, String)].option
      )
      (cost, currency) = batch.getOrElse((BigDecimal(0), "GBP"))
      estimated        = (cost * rate / 100).setScale(4, RoundingMode.HALF_UP)
      _ <- sql"""INSERT INTO warranty_provision
                   (serial_unit_id, entity_id, lot_batch_id, warranty_start, warranty_end, estimated_provision, currency, outstanding)
                 VALUES ($serialUnitId, $entityId, $lotBatchId, $start, $end, $estimated, $currency, $estimated)
                 ON CONFLICT (serial_unit_id) DO NOTHING""".update.run
    } yield ()
}
