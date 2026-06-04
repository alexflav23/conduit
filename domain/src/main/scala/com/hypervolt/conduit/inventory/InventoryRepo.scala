package com.hypervolt.conduit.inventory

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// On-hand is the immutable sum of stock_movement; stock_item is the running projection (doc 04 §Stock ops).
object InventoryRepo {

  def createLocation(entity: Option[UUID], code: String, name: String): ConnectionIO[UUID] =
    sql"INSERT INTO location (entity_id, code, name) VALUES ($entity, $code, $name) RETURNING id".query[UUID].unique

  def receive(entity: Option[UUID], variant: UUID, location: UUID, qty: Int): ConnectionIO[Unit] =
    (sql"""INSERT INTO stock_item (entity_id, product_variant_id, location_id, qty_on_hand)
           VALUES ($entity, $variant, $location, $qty)
           ON CONFLICT (entity_id, product_variant_id, location_id)
           DO UPDATE SET qty_on_hand = stock_item.qty_on_hand + $qty, updated_at = now()""".update.run *>
      sql"""INSERT INTO stock_movement (type, product_variant_id, location_id, entity_id, qty, ref_type)
            VALUES ('receipt', $variant, $location, $entity, $qty, 'seed')""".update.run).void

  def addSerial(
      serialNo: String,
      generation: String,
      variant: UUID,
      entity: Option[UUID],
      location: UUID
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO serial_unit (serial_no, generation, product_variant_id, entity_id, location_id, status)
          VALUES ($serialNo, $generation, $variant, $entity, $location, 'in_stock') RETURNING id""".query[UUID].unique

  def available(entity: UUID, variant: UUID): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(qty_on_hand - qty_allocated), 0) FROM stock_item
          WHERE entity_id = $entity AND product_variant_id = $variant""".query[Int].unique

  def allocatedQty(entity: UUID, variant: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(SUM(qty_allocated), 0) FROM stock_item WHERE entity_id = $entity AND product_variant_id = $variant"
      .query[Int]
      .unique

  def onHandFromMovements(variant: UUID, location: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(SUM(qty), 0) FROM stock_movement WHERE product_variant_id = $variant AND location_id = $location"
      .query[Int]
      .unique
}
