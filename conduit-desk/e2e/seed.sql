-- Seed for the desk e2e: HV-310 @ £587.50 (GB 20% VAT, 10% ADLP band), a customer price rule with
-- null channel/market (matches the demo ids the desk sends), and a `dev:agent-e2e` retail agent.
INSERT INTO product_family (code, name) VALUES ('fam-e2e', 'Home 3 Pro') ON CONFLICT (code) DO NOTHING;

INSERT INTO product_variant (family_id, sku, generation)
  SELECT id, 'HV-310', 'v3' FROM product_family WHERE code = 'fam-e2e'
  ON CONFLICT (sku) DO NOTHING;

INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, max_discount_pct, min_qty, status)
  SELECT 'customer', pv.id, 'GBP', 'GB_STANDARD', 587.50, 10.00, 1, 'active'
  FROM product_variant pv WHERE pv.sku = 'HV-310'
  AND NOT EXISTS (SELECT 1 FROM price_rule WHERE product_variant_id = pv.id AND surface = 'customer');

INSERT INTO app_user (keycloak_id, name) VALUES ('agent-e2e', 'E2E Agent') ON CONFLICT (keycloak_id) DO NOTHING;

INSERT INTO role_assignment (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r
  WHERE u.keycloak_id = 'agent-e2e' AND r.name = 'retail_sales_agent'
  AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id);

-- Deal Desk demo: a CEO user (the single approver) + an out-of-band order with a pending ADLP exception.
INSERT INTO app_user (keycloak_id, name) VALUES ('ceo-e2e', 'E2E CEO') ON CONFLICT (keycloak_id) DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r
  WHERE u.keycloak_id = 'ceo-e2e' AND r.name = 'ceo'
  AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id);

DO $$
DECLARE agent_id uuid; v_id uuid; rule_id uuid; sold uuid; ord uuid; line uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM "order" WHERE order_no = 'ORD-DEALDESK') THEN
    SELECT id INTO agent_id FROM app_user WHERE keycloak_id = 'agent-e2e';
    SELECT id INTO v_id FROM product_variant WHERE sku = 'HV-310';
    SELECT id INTO rule_id FROM price_rule WHERE product_variant_id = v_id AND surface = 'customer' LIMIT 1;
    INSERT INTO party (display_name, party_type, is_organization) VALUES ('DealDesk Cust','wholesaler',true) RETURNING id INTO sold;
    INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, created_by, adlp_category)
      VALUES ('ORD-DEALDESK','trade',sold,sold,'pending_ceo','GBP','stripe',agent_id,'exception') RETURNING id INTO ord;
    INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, discount_pct, tax_regime, price_rule_id, adlp_category, status)
      VALUES (ord, v_id, 1, 400.00, 31.91, 'GB_STANDARD', rule_id, 'exception', 'open') RETURNING id INTO line;
    INSERT INTO adlp_exception (order_id, order_line_id, party_id, list_price, max_discount_pct, requested_price, requested_discount_pct, status)
      VALUES (ord, line, sold, 587.50, 10.00, 400.00, 31.91, 'pending_ceo');
  END IF;
END $$;

-- Reset the demo exception to pending each run so the e2e is deterministic and re-runnable.
UPDATE adlp_exception SET status='pending_ceo', approved_by=NULL, approval_memo_ref=NULL,
    approved_valid_from=NULL, approved_valid_to=NULL, approved_volume_min=NULL, decided_at=NULL
  WHERE order_id = (SELECT id FROM "order" WHERE order_no = 'ORD-DEALDESK');
UPDATE "order" SET status='pending_ceo' WHERE order_no = 'ORD-DEALDESK';

-- H6Q demo: a finance viewer (full board), a forecastable account owned by the agent (demo market/channel),
-- an open weekly cycle and the agent's outstanding submission. Reset each run so the e2e is deterministic.
INSERT INTO app_user (keycloak_id, name) VALUES ('finance-e2e', 'E2E Finance') ON CONFLICT (keycloak_id) DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r
  WHERE u.keycloak_id = 'finance-e2e' AND r.name = 'finance'
  AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id);

DO $$
DECLARE agent_id uuid; acct uuid; cyc uuid;
BEGIN
  SELECT id INTO agent_id FROM app_user WHERE keycloak_id = 'agent-e2e';
  IF NOT EXISTS (SELECT 1 FROM party WHERE display_name = 'H6Q Leeds') THEN
    INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment, account_manager_user_id, status)
      VALUES ('H6Q Leeds','branch',true,'{forecastable}',
              '11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','wholesale',agent_id,'active');
  END IF;
  SELECT id INTO acct FROM party WHERE display_name = 'H6Q Leeds';

  -- exactly one open weekly cycle (the single-open-per-cadence invariant)
  UPDATE forecast_cycle SET status='closed', closed_at=now() WHERE cadence='weekly' AND status='open' AND code <> 'E2E-W01';
  INSERT INTO forecast_cycle (code, cadence, period_start, period_end, status, opened_at)
    VALUES ('E2E-W01','weekly','2026-09-01','2026-09-07','open',now())
    ON CONFLICT (code) DO UPDATE SET status='open';
  SELECT id INTO cyc FROM forecast_cycle WHERE code = 'E2E-W01';

  -- reset prior capture so the run is deterministic
  DELETE FROM forecast_entry WHERE forecaster_user_id = agent_id AND cycle_id = cyc;
  DELETE FROM pipeline_coverage WHERE market_id = '22222222-2222-2222-2222-222222222222' AND period_month = '2026-09-01';
  INSERT INTO forecast_submission (cycle_id, forecaster_user_id, company_id, status)
    VALUES (cyc, agent_id, acct, 'outstanding')
    ON CONFLICT (cycle_id, forecaster_user_id, company_id) DO UPDATE SET status='outstanding', submitted_at=NULL;
END $$;

-- Flow demo: a forecast for HV-310 in the demo market (2026-09) + a shipped + ASC-606-recognised dispatch, so
-- the Flow tab shows the variants over time and the ledger panel shows the TigerBeetle transfer ids.
DO $$
DECLARE v_id uuid; sc uuid; mkt uuid := '22222222-2222-2222-2222-222222222222'; per date := '2026-09-01';
  party_id uuid; ord uuid; ol uuid; dsp uuid;
BEGIN
  SELECT id INTO v_id FROM product_variant WHERE sku = 'HV-310';
  SELECT id INTO sc FROM forecast_scenario WHERE type = 'P50' AND toggle_basis IS NULL;
  DELETE FROM pipeline_coverage WHERE market_id = mkt AND period_month = per AND product_variant_id = v_id;
  INSERT INTO pipeline_coverage (level, market_id, product_variant_id, period_month, scenario_id, forecast_qty)
    VALUES ('market', mkt, v_id, per, sc, 100);
  IF NOT EXISTS (SELECT 1 FROM "order" WHERE order_no = 'ORD-FLOW') THEN
    INSERT INTO party (display_name, party_type, is_organization) VALUES ('Flow Cust','wholesaler',true) RETURNING id INTO party_id;
    -- fixed id so the desk Lifecycle tab can target it deterministically in e2e
    INSERT INTO "order" (id, order_no, type, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, order_date, subtotal_ex_vat, vat_total, total_inc_vat)
      VALUES ('33333333-3333-3333-3333-333333333333','ORD-FLOW','trade',party_id,party_id,mkt,'placed','GBP','stripe','2026-09-01',25000,5000,30000) RETURNING id INTO ord;
    INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES (ord, v_id, 50, 500.00, 5000.00) RETURNING id INTO ol;
    INSERT INTO dispatch (dispatch_no, order_id, date, status) VALUES ('DSP-FLOW', ord, '2026-09-10', 'delivered') RETURNING id INTO dsp;
    INSERT INTO dispatch_line (dispatch_id, order_line_id, qty) VALUES (dsp, ol, 50);
    INSERT INTO revenue_recognition (dispatch_id, order_id, invoice_no, currency, revenue_ex_vat, vat, cogs, gross_margin, ar_transfer_id, vat_transfer_id, cogs_transfer_id, recognized_at)
      VALUES (dsp, ord, 'INV-FLOW', 'GBP', 25000, 5000, 12000, 13000,
              123456789012345678901234567890, 223456789012345678901234567890, 323456789012345678901234567890, '2026-09-15');
    -- an open invoice with a contractual due date so the Finance cash waterfall has a bucket to show
    INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, due_date, status)
      VALUES (ord, 'INV-FLOW', 25000, 5000, 30000, '2026-10-10', 'open');
    -- the events behind the sale, so the desk Lifecycle timeline replays a real spine (not just the cycles)
    INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, payload, occurred_at, origin, status, published_at)
      VALUES (gen_random_uuid(), 'order.placed', 1, 'order', ord, ord::text, '{}'::jsonb, '2026-09-01T09:12:00Z', 'service:order', 'published', now()),
             (gen_random_uuid(), 'dispatch.created', 1, 'order', ord, ord::text, jsonb_build_object('dispatch_no','DSP-FLOW'), '2026-09-10T08:30:00Z', 'service:dispatch', 'published', now()),
             (gen_random_uuid(), 'order.invoiced', 1, 'order', ord, ord::text, jsonb_build_object('invoice_no','INV-FLOW'), '2026-09-10T08:30:05Z', 'service:dispatch', 'published', now()),
             (gen_random_uuid(), 'revenue.recognized', 1, 'order', ord, ord::text, '{}'::jsonb, '2026-09-10T08:30:05Z', 'service:revenue-recognition', 'published', now());
  END IF;
END $$;

-- Supply window + Shelf demo: Volex as a contract manufacturer with a firm-commitment horizon, an open auto-PO
-- proposal, a frozen-window divergence warning, and serials attributed to an account (one activated → on-shelf).
DO $$
DECLARE vlx uuid; v_id uuid; flowcust uuid; loc uuid;
BEGIN
  SELECT id INTO v_id FROM product_variant WHERE sku = 'HV-310';
  SELECT id INTO flowcust FROM party WHERE display_name = 'Flow Cust' LIMIT 1;
  IF NOT EXISTS (SELECT 1 FROM supplier WHERE name = 'Volex') THEN
    INSERT INTO supplier (name, billing_currency, is_contract_manufacturer) VALUES ('Volex','USD',true);
  END IF;
  SELECT id INTO vlx FROM supplier WHERE name = 'Volex' LIMIT 1;

  -- deterministic reset: the approve flow mutates commitments and consumes the proposal — re-seeding must
  -- restore the exact fixture state, or the suite only passes on a virgin database
  DELETE FROM po_proposal WHERE supplier_id = vlx AND product_variant_id = v_id;
  DELETE FROM supply_commitment WHERE supplier_id = vlx AND product_variant_id = v_id;

  INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone)
    SELECT vlx, v_id, d.dt, d.q, d.z FROM (VALUES
      (DATE '2026-07-06', 100, 'frozen'),
      (DATE '2026-09-07', 120, 'flex'),
      (DATE '2026-12-28', 300, 'free')) AS d(dt, q, z);

  INSERT INTO po_proposal (supplier_id, product_variant_id, target_date, demand_qty, committed_qty, available_qty, net_need, proposed_delta, blocked_qty, zone)
    VALUES (vlx, v_id, DATE '2026-09-07', 200, 120, 30, 50, 24, 26, 'flex');

  INSERT INTO commitment_warning (supplier_id, product_variant_id, target_date, zone, committed_qty, demand_qty, delta, source, severity, message)
    SELECT vlx, v_id, DATE '2026-07-06', 'frozen', 100, 150, 50, 'sales_input', 'block',
           'sales_input demand 150 diverges from the frozen firm PO of 100 (delta 50)'
    WHERE NOT EXISTS (SELECT 1 FROM commitment_warning WHERE supplier_id = vlx AND product_variant_id = v_id AND target_date = DATE '2026-07-06');

  SELECT id INTO loc FROM location LIMIT 1;
  IF loc IS NULL THEN INSERT INTO location (code, name) VALUES ('W-SIM','Sim Warehouse') RETURNING id INTO loc; END IF;
  IF flowcust IS NOT NULL AND NOT EXISTS (SELECT 1 FROM serial_unit WHERE company_id = flowcust) THEN
    INSERT INTO serial_unit (serial_no, generation, product_variant_id, company_id, status) VALUES
      ('SHELF-1','v3',v_id,flowcust,'dispatched'),
      ('SHELF-2','v3',v_id,flowcust,'dispatched'),
      ('SHELF-3','v3',v_id,flowcust,'activated');
  END IF;
END $$;

-- Auditability Center demo: an entity with an open September period and a matched AR reconciliation, so the
-- close board shows a period that can be closed + locked over clean books.
DO $$
DECLARE ent uuid; per uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM entity WHERE name = 'HV UK Demo') THEN
    INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
      VALUES ('HV UK Demo','GB','GBP','operating') RETURNING id INTO ent;
    INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
      VALUES (ent,'month','2026-09','Europe/London','open') RETURNING id INTO per;
    INSERT INTO reconciliation (type, period_id, expected, actual, currency, variance, status)
      VALUES ('ar_vs_invoices', per, 30000, 30000, 'GBP', 0, 'matched'),
             ('tb_vs_gl',        per, 30000, 30000, 'GBP', 0, 'matched');
  END IF;
END $$;

-- M-Period (doc 32): the GROUP reporting calendar period the demo entity closes into (keyed to its 2026-09
-- month period), so the Period investigation view lights up — entity status, the INV-FLOW journals/events,
-- and the lineage entry-points all re-project onto this window.
INSERT INTO reporting_calendar (period_key, period_from, period_to)
SELECT '2026-09', '2026-09-01', '2026-09-30'
WHERE NOT EXISTS (SELECT 1 FROM reporting_calendar WHERE period_key = '2026-09');

-- M13 Documents desk: a finalised invoice document for INV-FLOW so the Documents tab lists it, plus finance
-- void/refund rights (edit + approve on order) so the desk void action returns 202 in e2e.
DO $$
DECLARE ent uuid; ord uuid; inv uuid; tmpl uuid; tmplv int;
BEGIN
  SELECT id INTO ord FROM "order" WHERE order_no = 'ORD-FLOW';
  SELECT id INTO inv FROM order_invoice WHERE invoice_no = 'INV-FLOW' ORDER BY issued_at DESC LIMIT 1;
  SELECT id, version INTO tmpl, tmplv FROM document_template
    WHERE document_type = 'invoice' AND status = 'active'
    ORDER BY (jurisdiction IS NOT NULL) DESC, version DESC LIMIT 1;
  IF NOT EXISTS (SELECT 1 FROM entity WHERE name = 'HV UK Demo') THEN
    INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK Demo','GB','GBP','operating');
  END IF;
  SELECT id INTO ent FROM entity WHERE name = 'HV UK Demo' LIMIT 1;
  IF inv IS NOT NULL AND tmpl IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM document WHERE order_invoice_id = inv AND document_type = 'invoice') THEN
    INSERT INTO document (document_type, entity_id, formatted_number, order_invoice_id, order_id, locale, jurisdiction,
                          template_id, template_version, currency, total_amount, render_model, status, storage_uri,
                          content_sha256, issued_at)
      VALUES ('invoice', ent, 'HV-UK-INV-2026-000001', inv, ord, 'en', 'GB', tmpl, tmplv, 'GBP', 30000,
              '{}'::jsonb, 'finalised', 'mem://documents/seed-inv-flow.pdf', 'seedsha', now());
  END IF;
END $$;

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'order', 'view', NULL, '{volume,commercial,pii}', '{}', 'all' FROM role r
WHERE r.name = 'finance'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type='order' AND p.action='view' AND p.section IS NULL);
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'order', 'edit', NULL, '{}', '{}', 'all' FROM role r
WHERE r.name = 'finance'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type='order' AND p.action='edit' AND p.section IS NULL);
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'order', 'approve', NULL, '{}', '{}', 'all' FROM role r
WHERE r.name = 'finance'
  AND NOT EXISTS (SELECT 1 FROM permission p WHERE p.role_id = r.id AND p.object_type='order' AND p.action='approve' AND p.section IS NULL);

-- M13-Tax: a tax demo entity (fixed id the desk quote tester persists against) + a tax_specialist user. CFO
-- governance reuses ceo-e2e (the CFO/approver). A nexus profile (low threshold) so the nexus board has a row.
INSERT INTO entity (id, name, jurisdiction, functional_currency, entity_type)
  VALUES ('33333333-3333-3333-3333-333333333333', 'HV Tax Demo', 'GB', 'GBP', 'operating')
  ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (keycloak_id, name) VALUES ('tax-e2e', 'E2E Tax') ON CONFLICT (keycloak_id) DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r
  WHERE u.keycloak_id = 'tax-e2e' AND r.name = 'tax_specialist'
  AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id);

INSERT INTO nexus_profile (entity_id, jurisdiction, region, threshold_amount, status)
  VALUES ('33333333-3333-3333-3333-333333333333', 'US', 'CA', 100000.0000, 'monitoring')
  ON CONFLICT (entity_id, jurisdiction, region) DO NOTHING;

-- M13-VAT: an admin user (proposes the entity map) + GB → the tax demo entity as the active seller of record.
INSERT INTO app_user (keycloak_id, name) VALUES ('admin-e2e', 'E2E Admin') ON CONFLICT (keycloak_id) DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r
  WHERE u.keycloak_id = 'admin-e2e' AND r.name = 'admin'
  AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id);

INSERT INTO selling_entity (jurisdiction, entity_id, status)
  SELECT 'GB', '33333333-3333-3333-3333-333333333333', 'active'
  WHERE NOT EXISTS (SELECT 1 FROM selling_entity WHERE jurisdiction = 'GB');

-- M-Proof (doc 31): the gl_entry mirror for INV-FLOW's three recognition legs — written OUTSIDE the
-- ORD-FLOW guard and idempotent, so it backfills even on a local pg where ORD-FLOW pre-dates this seed.
-- event_id = the dispatch id (the Journal Walk's join key); two-sided + balanced so the conservation strip
-- ties (Σ DR 420.00 = Σ CR 420.00) and CTRL-LINEAGE-CLOSURE is green on a clean book.
INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, currency, amount_minor, phase, posted, transfer_code, event_id, occurred_at)
SELECT v.tid, v.side, v.key, v.role, 'GBP', v.amt, 'single', true, 1, rr.dispatch_id, '2026-09-15'
FROM revenue_recognition rr
CROSS JOIN (VALUES
  (123456789012345678901234567890::numeric, 'debit',  'AR:flow',      1, 2500000::numeric),
  (123456789012345678901234567890::numeric, 'credit', 'REVENUE:flow', 9, 2500000::numeric),
  (223456789012345678901234567890::numeric, 'debit',  'AR:flow',      1,  500000::numeric),
  (223456789012345678901234567890::numeric, 'credit', 'VAT:flow:GB',  5,  500000::numeric),
  (323456789012345678901234567890::numeric, 'debit',  'COGS:flow',    4, 1200000::numeric),
  (323456789012345678901234567890::numeric, 'credit', 'INV:flow',     3, 1200000::numeric)
) AS v(tid, side, key, role, amt)
WHERE rr.invoice_no = 'INV-FLOW'
  AND NOT EXISTS (SELECT 1 FROM gl_entry g WHERE g.tb_transfer_id = v.tid AND g.side = v.side);
