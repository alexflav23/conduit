# Conduit Desk — page-per-feature design specs (Claude Design source)

This directory is the **refreshed UI contract**: one self-contained design brief per feature, written so
[Claude Design](https://claude.ai/design) can mock each screen and a coding agent can implement it. It supersedes
the single-file [`spec/27_UI_FEATURE_MAP.md`](../27_UI_FEATURE_MAP.md) (kept as the legacy map) — the existing
handoff (`Conduit Desk.html`) covers ~11 features; Conduit has **25+**. Each page below is its own file so the
design can be extended one feature at a time.

> Source of the visual language: the existing Claude Design bundle (`tokens.css`, `desk-kit.jsx`, `desk-shell.jsx`).
> These specs **conform to it** — they describe *what each screen must show and do*, in that design's vocabulary.

## Design language (do not re-invent — match the bundle)
- **Brand:** Hypervolt. Dark-mode first, light toggle. Near-black ink `rgb(22,23,28)`; the signature 3-colour
  gradient **magenta `#EB01FF` → purple `#962DFF` → blue `#0356FF`** (`--hv-gradient-3c`) for hero/accent only —
  never on dense data. Type: **Rubik** (display) + **Roboto** (UI/body); **mono** for ids, money, ledger.
- **Kit components (reuse, don't fork):** `PageHead`, `Card`, `Chip` (status), `Drawer` (detail/side-panel),
  `Money` (mono, right-aligned, layer-aware), `Coverage`, `ZoneTag` (firm/flex/indicative), `AuditRef` (a figure
  that drills to its ledger/lineage), `LayerNote` (explains a collapsed layer), `LoadBar`, `EmptyRow`.
- **Shell affordances (every page inherits):** left nav (grouped), top bar with **period context** (open/closed/
  locked), **role / view-as switcher** showing the viewer's data layers, **notifications** bell, **command
  palette** (⌘K → screens + records), dark/light toggle, session/identity chip.

## The data-layer wall (load-bearing, doc 05) — applies to EVERY page
A withheld data layer/field/row is **absent from the payload** — so the UI must **collapse, never zero**. A money
widget the viewer can't see shows nothing (or a `LayerNote` "hidden — requires `profitability`"), never `£0.00` or
a placeholder. Design every figure as conditionally present. The layers: `volume · commercial · profitability ·
commission · inter_entity · pii`.

## Page-spec template (each feature file follows this)
```
# <NN> — <Feature> (<route id>)
Status: <COVERED in Conduit Desk.html | MISSING | PARTIAL> · Roles: <who> · Backend: <key endpoints>
## Purpose            — one line: the job this screen does
## Layout             — the screen shape (nav → header → body); primary + secondary regions
## Components         — the kit pieces + any new ones, with what each shows
## Data & layers      — fields shown, and which collapse for which layer
## Actions & states   — buttons/flows; empty / loading / forbidden(403) / error / success
## Design notes       — the Apple-quality intent; what the hero metric is; what must feel fast
```

## Feature inventory (the complete set — design status)
Legend: ✅ in the current design · ➕ MISSING (priority for the refresh) · ◑ partial.

| # | Feature | Route | Status | Spec file |
|---|---|---|---|---|
| 00 | Sign-in / session / view-as | `signin` | ✅ | [00-signin.md](00-signin.md) |
| 01 | Order Desk | `order` | ✅ | [01-order-desk.md](01-order-desk.md) |
| 02 | Deal Desk (ADLP exceptions) | `dealdesk` | ✅ | [02-deal-desk.md](02-deal-desk.md) |
| 03 | Demand / H6Q board | `h6q` | ✅ | [03-h6q.md](03-h6q.md) |
| 04 | Flow (waterfall + ledger) | `flow` | ✅ | [04-flow.md](04-flow.md) |
| 05 | Supply window | `supply` | ✅ | [05-supply.md](05-supply.md) |
| 06 | Shelf (per-account stock) | `shelf` | ✅ | [06-shelf.md](06-shelf.md) |
| 07 | Finance (P&L / cash / credit) | `finance` | ✅ | [07-finance.md](07-finance.md) |
| 08 | Documents | `docs` | ✅ | [08-documents.md](08-documents.md) |
| 09 | Lifecycle | `lifecycle` | ✅ | [09-lifecycle.md](09-lifecycle.md) |
| 10 | Auditability (close/recon/controls/lineage) | `audit` | ✅ | [10-auditability.md](10-auditability.md) |
| 11 | Tax | `tax` | ✅ | [11-tax.md](11-tax.md) |
| 12 | **Period governance + investigation** | `period` | ➕ | [12-period.md](12-period.md) |
| 13 | **Sync — shadow dual-run health** | `sync` | ➕ | [13-sync.md](13-sync.md) |
| 14 | **Proof Center** (laws / ASC-606 / journal walk / tamper) | `proof` | ➕ | [14-proof.md](14-proof.md) |
| 15 | **Forecast Engine** (backtest / champion / accuracy) | `engine` | ➕ | 15-forecast-engine.md |
| 16 | **Returns / RMA** | `returns` | ➕ | 16-returns.md |
| 17 | **Commission** (accrual / claw / statements) | `commission` | ➕ | 17-commission.md |
| 18 | **Inventory / ATP / dispatch / carriers** | `inventory` | ➕ | 18-inventory.md |
| 19 | **Purchasing / receiving / stock ops** (maker-checker) | `purchasing` | ➕ | 19-purchasing.md |
| 20 | **Batch / landed-cost / serial genealogy** | `batch` | ➕ | 20-batch.md |
| 21 | **Activation ingest + warranty provision** | `activation` | ➕ | 21-activation.md |
| 22 | **CRM — parties / contacts / deals / pipeline** | `crm` | ➕ | 22-crm.md |
| 23 | **Intercompany — TP / FX / hedges / true-ups** | `intercompany` | ➕ | 23-intercompany.md |
| 24 | **Procurement entity** (principal/LRD, flash-title) | `procurement` | ➕ | 24-procurement.md |
| 25 | **Reseller portal** (scoped, rate-limited) | `reseller` | ➕ | 25-reseller.md |
| 26 | **Access / permission builder** (à-la HubSpot, per-CRUD × sector × geo) | `access` | ➕ | 26-access.md |
| 27 | **Notifications** (subscriptions + delivery + in-app) | `notifications` | ➕ | 27-notifications.md |

The ➕ rows are the gap — written first. Each file is a standalone brief; drop it into Claude Design to extend
`Conduit Desk.html` one feature at a time.
