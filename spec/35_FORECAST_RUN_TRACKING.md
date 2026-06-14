# 35 — Forecast run tracking, reporting & the browsable delta

**Milestone:** M-Forecast follow-on (doc 26 §7). **Status:** BUILT.
**Desk:** `Forecast runs` tab (Plan group). **UI brief:** `spec/ui/28-forecast-runs.md`.
**Gate:** `view:pipeline_coverage` (the H6Q board gate). **Money/no-float:** units only here; `BigDecimal` throughout.

## 1. Why
The forecast engine (doc 26) is a self-improving tournament. Every cycle it scores models against censored
history and materialises a champion per account. That history is already stored as **immutable, idempotent
facts** — but it wasn't *legible*. This milestone makes it human-readable: *what did each run produce, on what
basis, and how did it evolve from the last one* — with a **complete browsable delta** down to any dimension.

## 2. The data it reads (already stored — nothing new persisted)
| Table | Grain | Role here |
|---|---|---|
| `forecast_run` | (origin, model, version, purpose) — `UNIQUE` | the run provenance: model set, `data_sha`, `params_hash`, timeline |
| `model_accuracy` | (run, account, period) | the **bake-off** — scored abs error per model (WHY a champion won) |
| `policy_selection` | (origin, company) — `UNIQUE` | the **champion** per account: `policy_key`, `weights`, forecast vs actual |
| `channel_comparables` (view) | segment × origin | per-segment outturn |
| `party` (+ `market`) | — | the dimensions a delta browses by (segment, channel_id, market_id→name) |

A "run" in the human sense = a **forecast origin**. Because every fact is keyed and append-only, every figure
below is **reproducible** and a diff between two origins is deterministic.

## 3. Surfaces (REST — `ForecastRunRoutes`, read model `ForecastRunReportRepo`, pure core `RunDiff`)
- `GET /api/v1/forecast/runs` — the **timeline**: per origin → accounts, forecast/actual units, total-level
  error, model-run count, last-scored.
- `GET /api/v1/forecast/runs/{origin}/report` — the **comprehensive report**: stats (incl. structural-champion
  share), by-segment outturn, champion model-mix, the **model bake-off** (the basis), and **run provenance**
  (model/version/data SHA/params/ran-at).
- `GET /api/v1/forecast/runs/diff?from={o}&to={o}&group_by={segment|channel|market}&market={uuid?}` — the
  **run-to-run diff**: headline stats both sides, error delta, accounts added/dropped, champion changes
  (account → from-policy → to-policy), policy-mix shift, a **human-readable narrative**, and the
  **browsable breakdown** (§4) + the market list for its filter.

## 4. The browsable delta (the spec ask)
`group_by` selects the axis (`segment` | `channel` | `market`); the optional `market` filter scopes to one
market — so **"channel by channel for each market"** is `group_by=channel` + a `market` filter. Each cell
carries `from`/`to` total-level error, the **error Δ**, forecast Δ, actual Δ and account count; rows sort by
`|error Δ|` so the biggest movers surface first. The per-cell error is `RunDiff.totalLevelErrorPct` — the same
served-grain definition as the headline, so page and spec cannot drift. (No channel *name* table exists today;
the channel axis labels by `channel_id` — swap to a name source if one lands.)

### 4.1 Account-level delta + the live depletion drill
`GET /api/v1/forecast/runs/diff/accounts?from=&to=&market=&segment=&channel=&limit=` — per account (in either
origin): champion change (from-policy → to-policy), forecast Δ, error Δ, **and the live depletion state from
`account_forecast_state`** — on-shelf stock, the **live depletion rate** (`velocity_ewma`, units/mo), runway
days, reorder point. Sorted by `|forecast Δ|` so the **enterprise accounts that moved most surface first**;
optional market/segment/channel filters scope the drill. This is where stock + depletion matter most.

`GET /api/v1/forecast/runs/account/{company}?origin=` — the per-account drill: the **participators** (the
per-model bake-off at that origin — who competed, their mean abs error, the winner flagged) + the account's
**live per-SKU depletion** (stock · rate · runway). The desk renders this inline under the account row.

### 4.2 Per-origin depletion snapshots (the historical rate delta)
`depletion_snapshot (origin_month, company_id, product_variant_id → shelf_stock, velocity_ewma, velocity_3m,
runway_days, source)` — an **immutable, idempotent** capture of the **censored** depletion state at each
origin (same definition as `BacktestEngine.depletionContext`, run set-based for the whole population).
Captured by **`BacktestEngine.runOrigin`** (`source='backtest'`) at every origin and by
**`LiveForecast.publish`** (`source='live'`) at the live origin; `DepletionSnapshotRepo.snapshot(origin, source)`
is `ON CONFLICT DO NOTHING`, so an origin's snapshot is taken once and never rewritten.

This makes the account view a **true rate delta over time**: per account, the from→to **shelf Δ** and
**depletion-rate Δ** (`velocity_ewma`), and per SKU in the drill (shelf from→to, rate from→to, rate Δ). The
account row sorts by `|forecast Δ|`; the desk shows on-shelf (to), shelf Δ, rate/mo (to), **rate Δ**, runway.
Reproducible from the serial/activation log — a backtest re-run converges to the same snapshots.

## 5. The narrative (RunDiff, pure + unit-tested)
Bullets a human reads: coverage change (accounts added/dropped), total-level error improvement/worsening,
champion switches (and how many moved onto a **structural** model — real telemetry — vs reverted to a
statistical shape), and the structural-champion share shift. Structural classification is by token
(`depletion`/`sell_through`/`order_book`/`mrp_order_book`/`retail_funnel`/`pantry_reversal`), blend-aware.

## 6. Tests
- `RunDiffSpec` (Docker-free): classification, stats (total-level error nets offsetting accounts),
  champion-change/added/dropped detection, error-delta sign.
- `e2e/forecast-runs.spec.ts`: timeline → report (bake-off shows depletion beating runrate3) → diff narrative
  ("improved") + champion-change table + the browsable breakdown (segment, re-grouped by market). Seed:
  origins 2026-03/2026-06 where an account's champion moves runrate3→depletion and error improves.

## 7. Out of scope (later)
A channel name/dimension table; a 2-level pivot (market × channel in one grid) beyond filter-and-group;
exporting a run report as a PDF document (the document engine could render it — a natural extension).
