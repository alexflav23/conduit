# 13 — Sync: shadow dual-run health (`sync`)
Status: MISSING · Roles: finance, auditor, ceo, admin (`view:sync_state`) · Backend: `GET /finance/sync-state` (+ the dual-run `dualrun_*` reconciliations on the Audit board)

## Purpose
The control room for the **months-long shadow parallel run** (doc 33): is Conduit tracking every source system
(Xero, HubSpot, MRPeasy, Athena, Stripe) to the penny? It shows per-source **sync health** (cursor, lag, status,
drift) and points to the **dual-run reconciliations** that prove the books match — a sustained all-matched window
is the cutover green light.

## Layout
- `PageHead` "Sync" + a `Load`/auto-refresh control + a "shadow: ON" banner when in shadow mode.
- **Sync-health board** — a row per `(source, dataset)`: source · dataset · **status** `Chip` (green ok+fresh+no
  fails / amber error or stale or rising fails) · **last run** (human lag "45s ago") · written · consecutive
  fails · cursor (mono) · last error.
- **Dual-run reconciliations** card — the `dualrun_*` rows (domain · expected[source] · actual[Conduit] ·
  variance · matched/exception), tolerance-0; or a pointer to the Audit board's reconciliation surface.
- (Optional) a **shadow-action** feed: the outbound effects suppressed during the run (what we *would* have sent).

## Components
`PageHead`, the health table with status `Chip`s + relative-time, `Money` for reconciliation figures, `AuditRef`
to open a reconciliation's detail, an amber alert affordance for stale/failing streams.

## Data & layers
Sync-health is `volume`-layer (operational, not money). The dual-run reconciliation figures are `commercial`/
`profitability` layered (collapse accordingly). Lag is `now − last_run_at`; a stale lag (>1h) or a rising
`consecutive_failures` is the early warning *before* a reconciliation exception.

## Actions & states
- **Load / refresh**: re-pulls the board. *Empty:* "No sync streams yet — connectors register on first run."
  *403:* "requires view:sync_state".
- A failing/stale stream should be visually loud (amber) and link to its last error + the relevant runbook
  (dlq-replay / projection-rebuild).

## Design notes
This is a **monitoring dashboard** — calm when green, loud when not. The hero is the **at-a-glance "are we in
step with reality?"** read: a wall of green stream chips + "all reconciliations matched for N days" is the
go-live confidence the CTO/auditor wants. Make staleness and drift impossible to miss.
