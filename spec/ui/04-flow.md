# 04 — Flow (waterfall + ledger) (`flow`)
Status: COVERED ✅ (refresh) · Roles: finance, ceo, supply · Backend: `GET /h6q/waterfall?variant&period`, `GET /h6q/ledger`

## Purpose
The 7-stage demand→cash waterfall (doc 20 D9): forecast → CM-committed → produced → delivered → ordered →
shipped → revenue, where the **gaps between stages are the story**, and every figure traces to TigerBeetle.

## Layout
- `PageHead` "Flow" + variant + period.
- The **7-stage waterfall viz** — stages don't equate; the drop between them is the insight (design true
  waterfall bars, not a funnel).
- **Ledger totals + drill**: each figure → its TigerBeetle transfers (`AuditRef`) — the auditability promise here.

## Components
`PageHead`, a waterfall chart, a ledger table with `AuditRef` drill, `Money` (mono).

## Data & layers
Unit stages are `volume`; revenue is `commercial`; COGS/margin is `profitability` (collapse). The drill to TB is
the proof every stage figure is real.

## Actions & states
Load by variant/period · drill a figure to its transfers. *Empty:* "no data for this variant/period."

## Design notes
The hero is the **gap analysis** — where does forecast leak before it becomes revenue. Make the stage-to-stage
drops visceral, and every number one click from its ledger evidence.
