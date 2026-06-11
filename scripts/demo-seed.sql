-- demo: a CEO dev user, an energy-sector account with seasonal history, stock + activations
INSERT INTO app_user (keycloak_id, name) VALUES ('demo-ceo', 'Demo CEO') ON CONFLICT (keycloak_id) DO NOTHING;
INSERT INTO role_assignment (user_id, role_id)
SELECT u.id, r.id FROM app_user u, role r WHERE u.keycloak_id='demo-ceo' AND r.name='ceo'
ON CONFLICT DO NOTHING;

INSERT INTO product_family (code, name) VALUES ('HOME3PRO', 'Home 3 Pro') ON CONFLICT (code) DO NOTHING;
INSERT INTO product_variant (family_id, sku, generation, product_class)
SELECT id, 'HV3PROAA', 'v3', 'charger' FROM product_family WHERE code='HOME3PRO'
ON CONFLICT (sku) DO NOTHING;
INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
SELECT 'customer', id, 'GBP', 'GB_STANDARD', 600.00, 1, 'active' FROM product_variant WHERE sku='HV3PROAA'
ON CONFLICT DO NOTHING;

INSERT INTO party (display_name, party_type, is_organization, sector)
VALUES ('Octopus Energy (demo)', 'wholesaler', true, 'energy')
ON CONFLICT DO NOTHING;

-- 30 months of seasonal demand: 100/mo, 250 in Jun-Aug
DO $$
DECLARE m date; buyer uuid; vid uuid; oid uuid; q int;
BEGIN
  SELECT id INTO buyer FROM party WHERE display_name='Octopus Energy (demo)';
  SELECT id INTO vid FROM product_variant WHERE sku='HV3PROAA';
  m := date '2023-01-01';
  WHILE m <= date '2025-06-01' LOOP
    q := CASE WHEN EXTRACT(MONTH FROM m) IN (6,7,8) THEN 250 ELSE 100 END;
    INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency,
                         payment_method, subtotal_ex_vat, vat_total, total_inc_vat, created_at)
    VALUES ('DEMO-' || to_char(m,'YYYYMM'), 'trade', buyer, buyer, 'placed', 'GBP', 'invoice',
            q*600, q*120, q*720, m + interval '14 days')
    ON CONFLICT (order_no) DO NOTHING
    RETURNING id INTO oid;
    IF oid IS NOT NULL THEN
      INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, line_total_inc_vat)
      VALUES (oid, vid, q, 600.00, q*120, q*720);
    END IF;
    m := m + interval '1 month';
  END LOOP;
END $$;

-- stock telemetry: 120 shipped 2 months ago, 90 activated over the trailing 90 days → shelf 30
DO $$
DECLARE buyer uuid; vid uuid; oid uuid; did uuid; i int;
BEGIN
  SELECT id INTO buyer FROM party WHERE display_name='Octopus Energy (demo)';
  SELECT id INTO vid FROM product_variant WHERE sku='HV3PROAA';
  SELECT id INTO oid FROM "order" WHERE order_no='DEMO-202505';
  INSERT INTO dispatch (dispatch_no, order_id, date, delivered_at)
  VALUES ('DEMO-DISPATCH-1', oid, now() - interval '60 days', now() - interval '55 days')
  ON CONFLICT (dispatch_no) DO NOTHING RETURNING id INTO did;
  IF did IS NOT NULL THEN
    FOR i IN 1..120 LOOP
      INSERT INTO serial_unit (serial_no, generation, product_variant_id, dispatch_id, company_id, activated_at, status)
      VALUES ('0301DEMO' || lpad(i::text, 8, '0'), 'v3', vid, did, buyer,
              CASE WHEN i <= 90 THEN now() - ((i % 90 + 1) || ' days')::interval ELSE NULL END, 'dispatched');
    END LOOP;
  END IF;
END $$;
SELECT 'seeded' AS status;
