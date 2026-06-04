package com.hypervolt.conduit.batch

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class NewBatch(
    batchNo: String,
    supplierId: Option[UUID],
    variantId: UUID,
    qty: Int,
    unitCostUsd: BigDecimal,
    fxRate: BigDecimal,
    fxBasis: String,
    hedgeRef: Option[String],
    shippingAlloc: BigDecimal,
    dutyAlloc: BigDecimal,
    currency: String
)

object LotBatch {

  // landed_unit_cost = (unit_cost_usd × fx_rate) + per-unit freight + per-unit duty (doc 04 §FX).
  // Strictly per-lot; no weighted-average anywhere. BigDecimal throughout — no float.
  def landedUnitCost(b: NewBatch): BigDecimal = {
    val q       = BigDecimal(b.qty)
    val perUnit = (x: BigDecimal) => if (b.qty == 0) BigDecimal(0) else (x / q)
    ((b.unitCostUsd * b.fxRate) + perUnit(b.shippingAlloc) + perUnit(b.dutyAlloc)).setScale(4, RoundingMode.HALF_UP)
  }
}

object LotBatchRepo {

  def create(b: NewBatch, receivedDate: LocalDate): ConnectionIO[UUID] = {
    val landed = LotBatch.landedUnitCost(b)
    sql"""INSERT INTO lot_batch
            (batch_no, supplier_id, product_variant_id, received_date, qty, unit_cost_usd, fx_rate, fx_basis,
             hedge_ref, shipping_alloc, duty_alloc, landed_unit_cost, currency)
          VALUES (${b.batchNo}, ${b.supplierId}, ${b.variantId}, $receivedDate, ${b.qty}, ${b.unitCostUsd},
             ${b.fxRate}, ${b.fxBasis}, ${b.hedgeRef}, ${b.shippingAlloc}, ${b.dutyAlloc}, $landed, ${b.currency})
          RETURNING id""".query[UUID].unique
  }

  def assignSerial(serialId: UUID, batchId: UUID): ConnectionIO[Int] =
    sql"UPDATE serial_unit SET lot_batch_id = $batchId WHERE id = $serialId".update.run

  // Specific-identification: a unit's cost is its own lot's landed cost — never an average.
  def costOfSerial(serialId: UUID): ConnectionIO[Option[BigDecimal]] =
    sql"""SELECT b.landed_unit_cost FROM serial_unit s JOIN lot_batch b ON b.id = s.lot_batch_id
          WHERE s.id = $serialId""".query[BigDecimal].option

  def landedCost(batchId: UUID): ConnectionIO[Option[BigDecimal]] =
    sql"SELECT landed_unit_cost FROM lot_batch WHERE id = $batchId".query[BigDecimal].option
}
