# 26 — Per-Account Revenue Forecasting (the self-improving engine)

> **✅ Slices 1–4 implemented (M-Forecast core + live engine).**
> **1–2 — series + registry:** `forecast.DemandModel` (seasonal_naive / ewma / croston_sba / **depletion** /
> seasonal_ets — pure, deterministic, ScalaCheck-verified) + `DemandSeriesRepo` (censored account×SKU monthly
> series as-of any origin, shelf/velocity context from the serial log; data-driven forecastability ≥3 orders).
> **3 — the loop:** `BacktestEngine` + V1_0_53 (`forecast_run`/`forecast_run_prediction` immutable + UNIQUE per
> origin×model; `model_accuracy`; champion = pure argmin, deterministic tie-break). `ForecastLoopSuite`'s headline
> IS the acceptance sentence: train ≤Q2'25 → predict Q3'25 → score vs actuals → a DIFFERENT champion per demand
> shape (exact-seasonal → seasonal family at ZERO error; shifted-lumpy rejects it); censoring/immutability/
> idempotency asserted; a new origin extends the learning.
> **4 — the live engine:** `LiveForecastService` (the champion publishes into the H6Q spine as
> `forecast_entry(source='model')`, append-only supersession — humans/hyperview/models scored on identical terms);
> `RunwayService` + **`PlacementConsumer`** (M8's missing `athena-placement-versioned` wire, subscription
> `conduit-placement-versioned-subscription-1`) — activations update shelf/velocity/runway and fire
> `forecast.account.runway` at the reorder point; `RevenueProjectionService` — units × the customer's tier net of
> the expected per-unit retrospective rebate (contract-consistent by construction). `LiveForecastSuite` green.
> *Fixed en route: a latent M8 bug — `ActivationService` nulled existing `company_id` when an activation arrived
> without one (now COALESCE; attribution is never erased).*
> *Remaining: slice 5 (NDJSON snapshot ingest + HubSpot/ghost-busters scrapers + car-sales elasticity + sector
> rollup) and slice 6 (desk surfaces).*

**Status:** design spec + build plan (M-Forecast). The capstone overlay on everything built so far: a
**definitive, self-improving model that automatically predicts revenue per account from historical patterns** —
per SKU, per geography — learning by **rolling-origin backtesting** (train on data ≤ Q2'25, predict Q3'25, score
against Q3'25 actuals; repeat across every historical origin) with **no hardcoding**: model selection, weights and
calibration all fall out of measured error.

> **The structural edge.** We directly observe our B2B customers' **sell-through**: every activation (ghost-busters
> / `athena-placement-versioned`, already ingested in M8) is a real charger going live, attributed to the customer
> who bought it (MRPeasy serial → customer). Shipped-minus-activated = that customer's **shelf stock, exactly** —
> so a reorder is not a curve-fit guess but a **runway with a date on it**. Most manufacturers forecast sell-in
> blind; we see the customer's shelf deplete in soft real time.

---

## 1. Populations — who gets a per-account model

- **B2B repeat accounts** (wholesale, installers, electricians, energy, automotive — `party.sector`/channel):
  per-account model. They reorder; their history + shelf state is informative.
- **Natural persons / D2C** (one-off buyers): **never per-account** — an aggregate **channel model**
  (the hyperview pattern; its Prophet runs inside Superset with no API/backtest, so Conduit fits its own
  channel-level seasonal model on the same series shape, scored honestly in the same loop).
- Classification is **data-driven, not a flag**: an account is *forecastable* when it has ≥ N (default 3) distinct
  order events; otherwise its volume contributes to its channel aggregate. Re-evaluated each run — an installer's
  3rd order automatically promotes them.

## 2. The grain

- **The model forecasts UNITS** at `(account × SKU × geography(market) × month)` — H6Q's existing grain
  (`forecast_entry`), extended with model sources.
- **Revenue is a PROJECTION, never stored as truth** (the doc-12 §8.3 rule — H6Q doesn't own money):
  `units × the account's contracted tier price at their PROJECTED cumulative position, net of expected rebate`
  through the M-Pricing engine (`TierResolver`/`RebateService.expectedRebate`). The revenue forecast is therefore
  **contract-consistent by construction** — it can never quote a price the customer wouldn't actually pay.
- Roll-ups reuse the H6Q dual-axis algebra (`Coverage`) + add the **sector** level (party.sector, M-Pricing slice 4);
  per-SKU × per-geography totals reconcile bottom-up (account×SKU) against top-down (channel×geo seasonality).

## 3. The data foundations (from the estate survey)

| Input | Source | Status |
|---|---|---|
| Activations (sell-through) | `athena-placement-versioned` → M8 `ActivationService`; `serial_unit.company_id`, `activation.recorded` | **live in Conduit** |
| Shelf stock per account | shipped (dispatch/serial genealogy M7) − activated; historical bootstrap via the ghost-busters SQL (V3-only `0301%`, exclude `-rtn`, `activation ≥ ship` clock-skew guard) | derivable |
| Median shelf time / velocity | ghost-busters `/api/stock/dashboard` pattern — recomputed natively in Conduit | derivable |
| Historical B2B demand 2021→cutover | **HubSpot deals** via the athena private-app token (`HYPERVOLT_HUBSPOT_API_KEY`); v3 search (`hs_createdate ≥ 2021-01-01`, epoch-ms!) + cursor paging + 100-id batch reads; **pipeline allowlist** (UK Retail, UK Retail - Direct, Wholesale/Installers/Distributors; exclude Investors/BD/New-Installer-Sign-Up/Enterprise); party stitch on `external_refs.hubspot_id`; precision's 6-path deal→order cascade; split-deals→tranches | backfill (slice 4) |
| Conduit-era demand | `order`/`order_line`/dispatch — native | **live** |
| Market seasonality driver | UK total-car-sales / EV-registrations series (the H6Q spreadsheet pattern — today spec-prose, not code): stored as an **exogenous series**, historical **elasticity fitted per channel**, forward values used as a regressor | net-new (slice 4) |
| D2C channel series | Conduit retail-channel orders (hyperview's series shape; pay-later ≈62.5% / card ≈99% conversion weighting where applicable) | live |

### 3a. Supersession & the snapshot-ingest mechanism (design decision)

**Conduit supersedes MRPeasy, HubSpot and the rest** — they are *historical sources*, not live dependencies. Even
where we keep publishing outward, those systems are secondary. So the engine takes **no runtime dependency on any
external API**. Instead:

- **Snapshot ingest, in-house:** external history arrives as **NDJSON files committed to git**
  (`ingest/<source>/<dataset>.ndjson` — e.g. `ingest/hubspot/deals.ndjson`, `ingest/ghostbusters/serials.ndjson`,
  `ingest/ghostbusters/activations.ndjson`, `ingest/exogenous/uk_car_sales.ndjson`). One JSON object per line, a
  declared schema per dataset, reviewable in a diff like any code change.
- **Scrapers are one-shot, out-of-band tools** (`scripting/` module): they call the source (the athena HubSpot
  token, the ghost-busters/Athena SQL, an MRPeasy export) and **emit NDJSON** — they never write to Conduit
  directly. Worst case we hand-build a scraper per source; the contract is only "produce the file."
- **The loader is deterministic + idempotent:** NDJSON → Conduit rows/events with deterministic ids
  (`migration_record` dedupe, the doc-18 machinery) — re-running a file is a no-op; loading a corrected file is a
  new, auditable change.
- **Reproducibility bonus:** a backtest run records the **git SHA of the data snapshot** it trained on — the
  (data, model, params) triple is fully pinned, so any historical score re-performs exactly, forever.
- Conduit-era data (orders, dispatches, activations) is native and needs no ingest; the NDJSON path exists for
  the 2021→cutover bootstrap and for any transition-period refresh (re-scrape → re-commit → re-load).

## 4. The model — timing × size, depletion-aware

B2B ordering is lumpy; a single continuous curve is the classic mistake. Decompose:

- **Timing — the depletion hazard.** Per account: `shelf_stock ÷ seasonally-adjusted activation velocity` (EWMA)
  = **runway**. Reorder probability concentrates as runway approaches the account's *historical reorder point*
  (some reorder at 4 weeks cover, some at zero — measured per account, never assumed).
- **Size** — the account's historical order-size distribution (per SKU), updated each order.
- **Seasonality** — multiplicative monthly factors fitted at channel level (where they're statistically stable)
  + the car-sales elasticity regressor; applied to account velocity so a market dip is attributed to the tide,
  not the account.
- **Model registry** (all pure Scala, deterministic, no Python dependency; a future Prophet sidecar is just
  another registry entry):
  | key | family | for |
  |---|---|---|
  | `seasonal_naive` | same-month-last-year × trend | the baseline every model must beat |
  | `ewma` | exponentially-weighted level | dense, stable accounts |
  | `croston_sba` | Croston/SBA intermittent demand | lumpy/sparse accounts |
  | `depletion` | shelf-runway hazard × size distribution | accounts with activation telemetry (the edge) |
  | `seasonal_ets` | Holt-Winters additive-trend multiplicative-seasonality | channel aggregates / D2C |
- **Ensembling**: per (account|channel), the live forecast is the **backtest champion** (or an inverse-error
  weighted blend) — selected mechanically by the loop, per §5. Humans (`manual`) and `hyperview` remain sources in
  `forecast_entry`; the accuracy ledger scores **humans and models on identical terms**.

### 4a. Structural calibrations (product-owner steer, iteration 6) — beyond curve-fitting

- **Order-book conditioning (B2B).** Incoming/open POs are near-certain near-term revenue: the forecast conditions
  on the **open deal/PO book as it stood at the origin** — honestly reconstructible (a deal with `createdate <
  origin` and not closed by the origin was open at the origin), converted via **stage/age conversion rates fitted
  on pre-origin cohorts only**. Live: the book = HubSpot open deals + Conduit orders placed-not-dispatched.
- **Stock-depletion calibration (B2B).** The shelf runway (shipped − activated) gates reorder *timing* — the
  depletion family exists; calibrate per account on the real serial telemetry.
- **Retail funnel decomposition (D2C).** Each component estimated **singly**, then **cumulated**:
  the sheer passage of time (conversion-by-age curves), conversion per payment channel (pay_later vs card —
  fitted, not hardcoded), refund rates per fulfilment type (direct_buy vs paid_install), and the abandoned-cart
  recovery rate. Composed: `created volume × conv(payment mix, age) − refunds + recovery × abandoned`.
- All three enter the same tournament and are scored by the same rolling-origin ledger — structure competes with
  statistics on measured error, never by assertion.

## 5. The learning loop — rolling-origin backtesting (the core mechanism)

```
for each origin O in [first-usable-quarter … last-closed-quarter]:
  snapshot  = the world strictly as-of O          (censored queries over the immutable order/activation log)
  for each model m in the registry:
    fit m on snapshot → predict O…O+h per (account × SKU × geo × month)
    store forecast_run(origin=O, model=m, params_hash, model_version)   -- IMMUTABLE, append-only
  score predictions vs actuals → model_accuracy(account, model, origin, horizon, mape, bias, pinball)
champion(account) = argmin over models of backtest error           -- pure selection, no hardcoding
```

**Honesty rules (enforced in code, tested):**
1. **No leakage** — every feature is computed by *censored* queries (`created_at < O`, activation `< O`, the
   car-sales values *known at O*). The event log + facts-only schema make as-of reconstruction exact.
2. **Predictions are append-only facts** — a `forecast_run` is never edited (the ledger discipline applied to
   forecasts); re-running an origin with a new model version is a NEW run; old runs stay scoreable forever.
3. **Deterministic reproducibility** — (snapshot cut, model key, params hash) fully determines a run; any historical
   score re-performs, like a financial control.
4. **Score at the served grain** — account×SKU for B2B, channel for D2C, AND the reconciled roll-ups (so the
   hierarchy can't hide offsetting errors). Quarter-close automatically extends the loop by one origin: scoring,
   re-ranking and champion selection re-run with zero human input. That IS the self-improvement.

## 6. Soft real time — the lambda shape

- **Streaming layer** (cheap, per event): the existing `activation.recorded` consumer path updates per-account
  state — `shelf_stock − 1`, velocity EWMA, runway — and emits `forecast.account.runway` when the runway crosses
  the account's reorder point (the sales signal: "Octopus reorders in ~3 weeks").
- **Batch layer** (nightly): full refit + champion forecasts written as `forecast_entry(source='model',
  model_version=…)` → existing `CoverageProjector` fan-out; (quarterly) the backtest loop extends.
- State is a **projection** (rebuildable from the log), never authoritative.

## 7. Schema (deltas)

```
forecast_model            (key, family, params JSONB, version, status)            -- the registry
forecast_run              (id, origin, horizon_months, model_key, model_version, params_hash,
                           cutoff_at, created_at)                                 -- immutable run header
forecast_run_prediction   (run_id, company_id, product_variant_id, market_id, period_month,
                           qty_p20, qty_p50, qty_p80)                             -- immutable predictions
model_accuracy            (run_id, company_id, model_key, origin, period_month, horizon,
                           forecast_qty, actual_qty, ape, bias, basis sell_in|sell_through) -- the error ledger
account_forecast_state    (company_id, sku: shelf_stock, velocity_ewma, runway_days,
                           reorder_point_days, last_event_at)                     -- streaming projection (rebuildable)
exogenous_series          (key e.g. 'uk_car_sales', period_month, value, known_at) -- censored regressor (known_at!)
champion                  = derived (argmin over model_accuracy), never stored as status
```
`forecast_entry` gains nothing — model output lands as `source='model'` rows (the column exists).
Revenue: **no table** — projected at read time through M-Pricing.

## 8. Milestone — M-Forecast (build order)

1. **Demand series + censoring** *(the bedrock)*: `DemandSeries` projection — per (account, SKU, market, month)
   units from orders/dispatches (+ activations as sell-through basis), **as-of any origin** (censored); the
   forecastable-account classifier. Property: censored(O) ≡ full-history truncated at O.
2. **The model registry + families** (pure, deterministic): seasonal_naive, ewma, croston_sba, depletion,
   seasonal_ets; ScalaCheck properties (determinism, non-negativity, seasonal-shape recovery on synthetic data).
3. **The rolling-origin loop**: backtest engine + immutable `forecast_run`/predictions + `model_accuracy` +
   champion selection. **Acceptance = the user's sentence**: train ≤ Q2'25 → predict Q3'25 → score vs Q3'25
   actuals → prove the loop ranks the better model above the worse one, and that adding an origin re-ranks.
4. **Live engine**: nightly champion forecasts → `forecast_entry(source='model')` → coverage fan-out; the
   activation-driven runway state + `forecast.account.runway` events; **revenue projection endpoint**
   (units × tier-at-projected-position net expected rebate).
5. **History + regressors (snapshot ingest, §3a)**: the NDJSON loader (deterministic, idempotent,
   `migration_record`) + dataset schemas for `hubspot/deals`, `ghostbusters/serials|activations`,
   `exogenous/uk_car_sales`; the out-of-band scrapers in `scripting/` (HubSpot search→NDJSON with the pipeline
   allowlist + party stitch; ghost-busters SQL→NDJSON); car-sales elasticity fit; **sector** level in the rollup.
6. *(later)* Desk surfaces (runway worklist, accuracy leaderboard humans-vs-models), Prophet-sidecar registry
   entry if it earns its place in the backtest.

**Reconciliations:** doc 12 (model source + sector level + accuracy extensions), doc 24 (revenue projection uses
expected tier/rebate), doc 18 (HubSpot backfill rides migration machinery), CLAUDE.md §9 (M-Forecast).
