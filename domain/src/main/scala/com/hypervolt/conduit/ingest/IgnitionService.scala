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
            branches <- detectBranches
            owners  <- materializeOwners
            soldVia <- phonePreAssociate
            fcOwn   <- seedForecastOwnership
            docSer  <- ensureDocumentSeries
            lineVat <- backfillLineVat
            mlVat   <- backfillMultiLineVat
            dispVat <- backfillDispatchVat
            tranches <- modelDeliveryTranches
            prodNames <- backfillProductNames
            ordLin   <- backfillOrderLineage
          } yield s"stamped=$stamped lots=$lots legacy_lots=$legacyLots serials_linked=$linked periods=$periods stock_items=$stock exposure_rows=$exp price_lost_lines_fixed=$repriced order_placed_events=$placed recognition_events=$emitted opening_inv_event=$invOpen warranty_windows=$warr replacements_linked=$repl $mdm $merged branches_linked=$branches $owners customer_installer_phone_links=$soldVia forecastable_accounts=$fcOwn document_series=$docSer line_vat_backfilled=$lineVat multiline_vat=$mlVat dispatch_vat=$dispVat delivery_tranches=$tranches product_names=$prodNames order_lineage=$ordLin"
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
    // Orphan contacts (no HubSpot company association) attributed by a SAFE signal: (a) the free-text company they
    // typed matches a master account by normalized name, or (b) a business email domain that resolves to exactly
    // one account (free-email domains excluded — gmail etc. are individuals, never a company). Free-email + no
    // company stays a genuine individual consumer, never force-fitted to a B2B account.
    val freeDomains =
      "('gmail.com','googlemail.com','hotmail.com','hotmail.co.uk','outlook.com','outlook.co.uk','yahoo.com','yahoo.co.uk'," +
        "'icloud.com','live.co.uk','live.com','btinternet.com','me.com','aol.com','sky.com','mail.com','protonmail.com')"
    val orphanByName =
      (fr"""INSERT INTO contact (party_id, first_name, last_name, role, email, phone, hs_contact_id)
            SELECT DISTINCT ON (r.contact_id) p.id, r.first_name, r.last_name, r.job_title, nullif(r.email,''), r.phone, r.contact_id
            FROM hubspot_contact_raw r
            JOIN party p ON p.status <> 'merged' AND p.normalized_name <> ''
              AND p.normalized_name = btrim(regexp_replace(regexp_replace(regexp_replace(lower(r.company),'^mrp:\s*','','g'),
                  '\y(ltd|limited|plc|llp|llc|inc|the|group|holdings|uk)\y','','g'),'[^a-z0-9]+',' ','g'))
            WHERE r.company_id IS NULL AND coalesce(r.company,'') <> ''
            ON CONFLICT (hs_contact_id) WHERE hs_contact_id IS NOT NULL DO NOTHING""").update.run
    val orphanByDomain =
      (sql"""INSERT INTO contact (party_id, first_name, last_name, role, email, phone, hs_contact_id)
            SELECT dom.party_id, r.first_name, r.last_name, r.job_title, nullif(r.email,''), r.phone, r.contact_id
            FROM hubspot_contact_raw r
            JOIN (
              SELECT lower(hcr.domain) AS domain, min(asl.party_id::text)::uuid AS party_id
              FROM hubspot_company_raw hcr
              JOIN account_source_link asl ON asl.source_system='hubspot_company' AND asl.source_id=hcr.company_id AND asl.status='linked'
              WHERE hcr.domain IS NOT NULL AND hcr.domain <> '' AND lower(hcr.domain) NOT IN """ ++ Fragment.const(freeDomains) ++ fr"""
              GROUP BY 1 HAVING count(DISTINCT asl.party_id) = 1
            ) dom ON dom.domain = lower(split_part(r.email,'@',2))
            WHERE r.company_id IS NULL AND r.email LIKE '%@%'
            ON CONFLICT (hs_contact_id) WHERE hs_contact_id IS NOT NULL DO NOTHING""").update.run
    // link every materialized contact's identity to its master party (idempotent), covering company + orphan paths
    val linkAllContacts =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT ct.party_id, 'hubspot_contact', ct.hs_contact_id,
                   btrim(coalesce(ct.first_name,'') || ' ' || coalesce(ct.last_name,'')), 'exact', 1.000, 'linked'
            FROM contact ct WHERE ct.hs_contact_id IS NOT NULL
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    for {
      ex   <- linkExact
      _    <- stampExact
      np   <- newParties
      _    <- linkNew
      cand <- candidates
      _    <- linkContacts
      ct   <- materializeContacts
      obn  <- orphanByName
      obd  <- orphanByDomain
      _    <- linkAllContacts
    } yield s"mdm_company_exact=$ex mdm_new_parties=$np mdm_candidates=$cand contacts_materialized=$ct orphan_by_name=$obn orphan_by_domain=$obd"
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
              SELECT v.hs_company_id AS hs_id, w.id AS winner, 'model' AS method, v.confidence, 1 AS pri
              FROM hubspot_match_verdict v JOIN party w ON w.display_name = 'MRP: ' || v.merge_into_name
              WHERE v.merge_into_name IS NOT NULL AND v.confidence >= 0.9
                AND NOT EXISTS (SELECT 1 FROM account_link_candidate cc
                                WHERE cc.source_id = v.hs_company_id AND cc.reviewed_by IS NOT NULL AND cc.reviewed_by <> 'ignition')
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

  // CEF-style wholesaler branches: the very clean "PARENT (location)" pattern — "CEF (Aberdeen City Centre)" rolls
  // up to "CEF". Conservative + safe: only the parenthesised-location form, parent itself unparenthesised,
  // shortest matching parent wins. Branches keep their own identity/orders; the rest are set manually in the desk.
  private def detectBranches: ConnectionIO[Int] =
    sql"""WITH branch_cand AS (
            SELECT id, substring(regexp_replace(display_name,'^MRP:\s*','') from '^(.*) \(') AS parent_name
            FROM party
            WHERE parent_party_id IS NULL AND status <> 'merged' AND display_name LIKE '% (%'),
          pairs AS (
            SELECT DISTINCT ON (bc.id) bc.id AS branch, p.id AS parent
            FROM branch_cand bc
            JOIN party p ON regexp_replace(p.display_name,'^MRP:\s*','') = bc.parent_name
                        AND p.parent_party_id IS NULL AND p.status <> 'merged'
            WHERE bc.parent_name IS NOT NULL AND length(bc.parent_name) >= 2
            ORDER BY bc.id, length(p.display_name) ASC)
          UPDATE party SET parent_party_id = pairs.parent FROM pairs WHERE party.id = pairs.branch""".update.run

  // Serial → owner (doc 07 M7): from the placement registry (placement_owner_raw), materialise an INDIVIDUAL master
  // account per owner email (the consumer who owns the charger), then stamp serial_unit.owner_party_id so every
  // activated charger traces to its owner. Idempotent: one party per email (unique index), serial link re-derives.
  private def materializeOwners: ConnectionIO[String] = {
    val createOwners =
      sql"""INSERT INTO party (display_name, party_type, is_organization, segment, market_id, external_refs)
            SELECT DISTINCT ON (lower(r.owner_email)) COALESCE(NULLIF(r.owner_name,''), r.owner_email), 'individual',
                   false, 'consumer', (SELECT id FROM market WHERE code = 'UK'),
                   jsonb_build_object('owner_email', lower(r.owner_email), 'keycloak', r.keycloak_user_id)
            FROM placement_owner_raw r
            WHERE r.owner_email IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM party p WHERE lower(p.external_refs->>'owner_email') = lower(r.owner_email))
            ORDER BY lower(r.owner_email)""".update.run
    val linkSerialOwners =
      sql"""UPDATE serial_unit s SET owner_party_id = p.id
            FROM placement_owner_raw r
            JOIN party p ON lower(p.external_refs->>'owner_email') = lower(r.owner_email)
            WHERE s.serial_no = r.serial AND r.owner_email IS NOT NULL AND s.owner_party_id IS DISTINCT FROM p.id""".update.run
    val linkOwnerSources =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT DISTINCT ON (r.keycloak_user_id) p.id, 'placement_owner', r.keycloak_user_id, r.owner_email, 'exact', 1.000, 'linked'
            FROM placement_owner_raw r
            JOIN party p ON lower(p.external_refs->>'owner_email') = lower(r.owner_email)
            WHERE r.keycloak_user_id IS NOT NULL
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    // Reconcile: a HubSpot consumer contact whose email matches an owner account IS that owner — attach the
    // contact (marketing identity) to the owner master account, unifying the two populations by email.
    val attachConsumers =
      sql"""INSERT INTO contact (party_id, first_name, last_name, role, email, phone, hs_contact_id)
            SELECT DISTINCT ON (r.contact_id) p.id, r.first_name, r.last_name, r.job_title, nullif(r.email,''), r.phone, r.contact_id
            FROM hubspot_contact_raw r
            JOIN party p ON lower(p.external_refs->>'owner_email') = lower(r.email)
            WHERE r.company_id IS NULL AND r.email IS NOT NULL
            ON CONFLICT (hs_contact_id) WHERE hs_contact_id IS NOT NULL DO NOTHING""".update.run
    val linkConsumerSources =
      sql"""INSERT INTO account_source_link (party_id, source_system, source_id, source_name, match_method, confidence, status)
            SELECT ct.party_id, 'hubspot_contact', ct.hs_contact_id,
                   btrim(coalesce(ct.first_name,'') || ' ' || coalesce(ct.last_name,'')), 'exact', 1.000, 'linked'
            FROM contact ct WHERE ct.hs_contact_id IS NOT NULL
            ON CONFLICT (source_system, source_id) DO NOTHING""".update.run
    for {
      o  <- createOwners
      ls <- linkSerialOwners
      _  <- linkOwnerSources
      cc <- attachConsumers
      _  <- linkConsumerSources
    } yield s"owner_accounts=$o serials_owner_linked=$ls consumer_contacts_unified=$cc"
  }

  // The MRPeasy import dropped line-level VAT (order_line.vat_amount = 0) though the invoice carries vat_total.
  // The document conservation guard (Σ line inc-VAT == invoice total) then rejects every invoice. Backfill the
  // exact single-line case: one line, one non-void invoice → line VAT = the invoice's vat_total (no rounding
  // ambiguity). Multi-line orders need largest-remainder allocation and are left for that follow-up. Idempotent
  // (only fills zero-VAT lines). Recognition already used the invoice-level VAT, so the ledger is unaffected.
  private def backfillLineVat: ConnectionIO[Int] =
    sql"""WITH oi AS (
            SELECT o.id AS order_id, max(i.vat_total) AS vat_total, count(i.*) AS ninv
            FROM order_invoice i JOIN "order" o ON o.id = i.order_id WHERE i.status <> 'void' GROUP BY o.id),
          single AS (
            SELECT oi.order_id, oi.vat_total FROM oi
            WHERE oi.ninv = 1 AND (SELECT count(*) FROM order_line ol WHERE ol.order_id = oi.order_id) = 1)
          UPDATE order_line ol SET vat_amount = single.vat_total
          FROM single WHERE ol.order_id = single.order_id AND COALESCE(ol.vat_amount, 0) = 0""".update.run

  // Multi-line VAT (C5 tail): allocate the invoice's vat_total across the lines by net weight using
  // largest-remainder (the house conserving allocate, doc 14) so Σ line VAT == vat_total to the penny. Only the
  // feasible set — one non-void invoice, multiple lines, Σ(line net) already == invoice ex-VAT — so conservation
  // holds. Orders whose line nets don't sum to the invoice ex-VAT (partial-dispatch / subset invoices) are left;
  // those need the document line-scoping fix, not a VAT backfill. Idempotent (only fills zero-VAT lines).
  private def backfillMultiLineVat: ConnectionIO[Int] =
    sql"""WITH mo AS (
            SELECT o.id order_id, max(i.total_ex_vat) ex, max(i.vat_total) vat
            FROM order_invoice i JOIN "order" o ON o.id = i.order_id WHERE i.status <> 'void' GROUP BY o.id
            HAVING count(i.*) = 1 AND (SELECT count(*) FROM order_line ol WHERE ol.order_id = o.id) > 1),
          ln AS (
            SELECT ol.id line_id, ol.order_id,
                   round((ol.unit_price_ex_vat * ol.qty * (1 - ol.discount_pct / 100))::numeric, 2) net
            FROM order_line ol JOIN mo ON mo.order_id = ol.order_id WHERE COALESCE(ol.vat_amount, 0) = 0),
          feasible AS (
            SELECT ln.order_id FROM ln JOIN mo ON mo.order_id = ln.order_id
            GROUP BY ln.order_id, mo.ex HAVING round(sum(ln.net), 2) = mo.ex AND mo.ex > 0),
          base AS (
            SELECT ln.line_id, ln.order_id, ln.net, round(mo.vat * 100)::bigint vat_minor,
                   sum(ln.net) OVER (PARTITION BY ln.order_id) net_sum
            FROM ln JOIN mo ON mo.order_id = ln.order_id JOIN feasible f ON f.order_id = ln.order_id),
          shares AS (SELECT *, (vat_minor * net / net_sum) ideal, floor(vat_minor * net / net_sum)::bigint fl FROM base),
          rema AS (SELECT *, vat_minor - sum(fl) OVER (PARTITION BY order_id) remainder,
                          row_number() OVER (PARTITION BY order_id ORDER BY (ideal - floor(ideal)) DESC, line_id) rnk FROM shares),
          final AS (SELECT line_id, (fl + CASE WHEN rnk <= remainder THEN 1 ELSE 0 END)::numeric / 100 vat_amt FROM rema)
          UPDATE order_line ol SET vat_amount = final.vat_amt FROM final WHERE ol.id = final.line_id""".update.run

  // Dispatch-scoped invoice VAT (C5 / M4 tranches): every dispatch-linked invoice bills only its dispatch's lines
  // (a tranche/call-off), so its VAT belongs on those lines. Allocate each such invoice's vat_total across its
  // DISPATCHED lines (largest-remainder by dispatched net), grossed up to the full line (× ol.qty/dl.qty) so the
  // document's per-dispatch proration recovers it; whole-line dispatches (the vast majority) are exact. Covers
  // single- AND multi-invoice (multi-shipment) orders. The zero-VAT guard means lines already set by the
  // whole-order passes above are skipped, so this only fills the dispatch-billed remainder. Idempotent.
  private def backfillDispatchVat: ConnectionIO[Int] =
    sql"""WITH inv AS (
            SELECT i.id inv_id, i.order_id, i.dispatch_id, i.vat_total vat
            FROM order_invoice i
            WHERE i.status <> 'void' AND i.dispatch_id IS NOT NULL AND i.vat_total > 0),
          dl AS (
            SELECT inv.inv_id, inv.vat, dl.order_line_id, dl.id dlid, dl.qty dqty, ol.qty oqty,
                   round((ol.unit_price_ex_vat * dl.qty * (1 - ol.discount_pct / 100))::numeric, 2) dnet
            FROM inv JOIN dispatch_line dl ON dl.dispatch_id = inv.dispatch_id
                     JOIN order_line ol ON ol.id = dl.order_line_id WHERE COALESCE(ol.vat_amount, 0) = 0),
          base AS (SELECT *, round(vat * 100)::bigint vat_minor, sum(dnet) OVER (PARTITION BY inv_id) net_sum FROM dl),
          shares AS (SELECT *, CASE WHEN net_sum > 0 THEN (vat_minor * dnet / net_sum) ELSE 0 END ideal,
                            CASE WHEN net_sum > 0 THEN floor(vat_minor * dnet / net_sum)::bigint ELSE 0 END fl FROM base),
          rema AS (SELECT *, vat_minor - sum(fl) OVER (PARTITION BY inv_id) rem,
                          row_number() OVER (PARTITION BY inv_id ORDER BY (ideal - floor(ideal)) DESC, dlid) rnk FROM shares),
          final AS (SELECT order_line_id, oqty, dqty, (fl + CASE WHEN rnk <= rem THEN 1 ELSE 0 END)::numeric / 100 alloc FROM rema)
          UPDATE order_line ol SET vat_amount = CASE WHEN final.dqty > 0 THEN round(final.alloc * final.oqty / final.dqty, 2) ELSE final.alloc END
          FROM final WHERE ol.id = final.order_line_id""".update.run

  // Model the tranches (M4 / doc 11): an order shipped across >1 dispatch, or a single dispatch covering only some
  // lines, is a call-off — record a delivery_tranche per (order line, dispatch) and stamp dispatch_line.tranche_id,
  // so the partial-fulfilment structure the import flattened is explicit. Scoped to genuinely-tranched orders
  // (a dispatched line whose order has >1 dispatch, or whose dispatch omits some of the order's lines). Idempotent
  // (only creates a tranche for a dispatch_line that has none).
  private def modelDeliveryTranches: ConnectionIO[Int] =
    sql"""WITH tranched_orders AS (
            SELECT DISTINCT d.order_id FROM dispatch d
            WHERE (SELECT count(*) FROM dispatch d2 WHERE d2.order_id = d.order_id) > 1
               OR (SELECT count(*) FROM dispatch_line dl JOIN dispatch dd ON dd.id = dl.dispatch_id WHERE dd.order_id = d.order_id)
                  < (SELECT count(*) FROM order_line ol WHERE ol.order_id = d.order_id)),
          dls AS (
            SELECT dl.id dlid, dl.order_line_id, dl.dispatch_id, dl.qty, d.date::date req_date,
                   row_number() OVER (PARTITION BY dl.order_line_id ORDER BY d.date, dl.id) seq
            FROM dispatch_line dl JOIN dispatch d ON d.id = dl.dispatch_id
            JOIN tranched_orders t ON t.order_id = d.order_id
            WHERE dl.tranche_id IS NULL),
          ins AS (
            INSERT INTO delivery_tranche (order_line_id, seq, qty, qty_allocated, qty_dispatched, status, dispatch_id, requested_date)
            SELECT order_line_id, seq, qty, qty, qty, 'dispatched', dispatch_id, req_date FROM dls
            RETURNING id, order_line_id, dispatch_id)
          UPDATE dispatch_line dl SET tranche_id = ins.id
          FROM ins WHERE dl.order_line_id = ins.order_line_id AND dl.dispatch_id = ins.dispatch_id AND dl.tranche_id IS NULL""".update.run

  // Order golden record (topology): the Conduit order (order.id) is the master; assign a Conduit-native ref and
  // record the source identities (the MRPeasy order code, the customer PO if present) in order_source_link, the
  // same master/sources pattern as the account MDM. Invoices/dispatches/recognition already FK to order.id, so
  // they're already lineage. Idempotent (only fills a null conduit_ref; source links ON CONFLICT DO NOTHING).
  private def backfillOrderLineage: ConnectionIO[Int] =
    sql"""UPDATE "order" o SET conduit_ref = r.ref
          FROM (SELECT id, 'CO-' || lpad(row_number() OVER (ORDER BY created_at, id)::text, 6, '0') AS ref
                FROM "order") r
          WHERE o.id = r.id AND o.conduit_ref IS NULL""".update.run *>
      sql"""INSERT INTO order_source_link (order_id, source_system, source_ref, source_detail)
            SELECT id, 'mrpeasy', order_no, 'MRPeasy customer order' FROM "order" WHERE order_no IS NOT NULL
            ON CONFLICT (order_id, source_system, source_ref) DO NOTHING""".update.run *>
      sql"""INSERT INTO order_source_link (order_id, source_system, source_ref, source_detail)
            SELECT id, 'customer_po', customer_po_number, 'Customer purchase order'
            FROM "order" WHERE customer_po_number IS NOT NULL AND customer_po_number <> ''
            ON CONFLICT (order_id, source_system, source_ref) DO NOTHING""".update.run

  // Real product names (C5 / preview): variants were created from order/serial SKUs with no human name, so line
  // items rendered the synthetic family "MRPeasy import". Map each variant's SKU to the MRPeasy catalogue title
  // (mrpeasy_item_raw), tolerating the renamed-code forms the historical orders use: exact, DONOTUSE_-prefixed,
  // and the -del<epoch> rename suffix. Unmatched variants keep their SKU as the label (the document falls back to
  // sku, never "MRPeasy import"). Idempotent (only fills a null/blank name).
  private def backfillProductNames: ConnectionIO[Int] =
    sql"""UPDATE product_variant v SET name = m.title
          FROM mrpeasy_item_raw m
          WHERE (v.name IS NULL OR v.name = '')
            AND lower(m.code) IN (
              lower(v.sku),
              'donotuse_' || lower(v.sku),
              lower(regexp_replace(v.sku, '-del[0-9]+$$', '')),
              'donotuse_' || lower(regexp_replace(v.sku, '-del[0-9]+$$', '')),
              -- Home-3 historical SKUs are colour-tethered ("ubt-t2") + carry a -del rename; the catalogue uses the
              -- plain colour ("ub-t2"). Collapse "<cc>t-t" -> "<cc>-t" after stripping -del. Additive (the exact
              -- arms still match the families whose catalogue codes keep the 't'), so it never mis-matches.
              regexp_replace(regexp_replace(lower(v.sku), '-del[0-9]+$$', ''), '([a-z]{2})t-t', '\1-t')
            )""".update.run

  // Gapless document numbering (doc 17 §3 / C5): the FOP renderer needs an active number series per
  // (entity, document_type, jurisdiction) to allocate from. Seed the UK invoice + credit-note series for the
  // operating entity. Continuous current_seq → gapless; {yyyy} in the format is presentation only. Idempotent.
  private def ensureDocumentSeries: ConnectionIO[Int] =
    sql"""INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format, period_scope, current_seq, status)
          SELECT e.id, t.doc_type, 'GB', t.series_code, '{series}-{yyyy}-{seq:06d}', 'annual', 0, 'active'
          FROM (SELECT id FROM entity WHERE entity_type = 'operating' ORDER BY created_at LIMIT 1) e
          CROSS JOIN (VALUES ('invoice', 'HV-UK-INV'), ('credit_note', 'HV-UK-CN')) AS t(doc_type, series_code)
          WHERE NOT EXISTS (SELECT 1 FROM document_number_series s
                            WHERE s.entity_id = e.id AND s.document_type = t.doc_type AND s.jurisdiction = 'GB')""".update.run

  // Bootstrap the H6Q bottom-up spine (doc 12): the cycle only generates capture slots for accounts that are
  // `forecastable` and owned by someone. No account-manager delegation exists in the imported book, so seed the
  // material trade accounts (≥ £100k lifetime order value — the ones worth a manual forecast) as forecastable,
  // owned by the operator until AMs are delegated. Top-level orgs only (a master rolls up from its branches).
  // Real accounts + the real operator — no fabricated owners. Idempotent (skips ones already forecastable).
  private def seedForecastOwnership: ConnectionIO[Int] =
    sql"""UPDATE party p
          SET roles = (SELECT array_agg(DISTINCT r) FROM unnest(COALESCE(p.roles, '{}'::text[]) || ARRAY['forecastable']) r),
              owner_user_id = COALESCE(p.owner_user_id, (SELECT id FROM app_user WHERE email = 'flavian@hypervolt.co.uk'))
          WHERE p.parent_party_id IS NULL AND p.status = 'active' AND p.is_organization
            AND NOT ('forecastable' = ANY(COALESCE(p.roles, '{}'::text[])))
            AND (SELECT COALESCE(sum(o.total_inc_vat), 0) FROM "order" o WHERE o.sold_to_party_id = p.id) >= 100000""".update.run

  // Phone pre-association (doc 02 §C): a phone number is a person-level identity key. Where email did NOT already
  // unify a consumer with the installer/wholesaler who sold or fitted their charger, an exact phone match does —
  // bridging the materialised owner to the org that carries the same number on one of its contacts. Deliberately
  // conservative: ONLY a phone held by exactly two non-merged parties (so installer switchboards, which fan out
  // across many staff/customer contacts, are excluded), ONLY consumer↔organisation, and NEVER overwrites an
  // existing attribution. Stamps the installer onto the consumer's external_refs as a soft, reviewable link —
  // it does not merge the records. Idempotent.
  private def phonePreAssociate: ConnectionIO[Int] =
    sql"""WITH ph AS (
            SELECT regexp_replace(c.phone, '[^0-9]', '', 'g') AS n, c.party_id
            FROM contact c
            WHERE c.phone IS NOT NULL AND length(regexp_replace(c.phone, '[^0-9]', '', 'g')) >= 7
            GROUP BY 1, 2),
          two AS (SELECT n FROM ph GROUP BY n HAVING count(*) = 2),
          sides AS (
            SELECT ph.n, p.id, p.display_name, p.is_organization, p.party_type
            FROM ph JOIN two USING (n)
            JOIN party p ON p.id = ph.party_id AND p.status <> 'merged'),
          bridge AS (
            SELECT DISTINCT ON (ind.id) ind.id AS consumer_id, org.id AS org_id, org.display_name AS org_name
            FROM sides ind
            JOIN sides org ON org.n = ind.n AND org.id <> ind.id
            WHERE ind.party_type = 'individual' AND org.is_organization
            ORDER BY ind.id, org.id)
          UPDATE party SET external_refs = COALESCE(external_refs, '{}'::jsonb)
                 || jsonb_build_object('sold_via_party_id', bridge.org_id::text,
                                       'sold_via_name', bridge.org_name,
                                       'sold_via_match', 'phone')
          FROM bridge
          WHERE party.id = bridge.consumer_id
            AND NOT jsonb_exists(COALESCE(party.external_refs, '{}'::jsonb), 'sold_via_party_id')""".update.run
}
