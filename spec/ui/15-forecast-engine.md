# 15 — Forecast Engine (`engine`)
Status: MISSING · Roles: finance, ceo, forecaster (`view:forecast`) · Backend: `GET /h6q/*` (coverage/accuracy), forecast tables (`model_accuracy`, `forecast_entry source='model'`), scripting RealBacktest/LivePublish

## Purpose
The self-improving forecast engine's glass box (doc 26): the **rolling-origin backtest** that picks a champion
model per account by lowest error (no hardcoding), its **accuracy ledger** over time, and the **12k+ live model
rows** it writes into the H6Q spine — so a human can see *why* the machine forecast what it did and trust it.

## Layout
- `PageHead` "Forecast Engine".
- **Champion board** — per account/SKU: the selected model, its backtest error, and the runners-up it beat
  (argmin over `model_accuracy`); the depletion-hazard inputs (activations / shelf stock) inline.
- **Accuracy over time** — a chart of forecast vs actual per rolling origin (train ≤ Q → predict Q+1 → score);
  the error trend is the credibility metric.
- **Model-vs-human spine** — the H6Q rows the engine authored (`source='model'`) visually distinguished from
  human capture, with the deviation each earned.

## Components
`PageHead`, a champion-selection table (model · error · rank), an accuracy line chart, model/human badge chips,
`Coverage`, `Money` for revenue projections (layered).

## Data & layers
Unit forecasts are `volume`; the revenue projection (pricing-engine applied) is `commercial`. Error/accuracy is
operational. Collapse the revenue column for a volume-only viewer.

## Actions & states
Read-mostly (the engine runs in the backtest loop / LivePublish). *Empty:* "no backtest run yet." Surface the
last backtest SHA + timestamp (reproducibility — same data+code ⇒ same champion).

## Design notes
The hero is **"the machine earned this forecast"** — show the champion *beating* its rivals, and the accuracy
trend bending toward truth. Make model vs human legible at a glance. This is the screen that turns a black-box
forecast into one finance will sign.
