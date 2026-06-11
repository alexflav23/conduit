# 27 — UI Feature Map: page-by-page, feature-by-feature (the concrete design contract)

**Why this doc exists:** previous design passes starved on specifics. Doc 20 specifies the *target* screens
(D1–D22); doc 22 sets the design language and workflow. This doc maps **what is actually built today** —
every page, every feature, every control (by `data-testid`), every API call, every state — against its
doc-20 target, with a **real screenshot of each page running against the live API** in
[`design-assets/desk/`](./design-assets/desk/). A designer can open the screenshot, find every feature in
the table, and know exactly what the redesigned screen must still do.

**Ground rules (bind, don't reinterpret):**
- Every `data-testid` in these tables is a **behavioural contract** — the Playwright suites click them.
  Restyle freely; keep the testids on the equivalent control.
- Every API row is the **data contract** — the desk has no mock data; all screens bind to the running backend.
- The four state columns (loading / empty / error / forbidden) are **acceptance criteria**: today most pages
  handle only the happy path (a class of crash-on-error bugs was found and fixed *while capturing these
  screenshots* — §14). The design must specify all four states for every feature.
- Tokens: `src/styles/tokens.stylex.ts` (accent `#962DFF`, dark-first). Component kit: doc 20 §9 (25 named
  components). Data-layer wall: doc 05 — money fields the role can't see **collapse**, never render zeros.

Per-page sections follow. `[shot]` = screenshot file in `design-assets/desk/`.

---

## 1. Order Desk — `[D-order-desk.png]` → doc 20 D2/D12

**Job:** place a compliant trade order in <60s, keyboard-only (doc 07 M4 acceptance). **Persona:** sales ops.

| Feature | Control today | API | Notes for design |
|---|---|---|---|
| Line entry (SKU, qty, unit price) | `sku` `qty` `unit-price` | — | Must become a multi-line grid (today: single line). Keyboard-first: Enter adds line, Tab order fixed |
| Tier-governed quote | `quote-btn` → `quote` `resolved-ex-vat` `vat-total` `total-inc-vat` `adlp` | `POST /pricing/quote` | Quote BEFORE place is the doc-24 invariant: nobody types a price; show the resolved tier + ADLP category as first-class chips |
| Non-tier price rejection | `error` | 422 from quote/place | The 422 is a *feature* (tier governance) — design the rejection as guidance ("nearest tier: …"), not a failure toast |
| Place order | `place-btn` → `order` `order-no` `order-status` | `POST /orders` | Success = order number + status chip; `pending_ceo` hold state must be visually loud (it blocks fulfilment) |

States to design: quote-in-flight, empty cart, 422 tier-violation, 401, credit-block hold, `pending_ceo` hold.

## 2. Deal Desk — `[D5-deal-desk.png]` → doc 20 D5/D6

**Job:** the maker-checker spine for price exceptions (doc 24: an exception is a governed tier *request*).
**Personas:** deal-desk maker (narrative), CEO/admin checker (decision).

| Feature | Control today | API | Notes |
|---|---|---|---|
| Pending exception queue | `load-pending` `exc-list` (rows `exception`) | `GET /adlp/exceptions?status=pending_ceo` | Queue = the worklist pattern (doc 20 D2); age + deviation are the sort keys |
| Exception detail | `exc-status` `exc-list-price` `exc-band` `exc-requested` `exc-deviation` `exc-chip` | `GET /adlp/exceptions/{id}` | Deviation vs band is THE number — design it as the hero metric |
| Narrative (maker) | `narr-volume` `narr-denomination` `narr-strategic` `narr-justification` `narr-notes` → submit | `POST …/{id}/submit` | Structured justification, not free text — design as a form with completeness affordance |
| Decision (checker) | decision controls | `POST …/{id}/decision` | The decision IS the activation (releases + re-quotes held orders) — make the consequence explicit in the confirm |

States: empty queue (a *good* state — celebrate it), my-own-request (maker ≠ checker, render read-only), decided-elsewhere race.

## 3. H6Q — `[D7-h6q-board.png]` → doc 20 D7/D8 + doc 20-H6Q (the deepest board)

**Job:** the demand board — capture agent forecasts, read coverage vs actuals, reconcile by agent.
**Personas:** sales agents (capture), exec/ops (board).

| Feature | Control today | API | Notes |
|---|---|---|---|
| Board/Capture mode switch | `h6q-tab-board` `h6q-tab-capture` | — | Two personas, one screen today — design may split them |
| Capture: my accounts + cycle | `h6q-load-mine` `h6q-cycle` `h6q-account` | `GET /h6q/my-forecasts` | Weekly agent ceremony — optimize for repeat speed |
| Capture: submit (SKU-mix aware) | `h6q-submit` `h6q-cap-status` | `POST /h6q/my-forecasts/{company}/submit` | Counts split per-SKU by governed mix (doc 12) — show the split, don't hide it |
| Coverage board | `h6q-grand-total` `h6q-by-branch` matrix rows `h6q-matrix-row` | `GET /h6q/coverage` `…/matrix` `…/by-sku` | THE data-dense board (doc 20-H6Q is its dedicated spec): market × period × scenario × group-by, P50/P80/P20 scenario toggle |
| Reconcile view | `h6q-mode-matrix` `h6q-mode-reconcile` | `GET /h6q/coverage/reconcile` | Forecast vs shipped vs activated per agent — the accountability view |
| Model rows in the spine | (rendered in board) | `forecast_entry source='model'` | The engine's 12,396 live rows surface here — visually distinguish model vs human rows (doc 26) |

States: untrained/no-data cells ("n/a, never silently zero"), stale cycle, scenario-switch in flight.

## 4. Flow — `[D9-flow.png]` → doc 20 D9

**Job:** the demand→revenue waterfall (7 distinct quantities; doc 12 §waterfall) + the immutable-ledger drill.

| Feature | Control today | API | Notes |
|---|---|---|---|
| Variant + load | `flow-load` `flow-variant` | `GET /h6q/waterfall?variant&period` | — |
| The 7-stage waterfall | `flow-grid` `flow-row-revenue` | same | forecast → cm_committed → produced → delivered → ordered → shipped → revenue: design as a true waterfall viz; stages DON'T equate — gaps are the story |
| Ledger totals + drill | `ledger-totals` `ledger-table` `ledger-row` | `GET /h6q/ledger` | Every figure traces to TigerBeetle transfers — the drill is the auditability promise on this screen |

## 5. Supply — `[D11-supply.png]` → doc 20 D11/D12

**Job:** the CM commitment window (firm/flex/indicative zones) + auto-PO proposals + divergence warnings.
**Persona:** supply ops. This screen carries the M9c ladder (signal-driven re-issue, versioned immutable).

| Feature | Control today | API | Notes |
|---|---|---|---|
| CM picker + load | `supply-load` `supply-cm` | `GET /h6q/suppliers` | Volex / Luxshare parallel lanes |
| Commitment ladder | `supply-commitments` rows `supply-commit-row` | `GET /h6q/supply/commitments` | Zone (firm/flex/indicative) is the core visual: design zones as a horizon band, version + reason (calendar vs forecast_deviation) visible |
| Auto-PO proposals | `supply-proposals` `supply-proposal-row` `supply-approve` | `GET …/proposals` `POST …/approve` | Approve = the human gate on the proposer; headroom context required |
| Divergence warnings | `supply-warnings` `supply-warning-row` | `GET …/warnings` | Frozen-window demand changes vs firm PO — warning, never silent drop |

## 6. Shelf — `[D11-shelf.png]` → doc 20 D11

**Job:** per-account shipped / activated / on-shelf in real time (the sell-through truth feeding depletion + runway).

| Feature | Control today | API | Notes |
|---|---|---|---|
| Shelf board | `shelf-load` `shelf-board` `shelf-row` | `GET /h6q/shelf` | Add: runway days + measured reorder point per account (data exists: `account_forecast_state`) — the actionable column is "who crosses reorder next" |

## 7. Finance — `[D9-finance.png]` → doc 20 D9/D10

| Feature | Control today | API | Notes |
|---|---|---|---|
| P&L by market/period | `fin-period` `fin-load-pnl` `fin-pnl` `fin-revenue` `fin-margin` | `GET /finance/pnl` | Data-layer gated: margin collapses for roles without the layer (doc 05) — design the collapsed state explicitly |
| Cash waterfall | `fin-load-wf` `fin-waterfall` `fin-wf-row` | `GET /finance/cash-waterfall` | — |
| Credit terms editor | `fin-party` `fin-load-terms` `fin-terms-days` `fin-save-terms` `fin-terms-status` | `GET/PUT /parties/{id}/credit-terms` | Mutation = maker action; confirm + audit affordance |

## 8. Documents — `[D17-documents.png]` → doc 17 / doc 20 D17

| Feature | Control today | API | Notes |
|---|---|---|---|
| Search by invoice/order | `doc-invoice-no` `doc-load` `doc-table` `doc-row` | `GET /documents?invoice_no|order_id` | — |
| WORM PDF download | `doc-download` | `GET /documents/{id}/pdf` | Served from the object-locked store — surface immutability ("final, sealed") in the UI language |
| Void / credit-note / refund | `void-kind` `void-reason` `void-submit` `void-status` `doc-voided` | `POST /invoices/{no}/void` | Never deletes — issues the reversing document; design as a paired-document flow |

## 9. Lifecycle — `[D21-lifecycle.png]` → doc 20 D21

| Feature | Control today | API | Notes |
|---|---|---|---|
| Order lifecycle replay | `life-order-id` `life-load` `life-cycles` `life-cycle-row` `life-timeline` `life-event` `life-when` `life-origin` | `GET /orders/{id}/lifecycle` | The event-sourced audit reconstruction — design as a true timeline with origin chips (user/consumer/relay) |

## 10. Audit — `[D15-18-audit.png]` → doc 20 D15+D16+D17+D18 (four target screens live here today)

| Feature | Control today | API | Notes |
|---|---|---|---|
| Close board | `aud-load-periods` `aud-periods` `aud-period-row` `aud-period-status` | `GET /finance/periods` | open → closed → locked progression; locked = posting rejected at the ledger boundary |
| Reconciliations per period | `aud-load-recs` | `GET …/{id}/reconciliations` | Gate on close: unmatched recs block |
| Close / lock actions | `aud-close` `aud-lock` | `POST …/close` `…/lock` | Hard finance actions — two-step confirm, show what becomes immutable |
| Controls register | `aud-load-controls` `aud-controls` `aud-control-row` + per-control run | `GET /finance/controls` `POST …/{code}/run` | Re-performable SOX controls (CTRL-*) — pass/fail history matters, not just last run |
| Lineage explorer | `aud-invoice` `aud-load-lineage` `aud-lineage` | `GET /finance/lineage?invoice_no` | Invoice → ledger transfers → events; the "prove this number" tool |

Design note: doc 20 splits this into D15–D18 — the redesign should too; today's single tab is a capacity compromise.

## 11. Tax — `[D-tax.png]` → doc 16

| Feature | Control today | API | Notes |
|---|---|---|---|
| Determination tester | `tax-from` `tax-to` `tax-region` `tax-postcode` `tax-party-status` `tax-vatid` `tax-amount` `tax-currency` `tax-quote-btn` → `tax-total` `tax-reverse` `tax-components` `tax-comp-row` `tax-supply-kind` | `POST /tax/quote` | Shows supply kind (domestic/IC/export), reverse-charge flag, per-component rates — design as an explainable result, the "why this rate" panel |
| Rate table admin | (rates list; propose/activate) | `GET/POST /tax/rates` `…/activate` | Effective-dated, maker-checker — never edit-in-place |
| Routing + selling entities + VAT exposure | — | `GET /tax/routing` `…/selling-entities` `…/vat/exposure` | Partially surfaced today; doc 16 §UI lists the full set |

## 12. Forecast Engine — `[forecast-engine.png]` (new, not in doc 20)

The interactive explainer (registry, tournament stepper, depletion playground, convergence, bands, β,
H6Q-vs-model). Already designed-in-miniature; treat as a **content page**: typography + data-viz polish only.
Numbers are currently baked at build time — design should assume a future API binding (same layout, live values).

---

## 13. Cross-cutting gaps the design must close (found while building this map)

1. **The four states are mostly missing.** Pages render happy-path only. Every feature table row needs
   loading / empty / error / forbidden treatments (doc 22 §invariants; the collapsed-not-zero rule for layers).
2. **Crash-on-error class (fixed, but the lesson stands):** pages mapped API *error JSON* into table state and
   threw (`rows.map is not a function`) — found live while capturing these screenshots on Shelf, Audit, Finance,
   H6Q. Guards added; the design system's data-table component must own the error/empty/forbidden states so
   individual pages can't regress.
3. **Auth is a dev token box** (`token` testid) — D1 (login/session, Keycloak) is undesigned and unbuilt.
4. **No worklist/home** (D2): the desk opens on Order Desk; doc 20's role-aware worklist is the intended landing.
5. **Navigation is a flat 12-tab row** — doc 20's nav map groups by domain (Sell / Plan / Supply / Finance /
   Govern); the redesign should adopt that grouping or better.
6. **Several D-screens have no page yet**: D3/D4 (price-rule list/detail), D10 (commission statements),
   D13/D14 (permission builder, config), D19/D20 (money-integrity, period panel), D22 (auditor shell).
   They bind to existing APIs — design them from doc 20; build follows.

## 14. Deliverable checklist for the design pass (per page)

For each of the 12 pages above (+ the 8 unbuilt D-screens, doc 20):
- [ ] High-fidelity dark-first layout honouring every feature row (testids preserved on equivalent controls)
- [ ] The four states per feature (loading / empty / error / forbidden-collapsed)
- [ ] Keyboard map (doc 20 §0: the desk is keyboard-fast or it fails)
- [ ] Density spec: these are operator consoles — doc 20-H6Q's "extremely readable, extremely functional" bar
- [ ] Component-kit mapping (which of the 25 doc-20 components, or a new one with a name and contract)
