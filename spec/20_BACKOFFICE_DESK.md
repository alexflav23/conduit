# 20 — Back-Office Desk: Screen-by-Screen Spec (React/TS)

Build spec for the **Conduit back-office desk** — the React/TypeScript web app for **non-field roles**. Audience: `finance`, `tax_specialist`, `admin`, *CEO/CFO*, *Treasury*, the read-only `auditor`, plus power desk users running Deal Desk and the full H6Q board. The field roles (`retail_sales_agent`, `customer_service_agent`, `fulfilment_agent`) live in the Flutter companion app — **doc 08**. Where the two surfaces touch (an agent assembles an ADLP exception; the CEO decides it on the desk), the seam is called out.

This is the other half of the UI — and the more complex half (doc 10 §B): pricing governance/ADLP and the inter-entity wall, the permission builder, Deal Desk + CEO approval, the deep H6Q board, ledger/finance views, supply planning, admin, and the **Auditability Center** (doc 14 §6). Like doc 08, each screen lists: **purpose · role · entry · layout/components · data (→ API from doc 06) · actions (→ effects) · states**. It is grounded in the data model (02), domain logic (04), access control (05), API (06) and financial integrity (14) — don't invent fields or endpoints beyond those.

**Stack (CLAUDE.md §5).** Vite 8 + React 19 + TypeScript 5.8, **StyleX 0.18** (per hyperstore: `stylex.defineVars` tokens, `createTheme`), **yarn**, React Router v6 (locale-prefixed), **React Query v5** for server state, **React Hook Form** for forms, axios. **The TS API client is generated from Conduit's OpenAPI** (tapir emits it) — types are generated, never hand-written. i18next + react-i18next, 15-locale set (incl. CJK + Thai). Hypervolt accent **`#962DFF`**, **dark-mode first-class**. Existing thin slice: `conduit-desk/` (Order-Desk quote→place card, `colors.stylex.ts` token set) is the seed this builds on.

> **Canonical naming (CLAUDE.md §8.1):** the data model is `party` (organization/individual/branch are party types). Some doc-06 paths still read `/companies` / `company_id`; treat those as a `party` of an organization type and prefer `/parties`. This doc uses `party`.

---

## 0. Design language & global patterns

**Brand / theme.** Hypervolt: premium, confident, dense-but-calm. This is an **operator console**, not a marketing site — high information density, fast keyboard paths, tables and boards first. Primary accent **`#962DFF`**; **dark mode first-class** (token set in `conduit-desk/src/styles/tokens.stylex.ts`: `bg #0a0b15`, `surface #15172a`, `border #2a2d44`, `text`, `muted`, `ok #30d158` green=matched/on-track, `warn #ff9f0a` amber=attention, plus a `danger` red=blocked/exception/over-limit token to add). Rounded 12–14px cards, generous numerals — money and units are the content. Status uses colour sparingly and consistently with doc 08 (green/amber/red).

**Platform / layout.** Desktop-first, wide. **Persistent left nav rail** (role-gated sections) + top context bar (entity / market / period / scenario context switchers) + main work area, typically **two-pane** (list/table → detail drawer or detail route) and, for boards, **three-pane drill** (master list → breakdown → atoms). Responsive down to a laptop; not designed one-handed (that is the Flutter app). Keyboard-first: command palette (⌘K) to jump screen/record; every table is arrow-navigable; bulk-select with shift; forms tab-ordered.

**Auth.** Keycloak OIDC (JWKS-verified server-side, CLAUDE.md §2). One identity = one person; **silent token refresh**; sign-in lands on the role's home. The desk never trusts itself for authorisation — see below.

**Data-layer awareness (critical, doc 05 §3).** Every field is projected server-side by the principal's `viewable_layers`; the response **does not contain** layers the user lacks. The UI must **gracefully omit (not zero)** hidden layers — a `volume`-only viewer of the H6Q board sees units, coverage % and sell-through and **no money column at all** (the column is absent, not blank, not `£0`). A `commercial`-but-not-`profitability` user sees revenue but no margin/GP. A Deal Desk preset with `price_rule[volume,commercial]` and **no `inter_entity`** grant sees customer pricing and the inter-entity layer is **invisible and absent from the payload** — the **layer wall**. The UI renders only what arrives; money widgets accept a "hidden" sentinel and collapse. Custom `attributes` keys are layer-tagged too (doc 05 §3) — render only the keys present. **No client-side gate is ever the authority**; it mirrors the server projection for layout only.

**Maker-checker everywhere money/control moves (doc 05 §4–5, doc 14 §4).** Pricing-rule activation, ADLP exception decision, period close/lock, prior-period adjustment, manual journal, FX-rate entry, credit-limit change, stock count/transfer/write-off approval, returns approval — all **two-person**. The UI must (a) show who proposed and who must approve, (b) **disable the approve control for the proposer** (the server rejects self-approval — the UI surfaces it pre-emptively as a disabled state with a tooltip, never as a silent failure), (c) capture a memo/reason on the decision, (d) show the resulting immutable audit reference.

**Optimistic vs authoritative.** Reads use React Query with `as of <time>` staleness stamps and background refetch; mutations are **never** assumed-applied for financial/control actions — they show pending → server-confirmed, surfacing `409`/`422` domain rejections (allocation race, ADLP hold, credit block, locked-period post) as clear, actionable banners, not toasts that vanish.

**Time & period context (doc 14 §2).** A global **reporting context** (entity · reporting timezone · fiscal calendar · period) sits in the top bar and parameterises every report, board and ledger view. Changing it re-projects (it does not migrate). Period state (open/closed/locked) is shown wherever a figure is period-bound; **locked** periods are read-only and badged.

**Localization (i18n).** Fully localized (i18next, 15 locales incl. CJK + Thai); no hard-coded strings; numbers/currency/dates per locale; **group consolidation/presentation = USD** (doc 14 §2.4). Per-entity statutory views render in the entity's local TZ; the consolidated view in USD/group TZ. No RTL in scope.

**Global UI furniture.** Top context bar (entity/market/period/scenario), command palette (⌘K), left nav rail (role-gated), notification bell (badge), global search (orders/parties/serials/deals, scope-filtered server-side), per-table loading/empty/error states, export buttons (layer-respecting), an **audit-reference chip** on every governed action's result, and a persistent **"viewing as <role> · layers: …"** affordance so an operator always knows what is hidden from them.

**Reusable components (for the design system — extends doc 08's set; shared where sensible).**
`MoneyOrHidden` (layer-aware; collapses when absent — shared with app), `UnitsBadge`, `StatusChip` (order/deal/exception/period/reconciliation/control states), `CoverageBar` (forecast vs pipeline vs shipped vs activated), `ScenarioToggle` (P20/P50/P80 + ex-account toggles), `LayerGuard` (renders children only if a layer key is present in the payload — layout helper, **not** an authz gate), `DataTable` (virtualized, sortable, column-pick, keyboard-nav, scope-filtered server-side, layer-aware columns, CSV/XLSX export), `DrillBreadcrumb` (channel→sub-channel→segment→customer→branch / by-agent path), `DrawerDetail` (right-hand detail pane), `ApprovalBar` (maker-checker: proposer, approver, memo field, disabled-for-self), `DiffView` (before/after for versioned rules, amendments, reslice preview), `VersionTimeline` (append-only history of a rule/exception/period), `MemoComposer` (decision/justification rich-but-bounded text + doc refs), `LedgerDrill` (figure → transfers → events → documents → replay), `ReconRow` (expected/actual/variance/status/sign-off), `ControlCard` (control + assertion + last run + re-perform), `EntityPeriodPicker` (the global context control), `EvidenceExportSheet` (signed, time-stamped pack builder), `AuditRefChip`, `EmptyState`/`ErrorState`/`StaleStamp`.

---

## 1. Navigation map

```
Login (Keycloak OIDC)
└── Desk shell (left nav rail — role-gated; top context bar: entity · market · period · scenario)
    ├── Home / Worklist           (all)               → role-tuned "what needs me"
    ├── Pricing & ADLP            (CEO, finance-view, tax-view)
    │     ├── Price-rule list / versioning            → Rule detail → Activate (approval)
    │     └── Inter-entity layer (walled)
    ├── Deal Desk                 (desk power users)   → Exception queue → Assemble justification
    │     └── CEO Approval         (CEO only)          → Approve / Reject memo
    ├── H6Q Board                 (all, layer-aware)   → Drill channel→…→branch / by-agent → Outstanding
    ├── Finance                   (finance, auditor-view, CEO)
    │     ├── Ledger / AR / AP / Inventory projections
    │     └── Commission statements
    ├── Supply Planning           (finance, fulfilment-mgmt)
    │     └── Replenishment · Backorders · PO/GRN
    ├── Auditability Center       (finance, CEO, auditor)
    │     ├── Controls register · Reconciliation · Period close
    │     └── Lineage explorer · Money integrity · Time/period · Audit & evidence export
    ├── Admin                     (admin)
    │     ├── Permission builder (roles × permissions × layers × scope)
    │     ├── Users & assignments · Config / reference data
    └── Auditor Portal            (auditor — read-only, financial-truth + lineage + controls only)
```
Sections are **role-gated by the server** (the nav reflects grants returned for the principal; it does not invent reach). An `auditor` sees a stripped shell: Finance (read), Auditability Center, Lineage — and **no** edit affordances anywhere.

---

## 2. Auth & shell

### D1 · Login / session
- **Purpose:** authenticate; restore session; land on role home.
- **Role:** all. **Entry:** app load.
- **Layout:** Hypervolt mark on dark; "Sign in" (Keycloak OIDC web flow); silent refresh on return.
- **Actions:** sign in → OIDC → role home (D2). Sign out.
- **States:** loading; auth error (retry); session expired (re-auth, preserving the route).

### D2 · Desk shell + Home / Worklist
- **Purpose:** the operator's "what needs me now," role-tuned, plus the chrome (nav rail, context bar, palette, bell).
- **Role:** all (cards vary by role/grants/layers). **Entry:** post-login.
- **Layout:** top context bar = `EntityPeriodPicker` (entity · market · reporting TZ · period · scenario) + global search + ⌘K + bell + "viewing as <role> · layers" chip. Left nav rail role-gated. Home work area = stacked worklist cards:
  - **CEO:** exceptions **pending decision** (count + oldest age), price-rule activations awaiting approval, period-close/lock approvals, prior-period adjustments, FX-rate entries to approve.
  - **Finance:** open reconciliation exceptions, the period-close checklist progress, AR ageing flags, commission true-up run status.
  - **Admin:** pending access requests, recently changed grants (review), config drift.
  - **Auditor:** new evidence available, controls with a failed last-run, periods locked since last visit (all read-only).
- **Data:** `GET /adlp/exceptions?status=pending_ceo`, `GET /pricing/rules?status=draft`, the close/recon/control reads (D-Audit screens), `GET /audit?from=`.
- **Actions:** card → its screen; ⌘K jump; switch entity/period (re-projects everything).
- **States:** empty ("nothing needs you"), loading, error, stale-stamp on cached cards.

---

## 3. Pricing Governance / ADLP (the layer wall)

### D3 · Price-rule list
- **Purpose:** govern `price_rule`s — list, filter, see versions, see activation status, spot the inter-entity wall.
- **Role:** CEO (edit all layers), finance/tax (view per their layers). **Entry:** Pricing & ADLP.
- **Layout:** `DataTable` of rules — columns: surface (`customer` / `inter_entity`), variant/family, channel, market, currency, `authorised_price` (`MoneyOrHidden`), `max_discount_pct`, status (`StatusChip`: draft / active / superseded), version, effective-from, last changed by. **Inter-entity rows are present only for principals with the `inter_entity` layer** — for everyone else they are absent from the payload (not greyed) and a quiet note states "inter-entity pricing is layer-restricted." Filter by surface/variant/channel/market/status; search.
- **Data:** `GET /pricing/rules?surface=&variant=&channel=` (layer-projected; inter-entity rows require the layer per doc 05 §3).
- **Actions:** row → D4; "New rule" (→ D4 create, CEO); export (layer-respecting).
- **States:** layer-walled (inter-entity absent), empty, error.

### D4 · Price-rule detail · versioning · activate
- **Purpose:** inspect/author a rule, see its append-only version history, and **activate** under approval.
- **Role:** CEO edits all layers; others view per layer. **Entry:** D3.
- **Layout:** rule header (surface, scope = entity/channel/market/variant, currency); editable fields (`authorised_price` `MoneyOrHidden`, `max_discount_pct`, volume breaks, validity window) in a React-Hook-Form; **`VersionTimeline`** of prior versions with **`DiffView`** (this version vs prior); status banner (draft/active/superseded). **`ApprovalBar`** for activation: proposer, required approver, memo, disabled-for-self. If `surface = inter_entity` the whole detail is gated behind the `inter_entity` layer.
- **Data:** `GET /pricing/rules?...` (detail), `POST /pricing/rules` (create draft), `POST /pricing/rules/{id}/activate` (governed/audited; emits `pricing.rule.changed`).
- **Actions:** edit → save draft; **Activate** → maker-checker approval → on confirm, rule becomes active, prior version superseded, `AuditRefChip` shown. **Immediately effective, never a migration** (doc 07 M3).
- **Rules / states:** editing a layer requires it in `editable_layers` (server-enforced; UI disables otherwise); activation blocked for the proposer if it is their own draft and policy requires a separate approver; inter-entity surface invisible without the layer. States: draft / pending-approval / active / superseded / activation-rejected.

---

## 4. Deal Desk + CEO Approval

### D5 · Deal Desk — exception queue & justification
- **Purpose:** the desk side of the ADLP exception flow — assemble/curate the justification for an out-of-band line so the CEO can decide. (Agents originate the request in the app, S16; the desk power user can also originate/strengthen it.)
- **Role:** desk power users (deal-desk preset: `price_rule[volume,commercial]`, **no inter_entity**); CEO sees all. **Entry:** Deal Desk.
- **Layout:** `DataTable` of exceptions — order/line, party, requested price/discount vs ADLP band (`DiffView`), `adlp_category`, volume expectation + P-denomination, requesting agent, status (`StatusChip`: draft / pending_ceo / approved / rejected), age. Detail drawer = **`MemoComposer`** for justification (justification, `volume_expectation`, `volume_denomination`, `strategic_importance`, `doc_refs`). Money shown per the desk layers (customer pricing yes; cost/margin only if `profitability` granted — otherwise the GP impact line is **absent**).
- **Data:** `GET /adlp/exceptions?status=`, `POST /adlp/exceptions/{id}/submit { justification, volume_expectation, volume_denomination, strategic_importance, doc_refs }`.
- **Actions:** edit justification → **Submit** (moves draft → `pending_ceo`, routes to D6); attach doc refs. **No approve control here** — approval is CEO-only (server `403` otherwise; the UI does not render the control for non-CEO).
- **States:** draft / submitted / decided (read-only with the CEO memo shown); layer-stripped (no margin for non-profitability desk users).

### D6 · CEO Approval — decision memo
- **Purpose:** the CEO decides an exception — approve or reject — with a binding, immutable memo. The **sole** approval point (doc 05 §4).
- **Role:** CEO/CFO only. **Entry:** Home card / Deal Desk queue.
- **Layout:** full exception context (requested vs band `DiffView`; volume/strategic justification; agent; full margin impact — CEO has all layers); **`ApprovalBar`** with approve/reject + required **`MemoComposer`** (`memo_ref`). Side panel: the order it holds and what releases on approval.
- **Data:** `POST /adlp/exceptions/{id}/decision { decision: approve|reject, memo_ref }` (CEO only; `403` otherwise).
- **Actions:** **Approve** → order releases (no longer `pending_ceo`; allocation proceeds; that line's commission **un-zeroes**; agent push-notified — doc 08 S16). **Reject** → order returns for re-price; memo recorded. Either way the decision is immutable and audited (`approval_memo_ref`, `AuditRefChip`).
- **States:** pending (decidable); decided (read-only, memo shown); concurrent-decision guard (`409` if already decided).

---

## 5. H6Q Board (the full, layer-aware, drill-down board)

### D7 · H6Q coverage board
- **Purpose:** the full forecasting cockpit — coverage of forecast vs weighted pipeline vs shipped vs activated, drillable through the hierarchy and dually by agent, scenario-toggleable, layer-aware. (Doc 12 / doc 04 §H6Q; the bottom-up capture happens in the app — this is the rollup board.)
- **Role:** all desk roles, **layer-aware** (a `volume`-only viewer sees units/coverage/sell-through and **no money**; `commercial` adds revenue; `profitability` adds margin/GP). **Entry:** H6Q Board nav.
- **Layout:**
  - Context: period + **`ScenarioToggle`** (P20/P50/P80, default P50) + **ex-account toggles** (ex-Octopus, ex-Motability — doc 07 M11) + by-channel/by-agent mode switch.
  - **Master grid** (`DataTable`): one row per group at the current drill level — columns: label, market, **forecast** (units; revenue `MoneyOrHidden` if commercial), **weighted pipeline**, **shipped**, **activated**, **coverage %** (`CoverageBar`), **coverage-ex-account %**, **WoW delta**. Margin/GP columns appear only with `profitability`.
  - **`DrillBreadcrumb`**: channel → sub-channel → segment → customer → branch; click a row to drill one level (`/coverage/{group_by}/{key}/children`); a parallel **by-agent** aggregation reconciles to the same totals.
  - Detail drawer: sell-in / sell-through / overhang for the selected node (ship-not-activate = overhang).
- **Data:** `GET /h6q/coverage?period=&scenario=P50&market=&group_by=&key=&ex_account=` (layer-projected), `GET /h6q/coverage/{group_by}/{key}/children?period=&scenario=`, `GET /h6q/sell-through?company_id=|branch_id=&period=`.
- **Actions:** drill down/up (breadcrumb), switch scenario / ex-account / by-agent (re-query, recompute live — no reconciliation step), export (`GET /h6q/export?period=&format=xlsx`, layer-respecting), open the **outstanding** view (D8).
- **States:** layer-stripped (money columns **absent** for volume-only), insufficient history, empty cycle, stale-stamp, error.

### D8 · Outstanding forecasters
- **Purpose:** "who still owes this week" — the operational nudge surface for an open cycle.
- **Role:** desk roles with H6Q view. **Entry:** H6Q board / Home.
- **Layout:** `DataTable` per forecaster: name, accounts outstanding (count), accounts submitted, last-submitted, market/channel. Sort outstanding-first; filter by market/channel.
- **Data:** `GET /h6q/outstanding?cycle=&market=&channel=`, `GET /h6q/cycles?status=open`.
- **Actions:** nudge (emits a notification to the forecaster); drill a forecaster → their outstanding accounts.
- **States:** all-submitted (done), no open cycle, error.

---

## 6. Finance — Ledger & Commission projections

### D9 · Ledger / AR / AP / Inventory projections
- **Purpose:** the relational, searchable projection of the TigerBeetle truth — AR (open invoices by payer), AP, inventory value, all period-bound and traceable. (Postgres projection off the ledger, doc 00/14; figures **tie to TB to the penny**.)
- **Role:** finance (all layers), CEO, auditor (read). **Entry:** Finance nav.
- **Layout:** tabbed `DataTable`s in the global period/entity context:
  - **AR** — open invoices grouped by payer (`bill_to_party`, central-billing aware), ageing buckets, amounts `MoneyOrHidden`.
  - **AP** — supplier obligations.
  - **Inventory** — on-hand value at **specific-identification batch cost** (no averaging — doc 07 M7) by location/variant.
  - Each figure exposes a **`LedgerDrill`** affordance → its TB transfers → events → source docs (hands off to D14).
- **Data:** ledger/AR/AP/inventory projection reads (doc 04 §Ledger; consumed via the Finance read endpoints; AR ties from `bill_to` payer per doc 02 §C). USD consolidated vs per-entity local per the context picker.
- **Actions:** drill a figure → D14 lineage; export (layer-respecting); change period/entity (re-projects).
- **States:** **locked-period** badge (read-only), open-period (live), reconciliation-exception flag on a tie that hasn't matched, layer-stripped, stale-stamp.

### D10 · Commission statements
- **Purpose:** the finance/management view of commission — schemes in effect, accruals, quarterly true-ups, statements per agent/team (full-scope, unlike the agent's own-scope app view).
- **Role:** finance, CEO (commission layer). **Entry:** Finance nav.
- **Layout:** `DataTable` of `commission_entry` rows (agent, order/line, basis = gross margin, rate, amount `MoneyOrHidden`, kind = accrual / true_up_adjustment, status), filterable by agent/period/status; per-agent **statement** view that reconciles to the ledger; true-up run status (posted entries never reopened — the run books the delta as a current-period adjustment, doc 07).
- **Data:** `GET /commission/entries?agent_id=&period=&status=`, `GET /commission/statements/{agent_id}?period=`.
- **Actions:** view statement; export; drill an entry → its order → `LedgerDrill`.
- **States:** clawed/void entries struck-through with reason; true-up delta called out; layer-hidden if no commission layer; statement-reconciles badge.

---

## 7. Supply Planning

### D11 · Replenishment & backorders
- **Purpose:** see demand-vs-cover at the SKU/location level, surface backorders and their ETAs, and act on replenishment signals driven by activation run-rate (doc 07 M9).
- **Role:** finance / supply-planning, fulfilment management (volume layer minimum). **Entry:** Supply Planning.
- **Layout:** `DataTable` per variant/location — on-hand, allocated, available, incoming (`StockItem`), backordered qty, oldest-backorder date, run-rate-driven replenishment suggestion. Backorder sub-view: which orders/tranches wait on incoming POs and their ETAs.
- **Data:** `GET /stock?entity_id=&location_id=&variant=` (`on_hand`/`allocated`/`available`/`incoming`).
- **Actions:** raise a PO from a shortfall (→ D12); drill a backorder → the waiting order/tranche.
- **States:** healthy / at-risk / short (colour per doc 08 status palette), empty, error.

### D12 · Purchase orders & goods receipt (PO/GRN)
- **Purpose:** create POs and receive against them, landing cost and auto-filling backorders.
- **Role:** finance / supply-planning. **Entry:** Supply Planning / from a shortfall (D11).
- **Layout:** PO list (`DataTable`: PO no, supplier, status, lines, ETA); PO create form (supplier, lines, expected dates); **GRN form** — per line: received qty, serials, batch; landed-cost capture (type + amount: freight, duty, FX basis) — feeds **specific-identification** lot cost (doc 07 M7).
- **Data:** `POST /purchase-orders`, `POST /purchase-orders/{id}/receive { lines:[{po_line_id, qty, serials[], batch}], landed_costs:[{type, amount}] }` → `GoodsReceipt` (lands cost, auto-allocates oldest backorders by requested date per tranche).
- **Actions:** create PO; receive (GRN) → stock increments, cost lands, backorders auto-fill, ledger inventory posting emitted.
- **States:** open / partially-received / received; serial/batch capture required for serialised lines; FX-basis (spot vs hedged) shown.

---

## 8. Admin — Permission builder & config

### D13 · Permission builder (roles × permissions × layers × scope)
- **Purpose:** compose roles as data and assign them with scope — the doc-05 model made operable. Roles are seed presets but **cloneable/editable**; the builder writes `permission` rows (object × action × section × layers × breadth) and scoped `role_assignment`s.
- **Role:** `admin` (admin **cannot** approve ADLP, edit audit, or grant itself elevated financial-control approval — server-enforced). **Entry:** Admin.
- **Layout:**
  - **Roles** list (seed + custom); clone/edit a role.
  - **Permission matrix** for a role: rows = object types (`order`, `price_rule`, `party`, `deal`, `adlp_exception`, `commission_entry`, `stock_*`, …), columns = actions (`view`/`create`/`edit`/`approve`/…), with per-cell **section** and **`viewable_layers`/`editable_layers`** chips and a **breadth** selector (`all`/`team`/`own`/`scoped`). The **layer wall is explicit here** — e.g. tick `commercial` but not `inter_entity` on `price_rule` to build the Deal Desk preset.
  - **Assignments**: assign a role to a user with `scope_entities[]`, `scope_markets[]`, `scope_channels[]`, optional `breadth_override`; multiple assignments **union** (doc 05 §1). A live **"effective access" preview** shows what a user with this set would see (and which layers are stripped) — backed by the same projection logic, **read-only simulation**.
- **Data:** `GET/POST/PATCH /admin/roles`, `POST /admin/users/{id}/assignments { role_id, scope_entities[], scope_markets[], scope_channels[], breadth_override? }`, `GET /admin/data-layers`, `PATCH /admin/field-layers`.
- **Actions:** clone/edit role; toggle permission cells/layers; create/revoke assignments (revocation effective **next request** — doc 05 §6); edit the field→layer map (`PATCH /admin/field-layers`, governed/audited). Every change audited (doc 05 §5).
- **States:** SoD guard — admin cannot grant ADLP-approval or financial-control approval to itself (disabled + tooltip); validation on incomplete scope; "this affects N users" confirm; audit-ref on save.

### D14 (config) · Users, config & reference data
- **Purpose:** manage users, entities/markets/channels, segments/sub-channels, currencies/locales, `property_definition` registry (governed custom attributes), tax-regime config refs.
- **Role:** admin. **Entry:** Admin.
- **Layout:** sub-tabs — Users (identity from Keycloak; role(s)/scope read-out), Reference data (markets/currencies/locales/channels/segments — year-1 seed UK only, the 23-market table is the roadmap), Property registry (governed custom props, each with optional `data_layer` tag → layer-projected, doc 05 §3), Channel taxonomy (runtime-extensible, doc 07).
- **Data:** `GET /admin/users/{id}`, `GET /admin/data-layers`, config reads; property-definition CRUD (doc 02 §M, governed/versioned).
- **Actions:** edit reference data (governed/audited); add a custom property (validated against registry); channel attribution (no code, doc 07).
- **States:** governed-change confirm + audit-ref; validation; year-1 single-entity note.

---

## 9. Auditability Center (doc 14 §6)

> The in-product surface that demonstrates — to an auditor — that the numbers are exact, complete, and re-performable. Finance/CFO own close/lock/FX-entry/PPA under maker-checker; the read-only **`auditor`** sees financial-truth + lineage + controls and **edits nothing**.

### D15 · Controls register
- **Purpose:** every `control`, its assertion, owner, frequency, automation, last-run result, with "re-perform now."
- **Role:** finance, CEO, auditor (read). **Entry:** Auditability Center.
- **Layout:** `DataTable` / `ControlCard` grid — `code`, `name`, `objective`, `assertion[]` (existence/completeness/valuation/cutoff/rights&obligations/presentation), `type` (preventive/detective), `frequency`, `automated`, `owner`, last-run status (`StatusChip`: pass/exception), last-run time.
- **Data:** controls register reads (doc 14 §4 `control`, §5 `control_run`).
- **Actions:** **Re-perform now** → runs the control's `evidence_query` live and records a `control_run` (auditor may run; cannot edit the control). View run history.
- **States:** pass / exception (drill to the failing items) / never-run; auditor = read + re-perform only.

### D16 · Reconciliation dashboard
- **Purpose:** the automated ties — TB↔GL, GL↔Xero, inventory↔counts, AR↔open invoices — per period, with sign-off; **a period cannot lock with an open exception** (doc 14 §5.2, doc 07 M13b).
- **Role:** finance (work + sign off), CEO, auditor (read). **Entry:** Auditability Center.
- **Layout:** per period × tie-type grid of **`ReconRow`** (type, scope, expected, actual, **variance**, status `StatusChip`: open/matched/exception, signed-off-by). Drill an exception → the un-matched items. Sign-off trail.
- **Data:** reconciliation reads (doc 14 §5.2 `reconciliation`).
- **Actions:** work an exception (drill to items → resolve via the owning module); **sign off** a matched tie (maker-checker; cannot sign off own correction). Exception blocks period lock (surfaced on D17).
- **States:** matched (green) / exception (red, blocks lock) / open; auditor read-only.

### D17 · Period-close board
- **Purpose:** open/closed/locked periods per entity, the close checklist with sign-offs, and the lock state — with **maker-checker** on close and lock, and **no posting to a locked period** (doc 14 §2.4).
- **Role:** finance (propose close), CEO/CFO (approve close/lock — maker≠checker). **Entry:** Auditability Center.
- **Layout:** periods grid per entity (`accounting_period`: scope day/month/quarter/year, period_key, reporting_tz, status open/closed/locked, closed_by, closed_at); the **close checklist** (`period_close_task`s with sign-offs); reconciliation gate indicator (D16); **`ApprovalBar`** for close → lock (proposer ≠ approver, disabled-for-self).
- **Data:** `accounting_period` + close-task reads (doc 14 §2.4, doc 07 M13b).
- **Actions:** propose close (finance) → approve close/lock (CEO/CFO); **lock blocked while any reconciliation exception is open** (D16) — the UI shows the blocking exceptions and disables lock. Prior-period adjustment to a closed/locked period = a separate controlled flow (maker-checker + CFO approval, fully audited).
- **States:** open / close-proposed / closed / locked (read-only, who/when badged); lock-blocked (with reasons); PPA-pending.

### D18 · Lineage explorer
- **Purpose:** pick any reported number → its TB transfers → the business events → the source documents → **"replay to re-derive"** — proving re-performance (doc 14 §5.1, doc 07 M13b).
- **Role:** finance, CEO, auditor (read). **Entry:** Auditability Center / any `LedgerDrill` affordance (D9, D10).
- **Layout:** **`LedgerDrill`** click-through: figure → transfers (TB, exact integer minor units) → events (the `event_id`s that caused them) → source documents (order, dispatch, GRN, invoice, RMA) — each step linked, each amount `MoneyOrHidden`. A **"replay to re-derive"** action re-computes the aggregate from atoms and shows it ties to the penny.
- **Data:** lineage reads off the ledger/event/document chain (doc 14 §5.1); `GET /audit?entity_type=&entity_id=` for the staff-action context.
- **Actions:** drill in/out; replay-re-derive (read-only, proves the chain); export the lineage as evidence (→ D21).
- **States:** ties (green) / mismatch (red — a finding) / replay-running; auditor read-only.

### D19 · Money-integrity panel
- **Purpose:** show the math is proven, not asserted — active `RoundingPolicy` per jurisdiction/boundary, live conservation checks, FX-rate provenance.
- **Role:** finance, CEO, auditor (read); Treasury for FX entry. **Entry:** Auditability Center.
- **Layout:** **RoundingPolicy** table (per `tax_regime`/boundary: line/invoice/FX/posting, mode — default HALF_UP, JPY 0 minor units etc.); **conservation** live check (`allocate(total, w).sum == total`) with last property-run result; **FX-rate provenance browser** (`exchange_rate`: base/quote/rate/as_of/rate_type spot|hedge/source/captured_at) and `fx_hedge` designations (treasury layer).
- **Data:** `RoundingPolicy` config + property-suite results (doc 14 §1.2, §5.4); `GET /treasury/hedges?...`, FX register reads (treasury layer, doc 06).
- **Actions:** browse policies/rates (read); **FX-rate entry** is maker-checker (Treasury proposes, CFO approves — `ApprovalBar`); auditor read-only.
- **States:** conservation pass/fail (fail = a finding), unprovenanced-rate flag, layer-hidden (treasury) for non-treasury non-auditor users.

### D20 · Time / period panel — preview reslice
- **Purpose:** show the canonical reporting TZ + fiscal calendar and run **"preview reslice"** — choose another TZ and see exactly which transactions change period **before** committing (the §2.2 governance tool).
- **Role:** finance, CEO (governed change), auditor (read). **Entry:** Auditability Center.
- **Layout:** current canonical reporting TZ + fiscal calendar; a **preview-reslice** form (pick alternative TZ/calendar) → a **`DiffView`** listing exactly which transactions move period and the net effect on the affected periods. Committing the canonical change is **CFO-approved, documented, comparatives-restated-where-material** (maker-checker `ApprovalBar`).
- **Data:** period-projection helper over UTC instants (doc 14 §2.1–2.2); reslice preview computed by re-projection (no migration).
- **Actions:** preview reslice (read, safe); propose canonical change → CFO approve (governed). The mechanics are cheap; the **decision is governed**.
- **States:** preview (safe) / change-proposed / committed (audited); auditor read-only.

### D21 · Audit log & evidence export
- **Purpose:** the searchable append-only trail, and signed, time-stamped evidence packs for the external auditor (WORM-style export).
- **Role:** finance, CEO, auditor. **Entry:** Auditability Center / Auditor portal.
- **Layout:** `DataTable` over `audit_log` (entity_type, entity_id, action, actor, before/after, time, `approval_memo_ref` where applicable) — searchable by entity/actor/date; **append-only, no edit control rendered for anyone (Admin cannot edit audit — doc 05 §5).** **`EvidenceExportSheet`** assembles a signed, time-stamped pack (audit + event log + TB references) for a scope/period.
- **Data:** `GET /audit?entity_type=&entity_id=&from=&to=` (read-only); evidence-export job (doc 14 §5.3, WORM-style retention).
- **Actions:** search/filter; build & download an evidence pack (signed, time-stamped). **No edit/delete anywhere.**
- **States:** read-only always; export building / ready / failed.

### D22 · Auditor portal (read-only shell)
- **Purpose:** a deliberately stripped surface for the read-only `auditor` — financial-truth + lineage + controls/reconciliations + audit log, **edits nothing**, PII hidden unless explicitly granted (doc 05 §4, doc 14 §6).
- **Role:** `auditor` only. **Entry:** auditor sign-in lands here (the nav rail shows only Finance-read, Auditability Center, Lineage, Audit/Evidence).
- **Layout:** the read subset of D9, D15–D21 — every edit/approve/sign-off control is **absent** (not disabled-with-tooltip; absent), `MoneyOrHidden` per the auditor's `view` layers (volume/commercial/profitability/commission/inter_entity/treasury all **view**), PII stripped unless granted.
- **Data:** the read endpoints above (all `view`, server-projected). **States:** read-only throughout; layer/PII-stripped per grant.

---

## 10. Cross-cutting acceptance (for the desk)

- **The layer wall holds in the UI by absence, not by hiding.** A Deal Desk user (no `inter_entity`) never receives inter-entity price rules — the rows/fields are **absent from the payload** and the UI shows no greyed placeholder. A `volume`-only H6Q viewer sees **no money column at all** (no `£0`, no blank). PII is absent for roles without `pii`. The client never re-derives a hidden value.
- **No desk control is the authorisation authority.** Every approve/edit/sign-off is server-enforced; the UI mirrors the server projection for layout, disables (or omits) what the principal cannot do, and surfaces `403`/`409`/`422` as clear, actionable banners — never silent.
- **Maker-checker is visible and self-blocking.** Pricing activation, ADLP decision, period close/lock, PPA, manual journal, FX-rate entry, credit-limit change, stock-adjustment/transfer/write-off approval, returns approval all show proposer + required approver; the approve control is **disabled/absent for the proposer**; the decision captures a memo and yields an `AuditRefChip`.
- **Every governed action is reconstructable.** A price/discount/exception/commission/stock/order/permission change drills (D18) from figure → transfers → events → documents and **re-derives by replay**; the audit log (D21) is append-only and editable by no one.
- **A period cannot lock with an open reconciliation exception** (D16/D17); a posting to a locked period is rejected and surfaced.
- **The CEO is the sole ADLP approver and the sole/CFO approver of financial-control actions** — the desk renders the approve control only for them (D6, D17, D19–D20); admin cannot self-grant it (D13).
- **"Preview reslice" is safe and read-only; committing it is governed** (D20).
- Every table/board has loading/empty/error/stale states; every screen respects the global entity/period/scenario context and re-projects (never migrates) on change.
- One React/TS codebase per the hyperstore stack (Vite + React + StyleX, OpenAPI-generated client); the `conduit-desk/` thin slice is the seed.

---

## 11. Suggested build order (screens)

1. **Shell + Auth + design system** (D1–D2): nav rail, top context bar (`EntityPeriodPicker`), command palette, the layer-aware component kit (`MoneyOrHidden`, `LayerGuard`, `DataTable`, `ApprovalBar`, `StatusChip`, `DiffView`, `AuditRefChip`) — built on the existing `conduit-desk/` tokens. (Backs M2/M3.)
2. **Pricing & ADLP** (D3–D4) + **Deal Desk + CEO Approval** (D5–D6) — the layer wall and the maker-checker spine made visible. (M3/M10.)
3. **H6Q Board** (D7–D8) — the deep drill-down board, layer-aware, scenario toggles. (M11.)
4. **Finance** (D9–D10) — ledger/AR/AP/inventory projections + commission statements. (M13.)
5. **Supply Planning** (D11–D12). (M9.)
6. **Admin / Permission builder** (D13–D14) — composes the roles/layers/scope that every screen above honours; build early enough to seed presets, harden here. (M2.)
7. **Auditability Center** (D15–D22) — controls, reconciliation, period close, lineage, money/time panels, audit/evidence export, auditor portal. (M13b.)

> **Phase 2–3 UI** — the desk follows the spine; Pricing/Deal-Desk track M3/M10, the H6Q board M11, Finance M13, and the Auditability Center M13b (doc 07 / doc 10 §B).
