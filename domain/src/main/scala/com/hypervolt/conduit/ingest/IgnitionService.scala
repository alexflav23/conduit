package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.treasury.HedgeProgramRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID

// Idempotent boot ignition (spec/STATUS Phase A): after the snapshot ingest, replay the trade history through the
// production engines so a fresh environment — local OR AWS — reconverges to the live state with no manual steps.
// Every step guards on existing state, so a re-boot is a no-op. The TB-side work (revenue/COGS recognition) is NOT
// done here (the API holds no TigerBeetle client); instead this emits the dispatch.created events the relay +
// RevenueRecognitionConsumer post, so recognition converges asynchronously after boot — the same path prod uses.
final class IgnitionService[F[_]: Async](xa: Transactor[F]) {

  private val transition = LocalDate.of(2026, 12, 1) // Volex → Luxshare cost transition

  def ignite: F[String] =
    HedgeProgramRepo.operatingEntity
      .flatMap {
        case None => "no operating entity — ignition skipped".pure[ConnectionIO]
        case Some(eid) =>
          for {
            _       <- ensureVolexSupplier
            stamped <- stampOrders(eid)
            lots    <- createCostedLots
            linked  <- linkSerials
            periods <- openPeriods(eid)
            exp     <- HedgeProgramRepo.rebuildExposureForecast(eid, transition)
            emitted <- emitRecognitionEvents
          } yield s"stamped=$stamped lots=$lots serials_linked=$linked periods=$periods exposure_rows=$exp recognition_events=$emitted"
      }
      .transact(xa)

  // The contract manufacturer (Volex, USD), so opening lots carry a supplier.
  private def ensureVolexSupplier: ConnectionIO[Int] =
    sql"""INSERT INTO supplier (name, billing_currency, supplier_entity, is_contract_manufacturer)
          SELECT 'Volex', 'USD', 'Volex PLC', TRUE WHERE NOT EXISTS (SELECT 1 FROM supplier WHERE name = 'Volex')""".update.run

  // Orders predate the operating entity (MRP import) — attribute them to it (idempotent on the NULL guard).
  private def stampOrders(eid: UUID): ConnectionIO[Int] =
    sql"""UPDATE "order" SET entity_id = $eid WHERE entity_id IS NULL""".update.run

  // One costed opening lot per HV3PRO variant: landed = Volex 0-band USD / GBP-USD register spot + £8 shipping.
  private def createCostedLots: ConnectionIO[Int] =
    sql"""INSERT INTO lot_batch (batch_no, supplier_id, product_variant_id, qty, unit_cost_usd, fx_rate, fx_basis,
            shipping_alloc, duty_alloc, landed_unit_cost, currency, received_date)
          SELECT 'VOLEX-OPEN-' || v.sku, (SELECT id FROM supplier WHERE name = 'Volex'), v.id,
                 (SELECT count(*) FROM serial_unit s WHERE s.product_variant_id = v.id),
                 sc.unit_cost, fx.rate, 'spot', 8, 0, round(sc.unit_cost / fx.rate + 8, 4), 'GBP', DATE '2025-06-01'
          FROM product_variant v
          JOIN supplier_cost sc ON sc.sku = v.sku AND sc.supplier = 'Volex' AND sc.min_qty_per_quarter = 0
          CROSS JOIN (SELECT rate FROM exchange_rate WHERE base = 'GBP' AND quote = 'USD' AND rate_type = 'spot' ORDER BY as_of DESC LIMIT 1) fx
          WHERE EXISTS (SELECT 1 FROM serial_unit s WHERE s.product_variant_id = v.id)
            AND NOT EXISTS (SELECT 1 FROM lot_batch lb WHERE lb.product_variant_id = v.id)""".update.run

  private def linkSerials: ConnectionIO[Int] =
    sql"""UPDATE serial_unit s SET lot_batch_id = lb.id FROM lot_batch lb
          WHERE lb.product_variant_id = s.product_variant_id AND s.lot_batch_id IS NULL""".update.run

  private def openPeriods(eid: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
          SELECT $eid, 'statutory', to_char(d, 'YYYY-MM'), 'Europe/London', 'open'
          FROM generate_series(DATE '2023-10-01', DATE '2026-12-01', INTERVAL '1 month') d
          ON CONFLICT (entity_id, scope, period_key) DO NOTHING""".update.run

  // Emit dispatch.created for each costed, not-yet-recognised dispatch with no in-flight event — the relay
  // publishes to conduit.orders and the consumer recognises (AR/Revenue/VAT/COGS → TigerBeetle). Idempotent.
  private def emitRecognitionEvents: ConnectionIO[Int] =
    sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, payload, occurred_at, status)
          SELECT gen_random_uuid(), 'dispatch.created', 1, 'order', d.order_id, d.order_id::text,
                 jsonb_build_object('dispatch_id', d.id::text), now(), 'pending'
          FROM dispatch d
          WHERE EXISTS (SELECT 1 FROM dispatch_line dl WHERE dl.dispatch_id = d.id)
            AND EXISTS (SELECT 1 FROM serial_unit s WHERE s.dispatch_id = d.id AND s.lot_batch_id IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM revenue_recognition r WHERE r.dispatch_id = d.id)
            AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.event_type = 'dispatch.created' AND o.payload->>'dispatch_id' = d.id::text)""".update.run
}
