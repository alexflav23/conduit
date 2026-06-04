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
