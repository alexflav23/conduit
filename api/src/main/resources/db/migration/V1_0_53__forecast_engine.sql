-- M-Forecast (doc 26 §7): the rolling-origin learning loop's persistence. Runs and predictions are IMMUTABLE,
-- append-only facts ("on origin O, model M predicted X") — the ledger discipline applied to forecasts, so backtests
-- stay honest forever. The champion is DERIVED (argmin over model_accuracy), never a stored status. The account
-- state is a rebuildable projection. exogenous_series carries known_at so a backtest can only see what was knowable
-- at its origin (no leakage).

CREATE TABLE forecast_run (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_month   DATE NOT NULL,            -- the censoring cut: trained on data strictly BEFORE this month
    horizon_months INTEGER NOT NULL,
    model_key      TEXT NOT NULL,
    model_version  INTEGER NOT NULL,
    params_hash    TEXT,                     -- pins the params; (data SHA, model, params) reproduces the run
    data_sha       TEXT,                     -- git SHA of the ingest snapshot, when applicable (doc 26 §3a)
    purpose        TEXT NOT NULL DEFAULT 'backtest',  -- backtest | live
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (origin_month, model_key, model_version, purpose)  -- idempotent loop: one run per (origin, model)
);

CREATE TABLE forecast_run_prediction (
    run_id             UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    company_id         UUID NOT NULL,
    product_variant_id UUID,
    market_id          UUID,
    period_month       DATE NOT NULL,
    qty                NUMERIC(18,4) NOT NULL,
    PRIMARY KEY (run_id, company_id, product_variant_id, period_month)
);
CREATE INDEX forecast_run_prediction_period_idx ON forecast_run_prediction (company_id, period_month);

-- The error ledger the learning selects from: one row per scored (run, account, period).
CREATE TABLE model_accuracy (
    run_id             UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    company_id         UUID NOT NULL,
    product_variant_id UUID,
    model_key          TEXT NOT NULL,
    origin_month       DATE NOT NULL,
    period_month       DATE NOT NULL,
    horizon            INTEGER NOT NULL,      -- months ahead of origin
    forecast_qty       NUMERIC(18,4) NOT NULL,
    actual_qty         NUMERIC(18,4) NOT NULL,
    abs_error          NUMERIC(18,4) NOT NULL,
    PRIMARY KEY (run_id, company_id, product_variant_id, period_month)
);
CREATE INDEX model_accuracy_champion_idx ON model_accuracy (company_id, model_key);

-- The soft-real-time projection (doc 26 §6) — rebuildable from the activation/dispatch log, never authoritative.
CREATE TABLE account_forecast_state (
    company_id         UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    shelf_stock        NUMERIC(18,4) NOT NULL DEFAULT 0,
    velocity_ewma      NUMERIC(18,4) NOT NULL DEFAULT 0,  -- units/month
    runway_days        NUMERIC(18,4),
    reorder_point_days NUMERIC(18,4),
    last_event_at      TIMESTAMPTZ,
    PRIMARY KEY (company_id, product_variant_id)
);

-- Exogenous regressors (doc 26 §3 — e.g. uk_car_sales). known_at enforces censoring: a backtest at origin O may
-- only read rows with known_at < O.
CREATE TABLE exogenous_series (
    series_key   TEXT NOT NULL,
    period_month DATE NOT NULL,
    value        NUMERIC(18,4) NOT NULL,
    known_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (series_key, period_month, known_at)
);
