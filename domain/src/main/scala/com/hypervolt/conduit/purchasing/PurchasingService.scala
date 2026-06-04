package com.hypervolt.conduit.purchasing

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.inventory.InventoryRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

final case class ReceiveLine(
    poLineId: UUID,
    variantId: UUID,
    qty: Int,
    unitCostUsd: BigDecimal,
    fxRate: BigDecimal,
    freight: BigDecimal,
    duty: BigDecimal,
    serials: List[String],
    currency: String
)

// Purchasing/receiving (doc 02 §H, doc 04 §Inventory): receiving lands the per-lot cost, increments stock,
// and auto-fills the oldest backorders by requested date.
final class PurchasingService[F[_]: Async](xa: Transactor[F], alloc: AllocationService[F]) {

  def createPO(entity: UUID, supplier: Option[UUID], currency: String): F[(UUID, String)] =
    sql"""INSERT INTO purchase_order (po_no, entity_id, supplier_id, txn_currency)
          VALUES ('PO-' || nextval('po_no_seq'), $entity, $supplier, $currency) RETURNING id, po_no"""
      .query[(UUID, String)]
      .unique
      .transact(xa)

  def addPoLine(poId: UUID, variant: UUID, qty: Int, unitCost: BigDecimal): F[UUID] =
    sql"INSERT INTO po_line (po_id, product_variant_id, qty, unit_cost) VALUES ($poId, $variant, $qty, $unitCost) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  // Receives one PO line: creates the lot_batch (landed cost), increments stock, records GRN + landed costs.
  def receive(poId: UUID, entity: UUID, locationId: UUID, line: ReceiveLine, receivedDate: LocalDate): F[UUID] = {
    val batchNo = s"GRN-${UUID.randomUUID().toString.take(8)}"
    val tx: ConnectionIO[UUID] =
      for {
        batchId <- LotBatchRepo.create(
          NewBatch(
            batchNo,
            None,
            line.variantId,
            line.qty,
            line.unitCostUsd,
            line.fxRate,
            "spot",
            None,
            line.freight,
            line.duty,
            line.currency
          ),
          receivedDate
        )
        grn <- sql"INSERT INTO goods_receipt (po_id) VALUES ($poId) RETURNING id".query[UUID].unique
        _   <- InventoryRepo.receive(Some(entity), line.variantId, locationId, line.qty)
        _ <- line.serials.traverse_ { s =>
          sql"""INSERT INTO serial_unit (serial_no, generation, product_variant_id, lot_batch_id, entity_id, location_id, status)
                     SELECT $s, generation, id, $batchId, $entity, $locationId, 'in_stock' FROM product_variant WHERE id = ${line.variantId}""".update.run.void
        }
        _ <- sql"UPDATE po_line SET qty_received = qty_received + ${line.qty} WHERE id = ${line.poLineId}".update.run
        _ <-
          sql"INSERT INTO goods_receipt_line (grn_id, po_line_id, qty_received, serials, lot_batch_id) VALUES ($grn, ${line.poLineId}, ${line.qty}, ${line.serials}, $batchId)".update.run
        _ <- recordLanded(grn, poId, "freight", line.freight, line.currency)
        _ <- recordLanded(grn, poId, "duty", line.duty, line.currency)
      } yield batchId
    tx.transact(xa).flatTap(_ => autoAllocateBackorders(entity, line.variantId))
  }

  private def recordLanded(
      grn: UUID,
      poId: UUID,
      kind: String,
      amount: BigDecimal,
      currency: String
  ): ConnectionIO[Int] =
    if (amount.signum <= 0) Applicative[ConnectionIO].pure(0)
    else
      sql"INSERT INTO landed_cost_component (grn_id, po_id, type, amount, currency) VALUES ($grn, $poId, $kind, $amount, $currency)".update.run

  // Re-run allocation for backordered, non-scheduled lines of this variant, oldest requested-date first.
  def autoAllocateBackorders(entity: UUID, variant: UUID): F[Unit] =
    sql"""SELECT ol.id, (ol.qty - ol.qty_allocated)
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE ol.product_variant_id = $variant AND o.entity_id = $entity AND ol.status = 'backordered'
          ORDER BY ol.promised_date NULLS LAST, o.order_date""".query[(UUID, Int)].to[List].transact(xa).flatMap {
      _.traverse_ {
        case (lineId, needed) => alloc.allocate(lineId, None, entity, variant, needed, serialised = false).void
      }
    }
}
