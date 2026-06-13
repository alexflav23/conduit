# 07 — Finance (P&L / cash / credit) (`finance`)
Status: COVERED ✅ (refresh) · Roles: finance, ceo (`view:gl_entry`, layered) · Backend: `GET /finance/pnl|cash-waterfall`, `GET/PUT /parties/{id}/credit-terms`

## Purpose
The finance read-models (doc 20 D9/D10): P&L by market/period, the cash waterfall, and the credit-terms editor —
the CFO's running view, with margin collapsing for roles without the `profitability` layer.

## Layout
- `PageHead` "Finance" + period.
- **P&L** by market/period: revenue · COGS · **margin** (collapses for non-profitability viewers — design the
  collapsed state explicitly, never £0).
- **Cash waterfall**: opening → in → out → closing.
- **Credit-terms editor**: per party — terms days, limit; a maker mutation (confirm + audit affordance).

## Components
`PageHead`, P&L table with a layer-collapsed margin column, cash waterfall, a credit-terms form, `Money` (mono),
`LayerNote` for the collapsed margin.

## Data & layers
Revenue is `commercial`; **margin/COGS is `profitability`** — the canonical collapse example (show `LayerNote`,
not zero). Credit terms are `commercial`.

## Actions & states
Load P&L / waterfall · edit credit terms (maker, confirm). *Collapsed margin:* explicit "hidden — requires
profitability" state. *Empty/error* per kit.

## Design notes
The hero teaching moment is the **layer collapse** — a sales viewer sees revenue but the margin column is
honestly absent (LayerNote), not faked. Credit-terms edits are real money mutations — confirm + audit.
