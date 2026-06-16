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
            _       <- ensureLocation(eid)
            stock   <- createStockItems(eid)
            exp     <- HedgeProgramRepo.rebuildExposureForecast(eid, transition)
            placed  <- emitOrderPlaced
            emitted <- emitRecognitionEvents
            invOpen <- emitOpeningInventory(eid)
            warr    <- backfillWarrantyWindows
            repl    <- applyRmaTickets
          } yield s"stamped=$stamped lots=$lots serials_linked=$linked periods=$periods stock_items=$stock exposure_rows=$exp order_placed_events=$placed recognition_events=$emitted opening_inv_event=$invOpen warranty_windows=$warr replacements_linked=$repl"
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

  // A stock location for the on-hand balance (the inventory↔count physical side).
  private def ensureLocation(eid: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO location (entity_id, code, name, type)
          SELECT $eid, 'UK-MAIN', 'UK Warehouse', 'warehouse' WHERE NOT EXISTS (SELECT 1 FROM location WHERE code = 'UK-MAIN')""".update.run

  // On-hand = costed serials not yet dispatched (the COGS-relieved ones leave the ledger via recognition). So
  // INV ledger net (opening − COGS) ties to physical (on-hand × landed cost). Idempotent per (entity, variant, loc).
  private def createStockItems(eid: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO stock_item (entity_id, product_variant_id, location_id, qty_on_hand)
          SELECT $eid, v.id, (SELECT id FROM location WHERE code = 'UK-MAIN'),
                 (SELECT count(*) FROM serial_unit s WHERE s.product_variant_id = v.id AND s.lot_batch_id IS NOT NULL AND s.dispatch_id IS NULL)
          FROM product_variant v
          WHERE EXISTS (SELECT 1 FROM serial_unit s WHERE s.product_variant_id = v.id AND s.lot_batch_id IS NOT NULL AND s.dispatch_id IS NULL)
            AND NOT EXISTS (SELECT 1 FROM stock_item si WHERE si.entity_id = $eid AND si.product_variant_id = v.id
                              AND si.location_id = (SELECT id FROM location WHERE code = 'UK-MAIN'))""".update.run

  // Emit inventory.opening (→ conduit.inventory → OpeningInventoryConsumer posts DR INV / CR opening-equity at the
  // total lot value). Once per entity (idempotent).
  private def emitOpeningInventory(eid: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, payload, occurred_at, status)
          SELECT gen_random_uuid(), 'inventory.opening', 1, 'inventory', $eid, $eid::text,
                 jsonb_build_object('entity_id', $eid::text), now(), 'pending'
          WHERE NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.event_type = 'inventory.opening' AND o.aggregate_id = $eid)""".update.run

  // A historical order IS an order.placed — replay the whole order book through the event so the baseline reality
  // rebuilds through the engines (sales backlog/commitment, commission accrual, rebate true-up). Idempotent on the
  // NOT EXISTS guard; consumers read authoritative rows by order id, so the payload is a faithful echo, not the
  // source of truth. Recognition is NOT triggered here (that is dispatch.created) — no double-posting.
  private def emitOrderPlaced: ConnectionIO[Int] =
    sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, scope, payload, occurred_at, status, origin)
          SELECT gen_random_uuid(), 'order.placed', 1, 'order', o.id, o.id::text,
                 jsonb_build_object('entity_id', o.entity_id::text, 'market_id', o.market_id::text, 'channel_id', o.channel_id::text),
                 jsonb_build_object('order_no', o.order_no, 'sold_to', o.sold_to_party_id::text, 'bill_to', o.bill_to_party_id::text,
                   'agent_id', o.agent_id::text, 'total_inc_vat', o.total_inc_vat::text, 'historical', true),
                 COALESCE(o.created_at, now()), 'pending', 'service:ignition'
          FROM "order" o
          WHERE NOT EXISTS (SELECT 1 FROM outbox_event e WHERE e.event_type = 'order.placed' AND e.aggregate_id = o.id)""".update.run

  // Backfill the warranty window on activated units: warranty_end = activation + the 3-year commercial term (+ any
  // purchased extension). The historical activation backfill set only activated_at; without a window the warranty
  // lifecycle is meaningless. The 36-month commercial warranty (doc 04) governs — the legal_warranty 24mo is the
  // statutory FLOOR, a separate concept. Idempotent (only fills NULLs); originals must have a window before the
  // replacement chains inherit it.
  private val commercialWarrantyMonths = 36

  private def backfillWarrantyWindows: ConnectionIO[Int] =
    sql"""UPDATE serial_unit su SET warranty_end =
            (su.activated_at AT TIME ZONE 'UTC')::date
            + make_interval(months => $commercialWarrantyMonths
                + COALESCE((SELECT sum(we.extra_months) FROM warranty_extension we WHERE we.serial_unit_id = su.id), 0))
          WHERE su.activated_at IS NOT NULL AND su.warranty_end IS NULL""".update.run

  // Resolve HubSpot RMA tickets to the serial genealogy: link the replacement unit → the unit it replaced, then
  // propagate the ROOT original's warranty_end down each replacement chain (a replacement always inherits the
  // original's warranty — the clock never resets, transitively). Idempotent; a no-op until real tickets land.
  private def applyRmaTickets: ConnectionIO[Int] =
    sql"""UPDATE rma_ticket t SET original_serial_unit_id = o.id, replacement_serial_unit_id = r.id
          FROM serial_unit o, serial_unit r
          WHERE o.serial_no = t.original_serial AND r.serial_no = t.replacement_serial""".update.run *>
      sql"""UPDATE serial_unit r SET replaces_serial_unit_id = t.original_serial_unit_id
            FROM rma_ticket t
            WHERE r.id = t.replacement_serial_unit_id AND t.original_serial_unit_id IS NOT NULL
              AND r.replaces_serial_unit_id IS DISTINCT FROM t.original_serial_unit_id""".update.run *>
      sql"""WITH RECURSIVE chain AS (
              SELECT id, warranty_end FROM serial_unit WHERE replaces_serial_unit_id IS NULL
              UNION ALL
              SELECT s.id, c.warranty_end FROM serial_unit s JOIN chain c ON s.replaces_serial_unit_id = c.id)
            UPDATE serial_unit s SET warranty_end = c.warranty_end
            FROM chain c
            WHERE s.id = c.id AND s.replaces_serial_unit_id IS NOT NULL
              AND s.warranty_end IS DISTINCT FROM c.warranty_end""".update.run

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
