-- M-Forecast: the lookup indexes the snapshot loader and the backtest engine live on once real MRPeasy
-- volume (50k orders / 17k parties / 129k serials) is seeded. Postgres does not index FKs automatically;
-- the loader's party-by-name lookup was a sequential scan per row (measured: quadratic, hours).
CREATE INDEX IF NOT EXISTS party_display_name_idx ON party (display_name text_pattern_ops);
CREATE INDEX IF NOT EXISTS order_sold_to_created_idx ON "order" (sold_to_party_id, created_at);
CREATE INDEX IF NOT EXISTS order_line_order_idx ON order_line (order_id);
CREATE INDEX IF NOT EXISTS order_line_variant_idx ON order_line (product_variant_id);
CREATE INDEX IF NOT EXISTS serial_unit_company_variant_idx ON serial_unit (company_id, product_variant_id);
CREATE INDEX IF NOT EXISTS dispatch_order_idx ON dispatch (order_id);
