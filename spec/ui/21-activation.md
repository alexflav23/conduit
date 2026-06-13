# 21 — Activation ingest + warranty provision (`activation`)
Status: MISSING · Roles: finance, ceo, fulfilment (`view:activation`/`warranty`) · Backend: ActivationService (athena-placement-versioned ingest), WarrantyService (provision register)

## Purpose
The sell-through + warranty surface (doc 07 M8): charger **activations** ingested first-write-wins from the UFE
placement stream (the real "a unit went live at a customer"), and the **warranty provision** each activation
opens (straight-line release over the warranty term) — the accrual side of after-sales.

## Layout
- `PageHead` "Activation & Warranty".
- **Activation feed**: serial · activated-at · installer · owner · market — the sell-through signal (distinct
  from sell-in/dispatch); first-write-wins (a later version doesn't override the first).
- **Warranty provision register**: per serial/cohort — estimated provision, released-to-date (straight-line),
  outstanding; the release schedule.
- Activation → its provision (an `AuditRef`); warranty claims (from Returns) draw the provision down.

## Components
`PageHead`, activation feed table, a warranty provision register with a release-progress `LoadBar`, `Money`
(provision — profitability), status `Chip`s, `AuditRef` to the ledger.

## Data & layers
Activation identity is `volume`; the warranty provision money is `profitability` (collapse). The warranty clock
starts at activation (not dispatch) — make that explicit.

## Design notes
The hero distinction is **sell-in vs sell-through** — dispatch put it on a shelf; activation is the real sale
signal that feeds H6Q depletion + warranty. Show the provision releasing over time (the after-sales liability
winding down), and a claim drawing it down.
