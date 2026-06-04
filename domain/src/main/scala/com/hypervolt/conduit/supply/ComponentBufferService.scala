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
import java.util.UUID

final case class BufferStatus(partsOnSite: Int, target: Int, deficit: Int)

// The component (parts) buffer (the CEO's extra dimension). Volex holds contractually-guaranteed COMPONENTS on
// site sized to P50 demand — NOT finished goods, because finished goods become an invoice/liability for us. We
// would rather hold guaranteed parts as a buffer. Conversion of parts → finished goods is the liability trigger.
// Configurable target per SKU/supplier; tracked as its own dimension distinct from the firm-FG commitment.
final class ComponentBufferService[F[_]: Async](xa: Transactor[F]) {

  def setBuffer(supplier: UUID, variant: UUID, partsOnSite: Int): F[Unit] =
    sql"""INSERT INTO component_buffer (supplier_id, product_variant_id, parts_on_site)
          VALUES ($supplier, $variant, $partsOnSite)
          ON CONFLICT (supplier_id, product_variant_id) DO UPDATE SET parts_on_site = $partsOnSite, updated_at = now()""".update.run
      .transact(xa)
      .void

  def setTarget(supplier: Option[UUID], variant: Option[UUID], targetUnits: Int, basis: String): F[UUID] =
    sql"""INSERT INTO component_buffer_policy (supplier_id, product_variant_id, target_units, basis)
          VALUES ($supplier, $variant, $targetUnits, $basis) RETURNING id""".query[UUID].unique.transact(xa)

  // Status vs the configured target (deficit > 0 = under-buffered against P50 demand).
  def status(supplier: UUID, variant: UUID): F[BufferStatus] =
    (parts(supplier, variant), target(supplier, variant)).tupled.transact(xa).map {
      case (p, t) => BufferStatus(p, t, math.max(t - p, 0))
    }

  // Parts → finished goods: decrements the buffer and RAISES the liability (the units now become an invoice for
  // us). Emits component.converted_to_fg — the consumer books the liability / opens the FG commitment.
  def convertToFinishedGoods(supplier: UUID, variant: UUID, qty: Int): F[Either[String, BufferStatus]] =
    parts(supplier, variant).transact(xa).flatMap { p =>
      if (qty > p) s"only $p parts on site; cannot convert $qty".asLeft[BufferStatus].pure[F]
      else
        (sql"UPDATE component_buffer SET parts_on_site = parts_on_site - $qty, updated_at = now() WHERE supplier_id = $supplier AND product_variant_id = $variant".update.run *>
          OutboxRepo.append(
            OutboxEvent(
              UUID.randomUUID(),
              "component.converted_to_fg",
              1,
              "supply",
              supplier,
              s"$supplier:$variant",
              None,
              None,
              None,
              Json.obj(
                "supplier_id"        -> supplier.toString.asJson,
                "product_variant_id" -> variant.toString.asJson,
                "qty"                -> qty.asJson,
                "remaining_parts"    -> (p - qty).asJson
              ),
              Instant.now()
            )
          ))
          .transact(xa) *> status(supplier, variant).map(_.asRight[String])
    }

  private def parts(supplier: UUID, variant: UUID): ConnectionIO[Int] =
    sql"SELECT COALESCE(parts_on_site,0) FROM component_buffer WHERE supplier_id = $supplier AND product_variant_id = $variant"
      .query[Int]
      .option
      .map(_.getOrElse(0))

  private def target(supplier: UUID, variant: UUID): ConnectionIO[Int] =
    sql"""SELECT COALESCE(target_units,0) FROM component_buffer_policy
          WHERE active AND (supplier_id = $supplier OR supplier_id IS NULL)
            AND (product_variant_id = $variant OR product_variant_id IS NULL)
          ORDER BY (supplier_id IS NOT NULL)::int + (product_variant_id IS NOT NULL)::int DESC, created_at DESC LIMIT 1"""
      .query[Int]
      .option
      .map(_.getOrElse(0))
}
