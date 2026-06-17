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
            _         <- ensureVolexSupplier
            _         <- ensureInHouseSupplier
            stamped   <- stampOrders(eid)
            lots      <- createCostedLots
            legacyLots <- createLegacyLots
            linked    <- linkSerials
            periods <- openPeriods(eid)
            _       <- ensureLocation(eid)
            stock   <- createStockItems(eid)
            exp     <- HedgeProgramRepo.rebuildExposureForecast(eid, transition)
            repriced <- fixPriceLostLines
            _        <- fixPriceLostCommitments
            _        <- invalidateStaleZeroRecognition
            placed  <- emitOrderPlaced
            emitted <- emitRecognitionEvents
            invOpen <- emitOpeningInventory(eid)
            warr    <- backfillWarrantyWindows
            repl    <- applyRmaTickets
            mdm     <- correlateMasterAccounts
            merged  <- applyAccountMatches
          } yield s"stamped=$stamped lots=$lots legacy_lots=$legacyLots serials_linked=$linked periods=$periods stock_items=$stock exposure_rows=$exp price_lost_lines_fixed=$repriced order_placed_events=$placed recognition_events=$emitted opening_inv_event=$invOpen warranty_windows=$warr replacements_linked=$repl $mdm $merged"
      }
      .transact(xa)

  // The contract manufacturer (Volex, USD), so opening lots carry a supplier.
  private def ensureVolexSupplier: ConnectionIO[Int] =
    sql"""INSERT INTO supplier (name, billing_currency, supplier_entity, is_contract_manufacturer)
          SELECT 'Volex', 'USD', 'Volex PLC', TRUE WHERE NOT EXISTS (SELECT 1 FROM supplier WHERE name = 'Volex')""".update.run

  // The legacy Home Pro / Home 3.0 units were made in-house before Volex contract manufacturing — their cost is
  // MRPeasy's avg_cost (GBP, native), so the supplier is Hypervolt itself.
  private def ensureInHouseSupplier: ConnectionIO[Int] =
    sql"""INSERT INTO supplier (name, billing_currency, supplier_entity, is_contract_manufacturer)
          SELECT 'Hypervolt In-House', 'GBP', 'Hypervolt Ltd', FALSE WHERE NOT EXISTS (SELECT 1 FROM supplier WHERE name = 'Hypervolt In-House')""".update.run

  // One costed opening lot per legacy variant from MRPeasy's GBP avg_cost (no FX, no shipping uplift — it's the
  // native in-house cost). Mirrors createCostedLots but GBP-native; covers the HV-PR-117x Home Pro fleet that Volex
  // never made. Guarded: only variants with serials + no existing lot.
  private def createLegacyLots: ConnectionIO[Int] =
    sql"""INSERT INTO lot_batch (batch_no, supplier_id, product_variant_id, qty, unit_cost_usd, fx_rate, fx_basis,
            shipping_alloc, duty_alloc, landed_unit_cost, currency, received_date)
          SELECT 'INHOUSE-OPEN-' || v.sku, (SELECT id FROM supplier WHERE name = 'Hypervolt In-House'), v.id,
                 (SELECT count(*) FROM serial_unit s WHERE s.product_variant_id = v.id),
                 sc.unit_cost, 1, 'gbp_native', 0, 0, round(sc.unit_cost, 4), 'GBP', DATE '2024-01-01'
          FROM product_variant v
          JOIN supplier_cost sc ON sc.sku = v.sku AND sc.supplier = 'MRPeasy' AND sc.min_qty_per_quarter = 0
          WHERE EXISTS (SELECT 1 FROM serial_unit s WHERE s.product_variant_id = v.id)
            AND NOT EXISTS (SELECT 1 FROM lot_batch lb WHERE lb.product_variant_id = v.id)""".update.run

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

  // Backfill the warranty window on activated units: warranty_end = activation + the 36-month warranty floor (+ any
  // purchased 5-year extension = +24mo). The historical activation backfill set only activated_at; without a window
  // the warranty lifecycle is meaningless. Idempotent (only fills NULLs); originals must have a window before the
  // replacement chains inherit it.
  private val commercialWarrantyMonths = 36

  private def backfillWarrantyWindows: ConnectionIO[Int] =
    sql"""UPDATE serial_unit su SET warranty_end =
            (su.activated_at AT TIME ZONE 'UTC')::date
            + make_interval(months => ($commercialWarrantyMonths
                + COALESCE((SELECT sum(we.extra_months) FROM warranty_extension we WHERE we.serial_unit_id = su.id), 0))::int)
          WHERE su.activated_at IS NOT NULL AND su.warranty_end IS NULL""".update.run

  // Resolve HubSpot RMA tickets to the serial genealogy: link the replacement unit → the unit it replaced, then
  // propagate the ROOT original's warranty_end down each replacement chain (a replacement always inherits the
  // original's warranty — the clock never resets, transitively). Idempotent; a no-op until real tickets land.
  private def applyRmaTickets: ConnectionIO[Int] =
    // 0. Ensure the placeholder variant exists (the V1_0_96 migration can't create it on a FRESH boot — product
    //    variants don't exist until the ingest runs, after migrations). Idempotent.
    sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised, product_class, status)
          SELECT family_id, 'HV3-RMA-UNSPECIFIED', 'v3', true, 'charger', 'active'
          FROM product_variant WHERE generation = 'v3' LIMIT 1
          ON CONFLICT (sku) DO NOTHING""".update.run *>
      // 1. Materialise the RMA units the ledger never had (V2 originals + replacement stock) — real serials from real
      //    tickets, classified V3 (0301-hex) / V2 (long decimal MAC or HYPV2 code), provenance 'hubspot_rma'.
      sql"""INSERT INTO serial_unit (serial_no, generation, product_variant_id, status, source)
          SELECT DISTINCT ns,
            CASE WHEN ns ~ '^0301[0-9a-f]{12}$$' THEN 'v3' ELSE 'v2' END,
            CASE WHEN ns ~ '^0301[0-9a-f]{12}$$' THEN (SELECT id FROM product_variant WHERE sku = 'HV3-RMA-UNSPECIFIED')
                 ELSE COALESCE((SELECT id FROM product_variant WHERE sku = 'hv-2.0-uwt-t2'),
                               (SELECT id FROM product_variant WHERE sku = 'HV3-RMA-UNSPECIFIED')) END,
            'rma_unit', 'hubspot_rma'
          FROM (
            SELECT lower(regexp_replace(original_serial, '[^0-9A-Za-z]', '', 'g')) AS ns FROM rma_ticket WHERE original_serial IS NOT NULL
            UNION
            SELECT lower(regexp_replace(replacement_serial, '[^0-9A-Za-z]', '', 'g')) FROM rma_ticket WHERE replacement_serial IS NOT NULL
          ) cand
          WHERE (ns ~ '^0301[0-9a-f]{12}$$' OR ns ~ '^[0-9]{11,}$$' OR ns ~ '^hypv2')
            AND NOT EXISTS (SELECT 1 FROM serial_unit s WHERE s.serial_no = ns)""".update.run *>
      // 2. Resolve faulty + replacement to serial ids (normalized — charger_id is raw). HubSpot's
      //    rma_serial_number__s_n_ gives the EXACT replacement serial (no inference needed).
      sql"""UPDATE rma_ticket t SET
              original_serial_unit_id = (SELECT id FROM serial_unit s WHERE s.serial_no = lower(regexp_replace(t.original_serial, '[^0-9A-Za-z]', '', 'g'))),
              replacement_serial_unit_id = (SELECT id FROM serial_unit s WHERE s.serial_no = lower(regexp_replace(t.replacement_serial, '[^0-9A-Za-z]', '', 'g')))
            WHERE t.original_serial IS NOT NULL OR t.replacement_serial IS NOT NULL""".update.run *>
      // 3. Genealogy: the replacement unit replaces the faulty one (exact, from the ticket's two serials).
      sql"""UPDATE serial_unit r SET replaces_serial_unit_id = t.original_serial_unit_id
            FROM rma_ticket t
            WHERE r.id = t.replacement_serial_unit_id AND t.original_serial_unit_id IS NOT NULL
              AND r.id <> t.original_serial_unit_id
              AND r.replaces_serial_unit_id IS DISTINCT FROM t.original_serial_unit_id""".update.run *>
      // 4. Warranty start for the faulty unit = the real install date from the ticket (V2s have no activation in any
      //    source; the install date IS the warranty start). warranty_end = install + 36mo. Units without an install
      //    date stay NULL (out of warranty by V2 vintage). The recursive step then flows this to its replacements.
      sql"""UPDATE serial_unit s
            SET activated_at = COALESCE(s.activated_at, ((t.payload->>'installation_date') || 'T00:00:00Z')::timestamptz),
                warranty_end = ((t.payload->>'installation_date')::date + interval '36 months')::date
            FROM rma_ticket t
            WHERE s.id = t.original_serial_unit_id AND s.warranty_end IS NULL
              AND t.payload->>'installation_date' IS NOT NULL AND t.payload->>'installation_date' <> 'null'""".update.run *>
      sql"""WITH RECURSIVE chain AS (
              SELECT id, warranty_end FROM serial_unit WHERE replaces_serial_unit_id IS NULL
              UNION ALL
              SELECT s.id, c.warranty_end FROM serial_unit s JOIN chain c ON s.replaces_serial_unit_id = c.id)
            UPDATE serial_unit s SET warranty_end = c.warranty_end
            FROM chain c
            WHERE s.id = c.id AND s.replaces_serial_unit_id IS NOT NULL
              AND s.warranty_end IS DISTINCT FROM c.warranty_end""".update.run

  // Price-loss remediation (the shadow `cogs_without_revenue / price_lost` defect). The MRPeasy import dropped the
  // per-line price on some orders (all lines £0) while the header carried the real total. Restore it by allocating
  // the header evenly across the order's (all-serialised) lines, so recognition computes the true revenue. Only
  // touches orders where EVERY line is £0 and the header is > 0 — idempotent (a fixed line is never re-touched).
  private def fixPriceLostLines: ConnectionIO[Int] =
    sql"""UPDATE order_line ol SET unit_price_ex_vat = round(o.subtotal_ex_vat / NULLIF(tot.q, 0), 4)
          FROM "order" o
          JOIN (SELECT order_id, SUM(qty) AS q FROM order_line GROUP BY order_id) tot ON tot.order_id = o.id
          WHERE ol.order_id = o.id AND o.subtotal_ex_vat > 0 AND ol.unit_price_ex_vat = 0
            AND NOT EXISTS (SELECT 1 FROM order_line p WHERE p.order_id = o.id AND p.unit_price_ex_vat > 0)""".update.run

  // The same orders' commitment was recorded at £0 (from the £0 lines); recompute it from the now-fixed lines so
  // the backlog (committed = recognised + open) stays consistent. Idempotent (only fills the £0-committed rows).
  private def fixPriceLostCommitments: ConnectionIO[Int] =
    sql"""UPDATE order_commitment oc
          SET committed_ex_vat = c.ex, committed_vat = round(c.ex * 0.20, 2), committed_inc_vat = round(c.ex * 1.20, 2)
          FROM (SELECT order_id, SUM(qty * unit_price_ex_vat * (1 - COALESCE(discount_pct,0)/100)) AS ex
                FROM order_line GROUP BY order_id) c
          WHERE oc.order_id = c.order_id AND oc.committed_ex_vat = 0 AND c.ex > 0""".update.run

  // Drop the stale £0-revenue recognitions for the now-repriced orders so they re-recognise at the true revenue.
  // Safe: re-recognition re-posts the COGS leg (deterministic id → TB + gl_entry ON CONFLICT both dedupe) and adds
  // the previously-skipped AR/Revenue/VAT legs. Only the price-lost set (header > 0, lines now priced) is dropped.
  private def invalidateStaleZeroRecognition: ConnectionIO[Int] =
    sql"""DELETE FROM revenue_recognition rr USING "order" o
          WHERE rr.order_id = o.id AND rr.revenue_ex_vat = 0 AND o.subtotal_ex_vat > 0
            AND EXISTS (SELECT 1 FROM order_line ol WHERE ol.order_id = o.id AND ol.unit_price_ex_vat > 0)""".update.run

  // Emit dispatch.created for each costed dispatch with no recognition row and no IN-FLIGHT event — the relay
  // publishes to conduit.orders and the consumer recognises (AR/Revenue/VAT/COGS → TigerBeetle). Idempotent, and
  // re-emits for a dispatch whose stale recognition was just invalidated (the published event no longer blocks it).
  private def emitRecognitionEvents: ConnectionIO[Int] =
    sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, payload, occurred_at, status)
          SELECT gen_random_uuid(), 'dispatch.created', 1, 'order', d.order_id, d.order_id::text,
                 jsonb_build_object('dispatch_id', d.id::text), now(), 'pending'
          FROM dispatch d
          WHERE EXISTS (SELECT 1 FROM dispatch_line dl WHERE dl.dispatch_id = d.id)
            AND EXISTS (SELECT 1 FROM serial_unit s WHERE s.dispatch_id = d.id AND s.lot_batch_id IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM revenue_recognition r WHERE r.dispatch_id = d.id)
            AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.event_type = 'dispatch.created' AND o.payload->>'dispatch_id' = d.id::text AND o.status = 'pending')""".update.run

  // Master-account correlation (doc 02). Deterministic links auto-bind; fuzzy MRPeasy↔HubSpot matches become
  // review candidates — never a guessed merge. Idempotent end to end (ON CONFLICT on the source-link / candidate
  // / contact unique keys), so it re-derives the same golden records on every boot.
  private def correlateMasterAccounts: ConnectionIO[String] = {
    // exact-link each HubSpot company to an existing party sharing its normalized name (earliest party wins)
    val linkExact =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT DISTINCT ON (hc.cid) p.id, 'hubspot_company', hc.cid, hc.cname, 'exact', 1.000, 'linked'
            FROM (SELECT c.company_id AS cid, c.name AS cname, coalesce(ds.seg, 'other') AS seg,
                    btrim(regexp_replace(regexp_replace(regexp_replace(lower(c.name),'^mrp:\s*','','g'),
                      '\y(ltd|limited|plc|llp|llc|inc|the|group|holdings|uk)\y','','g'),'[^a-z0-9]+',' ','g')) AS nn
                  FROM hubspot_company_raw c
                  LEFT JOIN (SELECT DISTINCT company_id, segment AS seg FROM deal_snapshot WHERE company_id IS NOT NULL) ds
                    ON ds.company_id = c.company_id
                  WHERE c.name IS NOT NULL AND c.name <> '') hc
            JOIN party p ON p.normalized_name = hc.nn AND hc.nn <> ''
            ORDER BY hc.cid, p.created_at
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    val stampExact =
      sql"""UPDATE party p SET external_refs = jsonb_set(p.external_refs, '{hubspot_company}', to_jsonb(asl.source_id))
            FROM account_source_link asl
            WHERE asl.party_id = p.id AND asl.source_system = 'hubspot_company' AND NOT jsonb_exists(p.external_refs, 'hubspot_company')""".update.run
    // a new master party for every HubSpot company with no exact match
    val newParties =
      sql"""INSERT INTO party (display_name, party_type, is_organization, segment, market_id, external_refs)
            SELECT DISTINCT ON (hc.cid) hc.cname,
                   CASE hc.seg WHEN 'installer' THEN 'installer' WHEN 'wholesaler' THEN 'wholesaler'
                               WHEN 'automotive' THEN 'fleet' ELSE 'other' END,
                   true, hc.seg, (SELECT id FROM market WHERE code = 'UK'),
                   jsonb_build_object('hubspot_company', hc.cid)
            FROM (SELECT c.company_id AS cid, c.name AS cname, coalesce(ds.seg, 'other') AS seg
                  FROM hubspot_company_raw c
                  LEFT JOIN (SELECT DISTINCT company_id, segment AS seg FROM deal_snapshot WHERE company_id IS NOT NULL) ds
                    ON ds.company_id = c.company_id
                  WHERE c.name IS NOT NULL AND c.name <> '') hc
            WHERE NOT EXISTS (SELECT 1 FROM account_source_link asl WHERE asl.source_system = 'hubspot_company' AND asl.source_id = hc.cid)""".update.run
    val linkNew =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT p.id, 'hubspot_company', p.external_refs->>'hubspot_company', p.display_name, 'seed', 1.000, 'linked'
            FROM party p
            WHERE jsonb_exists(p.external_refs, 'hubspot_company')
              AND NOT EXISTS (SELECT 1 FROM account_source_link asl
                              WHERE asl.source_system = 'hubspot_company' AND asl.source_id = p.external_refs->>'hubspot_company')
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    // fuzzy candidates: a new HubSpot-only party that closely matches an existing MRPeasy party → review to merge
    val candidates =
      sql"""INSERT INTO account_link_candidate (party_id, source_system, source_id, source_name, score)
            SELECT mrp.id, 'hubspot_company', hl.source_id, hl.source_name,
                   round(similarity(mrp.normalized_name, newp.normalized_name)::numeric, 3)
            FROM party newp
            JOIN account_source_link hl ON hl.party_id = newp.id AND hl.source_system = 'hubspot_company' AND hl.match_method = 'seed'
            JOIN party mrp ON jsonb_exists(mrp.external_refs, 'mrpeasy') AND mrp.id <> newp.id
              AND mrp.normalized_name % newp.normalized_name
              AND similarity(mrp.normalized_name, newp.normalized_name) >= 0.6
            WHERE newp.normalized_name <> ''
            ON CONFLICT (source_system, source_id, party_id) DO NOTHING""".update.run
    // bind each contact identity to its company's master party, then materialize the contact row (idempotent)
    val linkContacts =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT asl.party_id, 'hubspot_contact', r.contact_id,
                   btrim(coalesce(r.first_name,'') || ' ' || coalesce(r.last_name,'')), 'exact', 1.000, 'linked'
            FROM hubspot_contact_raw r
            JOIN account_source_link asl ON asl.source_system = 'hubspot_company' AND asl.source_id = r.company_id AND asl.status = 'linked'
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    val materializeContacts =
      sql"""INSERT INTO contact (party_id, first_name, last_name, role, email, phone, hs_contact_id)
            SELECT asl.party_id, r.first_name, r.last_name, r.job_title, nullif(r.email,''), r.phone, r.contact_id
            FROM hubspot_contact_raw r
            JOIN account_source_link asl ON asl.source_system = 'hubspot_company' AND asl.source_id = r.company_id AND asl.status = 'linked'
            ON CONFLICT (hs_contact_id) WHERE hs_contact_id IS NOT NULL DO NOTHING""".update.run
    for {
      ex   <- linkExact
      _    <- stampExact
      np   <- newParties
      _    <- linkNew
      cand <- candidates
      _    <- linkContacts
      ct   <- materializeContacts
    } yield s"mdm_company_exact=$ex mdm_new_parties=$np mdm_candidates=$cand contacts_materialized=$ct"
  }

  // Apply account matches → merge with perfect lineage (doc 02). Picks = committed model verdicts (confidence>=0.9,
  // when present) UNION the deterministic heuristic (best candidate per company at trgm>=0.92 whose target has real
  // order history and whose name isn't junk). The loser (a HubSpot-only party) folds into the survivor (the MRPeasy
  // trading account): source-links + contacts re-point (tagged merged_from), the loser is preserved as 'merged',
  // and an account_merge row records from→to. Idempotent: merged losers carry no pending candidates next run.
  private def applyAccountMatches: ConnectionIO[String] = {
    // Fully recomputable: reverse every prior AUTO merge (merged_by='ignition') and reset the candidates it
    // decided, so applying from the current verdicts yields the authoritative state. Manual merges (a human in the
    // review UI) carry a different merged_by and are never touched.
    val revLinks =
      sql"""UPDATE account_source_link a SET party_id = a.merged_from_party_id, merged_from_party_id = NULL
            WHERE a.merged_from_party_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM account_merge m WHERE m.loser_party_id = a.merged_from_party_id AND m.merged_by = 'ignition')""".update.run
    val revContacts =
      sql"""UPDATE contact ct SET party_id = ct.merged_from_party_id, merged_from_party_id = NULL
            WHERE ct.merged_from_party_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM account_merge m WHERE m.loser_party_id = ct.merged_from_party_id AND m.merged_by = 'ignition')""".update.run
    val revParty =
      sql"""UPDATE party SET status = 'active', merged_into_party_id = NULL
            WHERE id IN (SELECT loser_party_id FROM account_merge WHERE merged_by = 'ignition')""".update.run
    val revCand =
      sql"""UPDATE account_link_candidate SET status = 'pending', reviewed_by = NULL, reviewed_at = NULL
            WHERE reviewed_by = 'ignition'""".update.run
    val revMerge = sql"""DELETE FROM account_merge WHERE merged_by = 'ignition'""".update.run
    val buildPicks =
      sql"""CREATE TEMP TABLE _merge_picks ON COMMIT DROP AS
            SELECT DISTINCT ON (x.hs_id) x.hs_id, x.winner, x.method, x.confidence,
                   (SELECT asl.party_id FROM account_source_link asl
                    WHERE asl.source_system = 'hubspot_company' AND asl.source_id = x.hs_id LIMIT 1) AS loser
            FROM (
              SELECT v.hs_company_id AS hs_id, v.merge_into_party_id AS winner, 'model' AS method, v.confidence, 1 AS pri
              FROM hubspot_match_verdict v WHERE v.merge_into_party_id IS NOT NULL AND v.confidence >= 0.9
              UNION ALL
              SELECT h.source_id, h.party_id, 'heuristic', h.score, 2 FROM (
                SELECT DISTINCT ON (c.source_id) c.source_id, c.party_id, c.score
                FROM account_link_candidate c
                WHERE c.status = 'pending' AND c.score >= 0.92
                  AND (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = c.party_id) > 0
                  AND length(regexp_replace(lower(coalesce(c.source_name,'')), '[^a-z0-9]', '', 'g')) >= 3
                  AND NOT EXISTS (SELECT 1 FROM hubspot_match_verdict v WHERE v.hs_company_id = c.source_id)
                ORDER BY c.source_id, c.score DESC) h
            ) x
            ORDER BY x.hs_id, x.pri""".update.run
    val prune     = sql"""DELETE FROM _merge_picks WHERE loser IS NULL OR loser = winner""".update.run
    val moveLinks =
      sql"""UPDATE account_source_link a SET party_id = p.winner, merged_from_party_id = p.loser
            FROM _merge_picks p WHERE a.party_id = p.loser""".update.run
    val moveContacts =
      sql"""UPDATE contact ct SET party_id = p.winner, merged_from_party_id = p.loser
            FROM _merge_picks p WHERE ct.party_id = p.loser""".update.run
    val lineage =
      sql"""INSERT INTO account_merge (loser_party_id, winner_party_id, method, confidence, reason, sources_moved, contacts_moved, merged_by)
            SELECT p.loser, p.winner, p.method, p.confidence, 'auto-merge ('||p.method||')',
                   (SELECT count(*) FROM account_source_link a WHERE a.merged_from_party_id = p.loser),
                   (SELECT count(*) FROM contact ct WHERE ct.merged_from_party_id = p.loser), 'ignition'
            FROM _merge_picks p""".update.run
    val markLosers =
      sql"""UPDATE party SET status = 'merged', merged_into_party_id = p.winner
            FROM _merge_picks p WHERE party.id = p.loser""".update.run
    val acceptChosen =
      sql"""UPDATE account_link_candidate c SET status = 'accepted', reviewed_by = 'ignition', reviewed_at = now()
            FROM _merge_picks p WHERE c.source_id = p.hs_id AND c.party_id = p.winner AND c.status = 'pending'""".update.run
    val supersede =
      sql"""UPDATE account_link_candidate c SET status = 'superseded'
            FROM _merge_picks p WHERE c.source_id = p.hs_id AND c.party_id <> p.winner AND c.status = 'pending'""".update.run
    val count = sql"SELECT count(*) FROM _merge_picks".query[Int].unique
    revLinks *> revContacts *> revParty *> revCand *> revMerge *>
      buildPicks *> prune *> count.flatMap(n =>
        moveLinks *> moveContacts *> lineage *> markLosers *> acceptChosen *> supersede.as(s"account_merges=$n")
      )
  }
}
