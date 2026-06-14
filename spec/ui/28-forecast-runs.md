# 28 — Forecast Runs (run tracking, report & diff)

**Route:** `runs` (Plan group, after the Forecast Engine) · **Gate:** `view:pipeline_coverage`
**Backend:** `GET /api/v1/forecast/runs` · `/runs/{origin}/report` · `/runs/diff?from=&to=`
(`ForecastRunRoutes` → `ForecastRunReportRepo` + pure `RunDiff`).

## Purpose
Make the self-improving tournament (doc 26) **legible to a human**: every forecast origin is an immutable,
idempotent record (`forecast_run` + `model_accuracy` + `policy_selection`), so we can show *how the forecast
evolved between runs and what the basis for that evolution was* — without re-running anything.

## Surfaces
1. **Run timeline** (`fr-runs`) — one row per origin: accounts scored, forecast vs actual units, total-level
   error (chip: ok ≤15% / warn ≤40% / exception), model-run count, last-scored date. Newest first.
2. **Per-run report** (`fr-report`, opened from a row) — the comprehensive artefact:
   - **Stats:** accounts, forecast/actual units, total-level error, structural-champion share.
   - **Outturn by segment** (`channel_comparables`).
   - **Champion model mix** — how many accounts each policy won.
   - **Model bake-off** (`fr-accuracy`) — every model's scored mean/total abs error (lowest wins the account);
     structural vs statistical badge. *This is the basis a champion was chosen on.*
   - **Run provenance** — model, version, purpose, **data SHA + params hash** (the pins), ran-at.
3. **Compare two runs** (`fr-diff`) — pick `from`/`to` →
   - a **human-readable narrative** (coverage change, error improvement/worsening, champion switches, the
     shift toward structural models),
   - metric deltas (error Δ pts, accounts added/dropped, champion changes),
   - the **champion-change table** (account → from-policy → to-policy).

## Data-layer / honesty
Read-only; nothing is recomputed in the UI — the figures are the stored, reproducible facts. The diff/narrative
is computed by the pure `RunDiff` (unit-tested), the single source of truth the API serves and the spec pins.
