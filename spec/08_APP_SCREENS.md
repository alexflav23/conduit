# 08 — Companion App: Screen-by-Screen Spec (Flutter)

Build spec for the **Flutter companion app** (one codebase → mobile, tablet, web). Audience: field roles — `retail_sales_agent` (and other agents/account owners), `customer_service_agent`, `fulfilment_agent`. The back-office desk (pricing governance, permission builder, full ledger, deep H6Q board) stays in the React/TS web app and is **out of scope here**.

This doc is written to hand to **Claude Design**. Each screen lists: **purpose · role · entry · layout/components · data (→ API from doc 06) · actions (→ effects) · states**. It is grounded in the data model (02), domain logic (04) and API (06) — don't invent fields or endpoints beyond those.

---

## 0. Design language & global patterns

**Brand / theme.** Hypervolt: premium, clean, confident, lots of negative space. Primary accent **`#962DFF`** (Hypervolt purple); near-black surfaces optional (dark mode first-class). Material 3 as the base, custom-themed — rounded 12–16px cards, generous spacing, one strong accent, restrained use of colour for status (green=on-track, amber=attention, red=blocked/over-limit). Typography: clean grotesk/sans; large legible numerals (this app is numbers-forward — units, money, coverage %).

**Platform / layout.** Adaptive: **bottom navigation** on phones; **navigation rail + two-pane** (list/detail) on tablet & web. Same widgets, responsive breakpoints. Touch-first targets; keyboard-friendly numeric entry for forecasting.

**Auth.** Keycloak OIDC; biometric/PIN unlock on return; silent token refresh; one identity = one person, scoped to *their* accounts.

**Data-layer awareness (critical).** The server projects fields by the user's data layers (doc 05). The UI must **gracefully omit** money where the role lacks `commercial`/`profitability`/`commission` — never render an empty/zero placeholder. A `volume`-only user sees units and coverage but no revenue/margin. Build money widgets to accept "hidden" and collapse.

**Offline & sync.** Field use across timezones with patchy signal. Forecast drafts and order drafts persist locally and **queue**; a persistent offline banner shows pending sync; on reconnect the app re-submits and the **server re-validates** (pricing, ADLP, credit, allocation) — server wins, surfaced as a clear post-sync notice. Read screens cache last-known with a "as of <time>" stamp.

**Localization (i18n).** The app ships fully localized to the supported locales (Flutter `intl`/ARB; languages: en, es, fr, de, nl, ga, it, pt, pl, no, sv, da, fi, ja, th). Locale follows the signed-in user's preference, with per-account `preferred_locale` driving customer-facing content (quotes, product names via `product_translation`). All numbers, currencies and dates format per locale; fonts cover **CJK (Japanese) and Thai** plus European diacritics. No hard-coded strings; pseudo-localization in CI to catch truncation. (No RTL languages are in scope.)

**Global UI furniture.** Top app bar (screen title + context), pull-to-refresh on all lists, empty/loading/error states everywhere, a notification bell (badge), and a global "+" for the role's primary create action.

**Reusable components (for the design system).** `AccountCard`, `ScenarioStepper` (P20/P50/P80 segmented control), `SkuQtyRow` (variant + numeric stepper/keypad), `MoneyOrHidden` (layer-aware), `UnitsBadge`, `StatusChip` (order/deal/forecast/exception states), `CoverageBar` (forecast vs pipeline vs shipped), `SubmitBar` (sticky bottom CTA), `OfflineBanner`, `SyncToast`, `ScanButton` (serial), `SignaturePadless ConfirmSheet`.

---

## 1. Navigation map

```
Splash/Auth
└── App shell (role-adaptive tabs)
    ├── Home (dashboard)
    ├── Forecast        (agents/owners)         → Account forecast → Confirm
    ├── Accounts (CRM)  (agents/CS)             → Account detail → Contact / Branch / History
    │     └── Deals                              → Deal detail/edit
    ├── Orders          (agents/CS/fulfilment)   → Create order (quote) → Order detail → Exception status
    ├── Commission      (agents)                 → Period detail → Order breakdown
    ├── Lookup/Scan     (CS/fulfilment/agents)   → Serial detail (genealogy, warranty)
    ├── Service         (customer_service_agent) → Warranty claim / RMA-refund
    ├── Fulfil          (fulfilment_agent)       → Allocate / Dispatch-scan
    └── More            → Notifications · Profile · Settings
```
Tabs shown are role-gated (e.g. an agent sees Home/Forecast/Accounts/Orders/Commission; CS sees Home/Accounts/Orders/Lookup/Service; fulfilment sees Home/Orders/Fulfil/Lookup).

---

## 2. Auth & shell

### S1 · Splash / Login
- **Purpose:** authenticate; restore session.
- **Role:** all. **Entry:** app launch.
- **Layout:** Hypervolt mark centered on accent/dark; "Sign in" (Keycloak OIDC web flow); biometric prompt on return. Minimal.
- **Actions:** Sign in → OIDC → land on Home. Biometric unlock.
- **States:** loading; auth error (retry); no-network (allow cached read-only if a valid session exists).

### S2 · Home (dashboard)
- **Purpose:** "what needs me now," role-tuned.
- **Role:** all (cards vary by role/layer). **Entry:** post-login default tab.
- **Layout:** greeting + sync/offline banner; stacked action cards:
  - **Forecast due** (agents): "Week 2026-W23 — 4 of 9 accounts submitted," CTA → Forecast.
  - **My commission** (agents, commission layer): running period total (`MoneyOrHidden`), accrued vs trued-up, sparkline; CTA → Commission.
  - **Orders needing attention:** exceptions pending CEO, backordered, delivered-today.
  - **My accounts** snapshot: count, coverage vs forecast (`CoverageBar`, volume layer).
  - **Notifications** preview.
- **Data:** `GET /h6q/my-forecasts?cycle=current` (counts), `GET /commission/entries?agent_id=me&period=current`, `GET /orders?agent_id=me&status=...`, `GET /h6q/coverage?group_by=agent&key=me`.
- **States:** first-run (no accounts), all-done (forecast complete), offline (cached + stamp).

---

## 3. Forecasting (H6Q) — the core agent loop

### S3 · Forecast cycle home — "My accounts this week"
- **Purpose:** show the open weekly cycle and the accounts the owner must forecast.
- **Role:** agents/account owners. **Entry:** Forecast tab / Home card.
- **Layout:** cycle header (`2026-W23`, closes in Nd, progress ring X/Y submitted); list of **`AccountCard`s** = each owned account/branch with status chip (Outstanding / Submitted / Skipped), last-submitted timestamp, quick total. Filter/sort (outstanding first, by market/segment). Search.
- **Data:** `GET /h6q/my-forecasts?cycle=current` → accounts + status + last estimate.
- **Actions:** tap account → S4; "Skip" (with reason); pull-to-refresh.
- **States:** all submitted (celebratory done-state), none outstanding, offline (queued submissions flagged).

### S4 · Account forecast entry
- **Purpose:** enter demand for one account, per SKU, per scenario, per month.
- **Role:** owner of the account. **Entry:** from S3.
- **Layout:**
  - Account header (name, branch/parent, segment, channel, market).
  - **`ScenarioStepper`**: P20 / P50 / P80 (default P50).
  - Horizon selector (months — next N months).
  - **SKU list** = `SkuQtyRow` per variant (family · length · colour · connector), numeric keypad/stepper, prefilled with **last estimate**; **new catalogue SKUs appear automatically** with a "new" tag. Group by family; search/add SKU.
  - Optional note per account.
  - Sticky **`SubmitBar`**: running unit total + "Review & submit".
- **Data:** live catalogue from `GET /catalogue`; prefill + structure from `GET /h6q/my-forecasts` (the account's lines). 
- **Actions:** edit qty (auto-saves draft locally), switch scenario (independent qty sets), Review → S5.
- **Rules:** estimates are per (variant, month, scenario); the three scenarios are separate numbers. Draft persists offline.
- **States:** loading catalogue; offline (draft only, "will submit on sync"); validation (negative/blank → 0).

### S5 · Review & confirm submission
- **Purpose:** confirm the week's estimate for the account.
- **Layout:** summary by scenario (totals + per-SKU), diff vs last week (▲▼), note; big **Submit** CTA.
- **Data/Action:** `POST /h6q/my-forecasts/{company_id}/submit { cycle, lines[] }` → sets submission `submitted`; emits `forecast.submitted` (auto-rolls up). Returns to S3 with status updated + `SyncToast`.
- **States:** submitting; offline-queued ("submitted locally, syncing"); server rejection on sync (rare — e.g. account reassigned) with clear message.

### S6 · My forecast history & accuracy
- **Purpose:** the owner's track record — every past estimate vs actuals.
- **Role:** agents (own scope). **Entry:** Forecast tab → history; account detail.
- **Layout:** per account/period: estimate (by scenario) vs actual sell-in/sell-through; error/bias/MAPE chips; trend chart. Append-only history (versions visible).
- **Data:** `GET /h6q/accuracy?forecaster=me&company=&period=`.
- **States:** insufficient history (needs ≥1 closed period).

---

## 4. Commission (agents)

### S7 · Commission summary
- **Purpose:** real-time own commission.
- **Role:** agents with `commission(own)`. **Entry:** Commission tab / Home card.
- **Layout:** big period figure (`MoneyOrHidden`), **accrued vs trued-up** split, status (pending/posted), scheme in effect (name + validity), attainment toward tier; period switcher (current quarter default). List of contributing orders/lines.
- **Data:** `GET /commission/entries?agent_id=me&period=`, `GET /commission/statements/{me}?period=`.
- **Rules:** own-scope only; real-time accrual on placement, quarterly true-up shown as adjustments; if role lacks commission layer this tab is hidden entirely.
- **States:** no commission yet; period closed (trued-up badge).

### S8 · Commission period / order breakdown
- **Purpose:** drill into how a figure was earned.
- **Layout:** list of `commission_entry` rows (order, line, basis = gross margin, rate, amount, kind=accrual/true_up_adjustment, status); link to the order. True-up deltas called out.
- **Data:** `GET /commission/entries?agent_id=me&period=` filtered by order.
- **States:** clawed/void entries shown struck-through with reason.

---

## 5. Accounts / CRM

### S9 · My accounts
- **Purpose:** the owner's book of business — organizations (incl. branches) and individuals.
- **Role:** agents (own), CS (market scope, read). **Entry:** Accounts tab.
- **Layout:** searchable list of `AccountCard` (name, level badge master/branch, segment, channel/market, parent indicator, owner, status chip); filters (segment, market, channel, level, my-branches). A master expands to its branches; individuals shown with a person glyph.
- **Data:** `GET /companies?q=&level=&parent_id=` and `GET /individuals?q=` (scope-filtered).
- **Actions:** tap → S10; "+" new contact/deal/branch (agents).
- **States:** empty (no accounts assigned), offline cache.

### S10 · Account detail
- **Purpose:** 360° on a party — a master, a branch, or an individual.
- **Layout:** header (name, account code, **level + parent link**, segment, **bill-to/payer** and **credit scope** `MoneyOrHidden`, account manager); tabs: **Overview** (channel/market, addresses, **branches** for a master, bill-to relationship), **Contacts** (this node/branch), **Deals**, **Orders** (with customer PO), **Activity/History**, **Forecast** (this node's coverage; for a master, rolled up from branches with drill-in).
- **Data:** `GET /companies/{id}`, `/branches`, `/rollup`, `/history`; `GET /h6q/sell-through?company_id=` (branch or master).
- **Actions:** add note/activity; start deal; **create order (defaults sold-to = this branch, bill-to = its payer)**; add branch (master); call/email contact.
- **States:** layer-stripped (credit/revenue hidden for volume-only); on-hold/credit-block banner (showing whether limit is the branch's own or the master pool).

### S11 · Contact detail
- **Purpose:** person record.
- **Layout:** name/role/email/phone (pii layer), consent flag, related deals/activities; call/email/message actions.
- **Data:** contact under `GET /companies/{id}`. **States:** pii hidden if lacking layer.

---

## 6. Deals (pipeline)

### S12 · Pipeline & Deal detail
- **Purpose:** manage opportunities that feed forecast + convert to orders.
- **Role:** agents. **Entry:** Accounts → Deals, or Deals sub-tab.
- **Layout:** **Pipeline** = horizontally scrollable stage columns (kanban) on tablet/web, vertical grouped list on phone; deal cards (name, value `MoneyOrHidden`, expected close, stage probability, scenario volumes P20/50/80). **Deal detail/edit**: company/contacts, owner, stage (drag/stepper), value, expected close, P-volumes, deal lines (variant + qty + ADLP-resolved price), notes.
- **Data:** `GET /deals?...`, `PATCH /deals/{id}`, `POST /deals/{id}/win → {order_id}`.
- **Actions:** change stage (emits `deal.stage_changed`, updates weighted pipeline/coverage); **Win** → creates order (S14). Add deal line (priced via `POST /pricing/quote`).
- **States:** won/lost states; offline draft.

---

## 7. Orders

### S13 · Orders list
- **Purpose:** track the owner's orders.
- **Role:** agents (own), CS, fulfilment (scope). **Entry:** Orders tab.
- **Layout:** list with `StatusChip` (placed / pending_ceo / allocated / backordered / dispatched / delivered / invoiced / closed / refunded); filters by status/account/date; search by order no. Badge for exceptions pending CEO.
- **Data:** `GET /orders?agent_id=me&status=`.
- **Actions:** "+" create (S14); tap → S15.
- **States:** empty, offline cache.

### S14 · Create order (quote → place)
- **Purpose:** build and place an order; surface ADLP & commission in real time.
- **Role:** agents (CS limited). **Entry:** Orders "+", Account, or Deal-win.
- **Layout:**
  - **Party selectors:** sold-to (organization branch *or* individual), bill-to/payer (auto-filled from the branch's central-billing master, editable), ship-to (consignee address — the branch site). **Customer PO number** field (required when the account flags `customer_po_required`).
  - Line builder: add variant, qty; **live price** from quote (ex/inc VAT, resolved list, **discount slider bounded by ADLP `max_discount_pct`**); per-line `StatusChip` Standard vs **Exception**.
  - **Commission preview** (`MoneyOrHidden`, own) updating live.
  - Order totals, payment method (retail=Stripe handled upstream/Athena; trade=credit/invoice), requested delivery.
  - Sticky `SubmitBar`: "Place order".
- **Data:** `POST /pricing/quote` (live as lines change), `POST /orders`.
- **Rules / states:**
  - If any line discount exceeds the band → line flagged **Exception**; placing routes order to **`pending_ceo`** (S16) — no allocation, **commission preview shows £0 on those lines** until approved.
  - Credit check on trade orders → warning or **block** (422) per account policy.
  - Offline: order saved as draft, queued; server re-validates price/ADLP/credit/stock on sync (prices/stock may have moved → clear diff shown).

### S15 · Order detail
- **Purpose:** full status & fulfilment timeline.
- **Layout:** header (order no, account, status, totals `MoneyOrHidden`); lines (qty, allocated, dispatched, backordered, ADLP category, price provenance); fulfilment timeline (placed→allocated→dispatched→delivered→invoiced); serials on dispatch; invoice (auto on delivery) + Xero status; refund/RMA entry (CS).
- **Data:** `GET /orders/{id}`.
- **Actions (role/state-gated):** amend (pre-dispatch), cancel, allocate (fulfilment), dispatch (fulfilment, S19), raise refund/RMA (CS, S18).
- **States:** exception-held banner; backorder ETA from incoming PO; delivered→invoiced auto note.

### S16 · Exception status
- **Purpose:** show an order/line held for CEO ADLP approval (agent is read-only here — they assemble justification but cannot approve).
- **Layout:** requested price/discount vs band, justification, volume expectation (P-denomination), strategic note, status (pending_ceo/approved/rejected), decision memo when decided.
- **Data:** `GET /adlp/exceptions?status=` (own orders); submit justification `POST /adlp/exceptions/{id}/submit`.
- **Rules:** **no approve control in this app** — approval is CEO on the desk. On approval the order releases (push notification), commission un-zeroes.
- **States:** pending / approved (order proceeds) / rejected (re-price).

---

## 8. Catalogue & quote (support surface)

### S17 · Catalogue / quick quote
- **Purpose:** browse products and get an indicative price without an order.
- **Role:** agents, CS. **Entry:** Orders/Deal builder, or standalone.
- **Layout:** product list (family → variant: length/colour/connector), stock indicator (volume layer), price for a chosen account/channel/market (`MoneyOrHidden`), discount band shown. "Add to order/deal".
- **Data:** `GET /catalogue?channel_id=&market_id=&currency=`, `POST /pricing/quote`.
- **States:** out-of-stock badge; price-not-found (no rule) message.

---

## 9. Serial / activation lookup

### S18 · Lookup / Scan → Serial detail
- **Purpose:** field lookup of a unit (warranty, genealogy, activation).
- **Role:** CS, fulfilment, agents. **Entry:** Lookup/Scan tab; `ScanButton` (camera → serial/QR).
- **Layout:** scan or type serial → **Serial detail**: status (in_stock/allocated/dispatched/activated/returned), generation (V2/V3), genealogy (batch → order → customer → lifecycle timeline), **warranty** (start=activation, end=legal+extension, provision/exposure if finance layer), activation (installer/owner/placement), claims.
- **Data:** `GET /serials/{serial}`, `GET /batches/{batch_no}/serials` (recall).
- **States:** not found; V2 legacy note; not-yet-activated.

---

## 10. Customer service (role: `customer_service_agent`)

### S19 · Warranty claim & RMA/refund
- **Purpose:** raise a warranty claim or return/refund against a delivered unit/order.
- **Layout:** from a serial (S18) or order (S15): claim form (description, resolution repair/replace/reject, cost) → draws down provision; RMA/refund form (lines, reason, amount).
- **Data:** `POST /warranty/claims`, `POST /orders/{id}/refund`.
- **States:** confirmation; offline-queued; ineligible (out of warranty) note.

---

## 11. Fulfilment (role: `fulfilment_agent`)

### S20 · Allocate / Dispatch (scan)
- **Purpose:** allocate stock and dispatch with serial capture.
- **Layout:** order queue (allocatable/ready-to-dispatch); allocate action (shows ATP per location); dispatch screen: pick carrier, **scan serials** per line (`ScanButton`), tracking no; confirm.
- **Data:** `POST /orders/{id}/allocate`, `POST /orders/{id}/dispatch { carrier_id, lines:[{line_id, qty, serials[]}], tracking_no }`; stock from `GET /stock`.
- **Rules:** serialised line cannot dispatch without scanned serials (server 422); concurrency-safe allocation server-side.
- **States:** insufficient stock (backorder), partial dispatch.

---

## 12. Notifications, profile, settings

### S21 · Notifications
- Forecast cycle opened / closing soon; exception approved/rejected; order delivered/backorder filled; commission trued-up. Tap → deep link. Data: notification stream (consumer of events). Badge on bell.

### S22 · Profile & settings
- Identity (Keycloak), role(s) & scope (read-only display of what they can see), theme (light/dark), language/locale & currency display, biometric toggle, sync status & "force sync", sign out. Data: from token + `GET /admin/users/{me}` (self).

---

## 13. Cross-cutting acceptance (for the app)

- A `volume`-only user never sees a money figure anywhere (cards collapse, no zeros).
- Forecast capture works fully offline and reconciles on sync; new SKUs appear without an app update.
- Placing an order with an out-of-band discount visibly holds it (`pending_ceo`) and zeroes that line's commission preview until CEO approval (which the app cannot perform).
- Commission shown is always own-scope, real-time, with accrued vs quarterly true-up distinguished.
- Every list has loading/empty/error/offline states; every screen is legible one-handed on a phone and expands to two-pane on tablet/web.
- One Flutter codebase; no platform-specific screens.

---

## 14. Suggested build order (screens)

1. Shell + Auth (S1–S2) + theme/design system + global patterns (offline, layer-aware widgets).
2. Forecast loop (S3–S6) — the highest-frequency, highest-value flow.
3. Commission (S7–S8).
4. Accounts/CRM (S9–S11) + Deals (S12).
5. Orders (S13–S16) + Quote (S17).
6. Lookup/Serial (S18); then role surfaces Service (S19) and Fulfil (S20).
7. Notifications/Profile (S21–S22).
