-- M-Forecast follow-on (doc 35 §4.1): a per-origin snapshot of the CENSORED depletion state (shelf stock +
-- activation velocity = the depletion rate), so the rate itself can be diffed across forecast runs — not just
-- the current live state. Immutable, idempotent per origin (PK + the loader's ON CONFLICT DO NOTHING), exactly
-- like forecast_run / model_accuracy. Captured by BacktestEngine.runOrigin (source='backtest') at each origin
-- and by LiveForecast.publish (source='live') at the live origin. Reproducible from the serial/activation log.
CREATE TABLE depletion_snapshot (
    origin_month       DATE NOT NULL,
    company_id         UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    shelf_stock        NUMERIC(18,4) NOT NULL,   -- shipped − activated, as-of the origin
    velocity_ewma      NUMERIC(18,4) NOT NULL,   -- the 6-month activation rate (units/month) = the depletion rate
    velocity_3m        NUMERIC(18,4),            -- the 3-month sell-through rate
    runway_days        NUMERIC(18,4),            -- shelf / rate, in days of cover
    source             TEXT NOT NULL DEFAULT 'backtest',  -- backtest | live
    captured_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (origin_month, company_id, product_variant_id)
);
CREATE INDEX depletion_snapshot_co_idx ON depletion_snapshot (company_id, product_variant_id);
