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
