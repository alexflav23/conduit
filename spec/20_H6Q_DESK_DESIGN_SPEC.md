# 20 — H6Q Desk: Design Specification (for Claude Design)

**Audience:** Claude Design (and front-end engineers). **Status:** function-first. This spec defines *what each
screen must show, how it behaves, and the exact data it binds to*. It deliberately does **not** prescribe the
"beautiful" layer yet — colour systems, motion, illustration, marketing polish come **after** this is validated.
For now the bar is: **extremely readable, extremely functional, and faithful to the immutable ledger.**

> Build target: Vite + React 19 + StyleX (the `conduit-desk` app, mirroring hyperstore). Accent `#962DFF`,
> dark-mode first. The TS API client is generated from Conduit's OpenAPI (tapir). Everything below binds to the
> live `/api/v1/h6q/*` and ledger endpoints — no mock data.

---

## 0. The one idea this desk must convey

H6Q is **the same demand seen as a sequence of distinct "variants" that must never be conflated**, evolving over
time toward a real, ledger-proven revenue number:

```
forecast  →  committed  →  produced  →  delivered  →  ordered  →  shipped  →  revenue
(intent)     (firm PO)     (CM built)   (received)    (sold)      (dispatched) (ASC 606, in TigerBeetle)
```

Two non-negotiables the UI exists to serve:

1. **See the variants and how they evolve over time.** A user must, at a glance, see for any SKU/account/market:
   what we *forecast*, what we *committed* to the contract manufacturer, what was *produced/delivered*, what was
   *shipped*, and the **gaps** between them — and scrub that across weeks/months.
2. **Interact with the immutable log.** Any money figure (revenue, COGS, margin) must drill down to the
   **TigerBeetle transfers** that prove it. Numbers are never asserted; they are *traced*.

Everything else is in service of these two.

---

## 1. Information architecture (navigation)

Top-level tabs (left rail or top nav — designer's call later; for now a simple horizontal tab bar exists):

| Tab | Purpose | Primary endpoint(s) |
|---|---|---|
| **Capture** | An owner forecasts *their* accounts this week (per SKU or a unit count split by mix) | `GET/POST /h6q/my-forecasts`, `/submit`, `/submit-mix`, `/skip` |
| **Coverage board** | Bottom-up rollup, org axis ↔ agent axis (reconciling), per SKU, layer-aware | `GET /h6q/coverage`, `/coverage/by-sku`, `/coverage/reconcile`, `/outstanding` |
| **Flow (variants over time)** ⭐ | The waterfall per SKU and its evolution across periods — the heart of this spec | `GET /h6q/waterfall`, `/h6q/ledger` |
| **Supply window** | Firm-commitment time fences (frozen/flex/free), divergence warnings, auto-PO proposals | `GET /h6q/auto-po` (proposals), `supply_commitment`, `commitment_warning` reads |
| **Shelf** | Real-time per-account stock from serials (shipped/activated/on-shelf) | `GET /h6q/shelf` (per account + fleet board) |
| **Ledger / Audit** | The immutable-log view: recognised revenue → transfers → balances | `GET /h6q/ledger`, account balances |
| **Alerts** | Forward-visibility notifications + who was told (incl. the contract manufacturer) | `GET /h6q/notifications` |

The desk is **layer-aware** (doc 05 §3): a `volume`-only principal sees units only; money columns are **absent**
(not zeroed). The same screen composes more columns as the principal gains `commercial`/`profitability`.

---

## 2. Screen specs

### 2.1 Capture (the agent's weekly job) — *exists*
- Shows the owner's accounts for the open cycle, each with status (outstanding / submitted / skipped).
- Per account: an editable grid **rows = SKU, columns = P20 / P50 / P80**, for the horizon month.
- A **unit-count mode**: the agent types one number; the SKU mix splits it (show the resulting per-SKU split
  inline before submit, so they trust it). Revisions are append-only — show "revised from N" provenance.
- States: outstanding (call to action), submitted (green), closed-cycle (read-only + a 409 toast).

### 2.2 Coverage board — *exists; extend*
- Pivot over `pipeline_coverage`: rows drill `market → channel → sub_channel → segment → customer → branch`; a
  **By-agent** toggle re-pivots the *same* numbers on the owner; a **reconcile chip** shows `Σ branch ≡ Σ agent ✓`.
- A **By-SKU** drill (`/coverage/by-sku`): the total split per SKU (the "Quarterly Forecast Dashboard").
- Cells: forecast / shipped / activated / **coverage %** (headline) / WoW ▲▼ / forecast_source (manual/hyperview/mixed).
- Scenario selector (P20/P50/P80) and an **ex-cut toggle** (ex-Octopus / ex-Motability) — switch with no reload.
- "Who still owes" panel from `/outstanding`.

### 2.3 ⭐ Flow — variants over time (the heart)
The single most important screen. Two coordinated views:

**(a) The waterfall (one SKU, one period).** A left-to-right flow of the seven variants as labelled steps:
```
[ Forecast 120 ] → [ Committed 100 ] → [ Produced 90 ] → [ Delivered 80 ] → [ Ordered 60 ] → [ Shipped 50 ] → [ £25,000 ]
        83%             90%               89%              (sold)            (dispatched)      (revenue, ledger-proven)
```
- Between each step show the **conversion %** and, where it matters, the **gap** (e.g. produced < committed →
  "10 short, carried to next window"). Colour the gap, not the step (red = material shortfall, amber = watch).
- The revenue step is a **button/link** → opens the Ledger drawer (§2.6) for that dispatch.
- Bind to `GET /h6q/waterfall?variant=&period=` → `{stages:{sales_forecast, cm_committed, cm_produced,
  delivered, ordered, shipped}, revenue_ex_vat, conversion:{…}}`.

**(b) Evolution over time.** The *same* SKU's variants across consecutive periods (months) — a small matrix:
- Rows = the 7 variants; Columns = months (e.g. Jul, Aug, Sep). Each cell the quantity; the bottom row revenue.
- This is how a user sees demand **age**: a July forecast firms into an August commitment, gets produced, ships.
- Implementation: call `/h6q/waterfall` once per visible month and lay them side by side (the API is per-period).
- A sparkline per variant row is a *later* (beauty) nicety — for now a plain number matrix is the requirement.

States: empty (no data for the SKU/period — show "no forecast captured"), partial (some stages 0 — that's
*meaningful*, not an error: e.g. forecast > 0 but produced 0 means nothing built yet).

### 2.4 Supply window (firm-commitment + auto-PO) — *new*
- Per SKU, a **horizon strip**: weeks laid left→right, shaded by zone — **frozen** (firm, can't move), **flex**
  (±tolerance, with the graded curve the band tightens toward now), **free**. Show the firm PO per week and the
  **committable headroom** (how much it can still move up/down) — this is the real-time gate made visible.
- **Auto-PO proposals** (`/h6q/auto-po`): a table of `demand / committed / available / net_need / proposed_delta
  / blocked / zone`. `proposed_delta` is the auto-fill within headroom; `blocked` (with a ⚠️) is what needs
  exec escalation. An **Approve** action commits the proposed delta (maker-checker, audited).
- **Divergence warnings** (`commitment_warning`): when sales/automated demand diverges from a frozen PO, list it
  prominently — "the PO can't move; decide."

### 2.5 Shelf (real-time per-account stock) — *new*
- Mirrors ghost-busters `/stock/dashboard`, but native: a board of accounts with **shipped / activated / on-shelf**,
  busiest shelf first (`GET /h6q/shelf`). Per-account drill (`/h6q/shelf?company=`) shows the finite serial set.
- "On-shelf" falls **live** as activations arrive (the Pulsar stream flips serials) — the UI should poll or
  subscribe so the number visibly ticks down. Conduit owns the serial→customer attribution (set at dispatch),
  so this is authoritative, not an MRPeasy guess.

### 2.6 Ledger / Audit drawer (interaction with the immutable log) — *new, critical*
The proof surface. Opened from any money figure (revenue/COGS/margin) or standalone for a period.
- Shows, for a recognised dispatch: **revenue ex-VAT, VAT, COGS, gross margin**, and the **three TigerBeetle
  transfers** that posted them (`ar_transfer_id`, `vat_transfer_id`, `cogs_transfer_id`) with amounts in minor
  units, plus the **live account balances** (AR / Revenue / VAT / COGS / INV) so debits == credits is visible.
- The message to convey: *"this revenue is not a spreadsheet number — here are the immutable transfers."*
- Bind to `GET /h6q/ledger?market=&period=` (recognitions + transfer ids + amounts) and the balance reads.
- A "re-performable" trace: figure → transfer → event → source row (for audit; later milestone deepens this).

### 2.7 Alerts — *exists (panel); promote to a tab*
- `GET /h6q/notifications`: recipient, channel (in-app/email/webhook), message, status. Filter by the contract
  manufacturer to see exactly what Volex was told and when.

---

## 3. Cross-cutting interaction & readability rules

1. **Variants never blur into one number.** Wherever a quantity appears, it is labelled with *which* variant it
   is (forecast vs committed vs shipped…). Never show a bare "units" that conflates stages.
2. **Money is always traceable.** Every currency value is a link/affordance to the Ledger drawer (§2.6). If the
   principal lacks the layer, the value (and its link) is **absent**, never shown as 0 or "—£".
3. **Gaps are first-class.** The interesting information is the *delta* between variants (the shortfall, the
   divergence, the coverage gap). Surface gaps, not just totals.
4. **Time is a dimension, not a filter.** The Flow screen scrubs across periods; demand visibly ages.
5. **Real-time where it's real-time.** Shelf on-shelf and auto-PO proposals refresh as events arrive (poll on an
   interval now; switch to push later). Show a "last updated" timestamp.
6. **States everywhere.** Every data panel has explicit loading / empty / error / partial states. "Partial" (a
   stage at 0) is a *valid business state*, rendered plainly, never as an error.
7. **Readability bar:** monospaced/tabular numerals for all quantities and money; right-aligned numeric columns;
   generous row height; the headline metric per panel visually dominant; no more than one accent colour in use
   per panel until the beauty pass.

---

## 4. Data contracts (the bindings)

| Screen | Method · path | Shape (key fields) |
|---|---|---|
| Capture | `GET /h6q/my-forecasts` | `{cycle, accounts:[{company_id,name,status,lines:[{variant,period,scenario,qty}]}]}` |
| Capture | `POST /h6q/my-forecasts/{id}/submit` · `/submit-mix` · `/skip` | per doc 12 §11 |
| Coverage | `GET /h6q/coverage?market&period&scenario&group_by&variant` | `[{level,branch_company_id,agent_user_id,product_variant_id,forecast_qty,shipped_qty,activated_qty,coverage_pct,wow_delta,forecast_source, …layer money}]` |
| Coverage | `GET /h6q/coverage/by-sku` · `/coverage/reconcile` · `/outstanding` | per-SKU rows · `{branch_axis,agent_axis,ties}` · who-owes |
| Flow | `GET /h6q/waterfall?variant&period` | `{stages:{…7 variants},revenue_ex_vat,conversion:{…}}` |
| Supply | `POST /h6q/auto-po {supplier,market,period,scenario,asOf}` | `[{product_variant_id,demand,committed,available,net_need,proposed_delta,blocked_qty,zone}]` |
| Shelf | `GET /h6q/shelf[?company]` | board: `[{company_id,name,shipped,activated,on_shelf}]` · one: `{shipped,activated,on_shelf}` |
| Ledger | `GET /h6q/ledger?market&period` | `{recognitions:[{dispatch_id,revenue_ex_vat,vat,cogs,gross_margin,ar_transfer_id,vat_transfer_id,cogs_transfer_id}], balances:{ar,revenue,vat,cogs,inv}}` |
| Alerts | `GET /h6q/notifications` | `[{subscription,channel,subject,body,status,created_at}]` |

All require a Keycloak bearer; dev uses `dev:<keycloak_id>`. All reads are scope-filtered + layer-projected
server-side (doc 05) — the UI renders whatever fields are present and must not assume money exists.

---

## 5. What to hand the "beautiful" pass later (explicitly out of scope now)

- Colour system beyond the single accent, theming (dark/pro), iconography, illustration.
- Motion/transitions, sparklines/charts (the Flow matrix is numeric for now), skeleton loaders.
- Marketing-grade typography scale; for now: system font, tabular numerals, clear hierarchy.
- Responsive/mobile polish (the desk is back-office/iPad first; capture is the only field surface).

The validation goal for THIS pass: a finance/ops user can open Flow, read a SKU's variants and their evolution,
click revenue, and **see the TigerBeetle transfers that prove it** — with zero ambiguity about which variant
each number is. Once that's true and trusted, hand to Claude Design for the beautiful layer.

> Supports M11 (doc 07/12). Pairs with doc 08 (Flutter field capture) and the back-office desk deliverable (doc 10 §B).
