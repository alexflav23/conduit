-- Supplier cost master: the real per-SKU manufacturer cost (USD) with quarterly volume-discount bands, loaded by
-- the import engine from ingest/cost/*.ndjson (Volex Home 3 Pro today; Luxshare is an ingest away). Drives COGS
-- (USD × the FX register + shipping) and the hedge exposure forecast — the payables-FX picture.
CREATE TABLE supplier_cost (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier            TEXT NOT NULL,            -- Volex | Luxshare
    sku                 TEXT NOT NULL,
    currency            CHAR(3) NOT NULL,         -- USD
    min_qty_per_quarter INT NOT NULL DEFAULT 0,   -- band threshold (0 / 12500 / 18750 / 25000)
    unit_cost           NUMERIC(18,4) NOT NULL,   -- ex-works unit cost in `currency`
    shipping_gbp        NUMERIC(18,4) NOT NULL DEFAULT 0,
    duty_pct            NUMERIC(6,4) NOT NULL DEFAULT 0,
    source              TEXT,
    as_of               DATE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (supplier, sku, min_qty_per_quarter, as_of)
);
CREATE INDEX supplier_cost_sku_idx ON supplier_cost (sku, min_qty_per_quarter);

-- Correct the CM transition: Volex → Luxshare is DECEMBER 2026 (not August). The May–Sep 2026 continuation is
-- therefore entirely Volex (its window precedes the transition).
UPDATE fx_hedge
   SET doc_ref = 'Ebury indicative 1.3290 (vs 1.2700 IB forecast); 50% Volex — CM transition to Luxshare Dec 2026'
 WHERE contract_no = 'Continuation May-Sep 2026';
