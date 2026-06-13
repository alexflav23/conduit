# 10 — Auditability (close / recon / controls / lineage) (`audit`)
Status: COVERED ✅ (refresh) · Roles: finance, auditor, ceo · Backend: `GET /finance/periods`, `…/{id}/reconciliations`, `POST …/close|lock`, `GET /finance/controls`, `POST …/{code}/run`, `GET /finance/lineage?invoice_no`

## Purpose
The Auditability Center (doc 20 D15–D18, doc 14 §6): the period **close board** (open→closed→locked), automated
**reconciliations**, the re-performable **SOX control register**, and the **lineage explorer** (figure → ledger →
events). doc 20 splits this into four screens — the redesign should too (today's single tab is a compromise).

## Layout
- `PageHead` "Auditability".
- **Close board**: periods with status; close → **lock** (two-step confirm, show what becomes immutable — posting
  is rejected once locked); unmatched reconciliations block the lock.
- **Controls register**: each `CTRL-*` with **pass/fail history** (not just last run) + a Run action (re-performable).
- **Lineage explorer**: invoice → ledger transfers → events — the "prove this number" tool.

## Components
`PageHead`, close-board table with status `Chip`s, a reconciliation list (matched/exception/signed-off), a control
register with run + history, a lineage chain with `AuditRef`, `Money` (mono).

## Data & layers
Reconciliation/ledger figures are `commercial`/`profitability` (layered). Close/lock are hard finance actions
(maker-checker on lock; the closer can't lock — SoD).

## Actions & states
Close · lock (two-step, immutability warning) · run a control · trace an invoice. **Lock blocked** over an
unsigned exception — surface as a gate, not a toast. Control history matters (a control that passes today but
flapped is a signal).

## Design notes
Split into the four doc-20 screens (close / recon / controls / lineage). The hero is **re-performable proof** —
controls earn green on click, the lock is visibly final, and any figure traces to its evidence. Semantic-red is
load-bearing only on a failed control / unsigned exception.
