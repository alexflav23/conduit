# 07 — Build Plan

Build in milestones. A milestone is **done** only when its acceptance tests pass. Do not start downstream modules before the **Phase-1 spine** is green — everything consumes it.

## Test strategy

- **Unit:** domain logic in doc 04 (pricing resolution, ADLP categorisation, ATP/allocation, commission, coverage, ledger posting rules) — pure functions, table-driven.
- **Integration (it):** repositories against a real Postgres (testcontainers); ledger against a TigerBeetle test cluster; consumers against an embedded Pulsar.
- **Contract:** every Avro schema validated `BACKWARD` against the registry in CI.
- **Authz:** per endpoint — in-scope allow / out-of-scope deny / layer-stripped projection.
- **Concurrency:** allocation race test (N parallel allocators on 1 unit → exactly one wins).
- **Idempotency:** redeliver every event twice → identical state; activation re-fire → no double.
- **End-to-end golden path:** deal→quote→order→allocate→dispatch→activate→invoice→commission→coverage, asserting events, ledger balances and projections.

---

## Phase 1 — Spine (no feature work until green)

**M1 Foundations** — migrations for doc 02; `outbox_event`; Pulsar topics + Avro registry + relay; TigerBeetle ledgers per currency + Ledger-poster consumer skeleton; Keycloak OIDC. **Financial-integrity core (doc 14):** typed `Money`/`Currency` + `RoundingPolicy` + conserving allocation + Squants quantities; `exchange_rate`; UTC-instant discipline + period-projection helper; `accounting_period` (open/closed/locked) with ledger-boundary lock; `control`/`control_run`/`reconciliation`/`period_close_task` tables; ScalaCheck property suite.
- *Accept:* a write + outbox row commit atomically; relay publishes in `partition_key` order; a sample consumer dedupes a redelivered event; a deterministic transfer id makes ledger posting idempotent; **no float exists in any money path (CI rule); `allocate(total,w).sum == total` holds under property test; `usd + eur` fails to type-check; the same events bucket into different months under two reporting timezones purely by re-projection; a posting to a `locked` period is rejected.**

**M2 Access control** (doc 05) — policy layer, scope filtering, data-layer projection, preset roles, permission builder API.
- *Accept:* "UK wholesale only" user sees only UK-wholesale rows on every list; Deal Desk cannot read `price_rule.inter_entity` (absent from payload); revoking a grant denies on next request; all enforced server-side.

**M3 Catalogue + ADLP pricing** (doc 04 §Pricing) — families/variants (incl. connector type, retail/trade/mrp_sku, generation), `price_rule`, `/pricing/quote`.
- *Accept:* changing a partner price is a governed, audited, immediately-effective UI action (never a migration); quote returns correct ex/inc-VAT, volume break, and `adlp_category`; inter-entity rules layer-walled.

**M4 CRM + Order capture** (doc 04 §Orders/ADLP/Credit) — company/contact/deal/pipeline; order placement emitting `OrderPlaced`; **delivery schedules (tranches/call-off)**; **permission-gated pre-dispatch amendment**; ledger commitment consumer; audit projection.
- *Accept:* a 3-line compliant order places in <60s keyboard-only with correct ADLP pricing; an exception line holds the order `pending_ceo` (no allocation, no commission) until CEO approval; credit block fires per policy; **a 500-unit line scheduled as 2×250 on different dates creates two independently-fulfillable tranches**; **an authorised amend pre-dispatch re-prices/re-allocates and records `order_amendment`, while an amend after cutoff/dispatch is rejected (409)**; `OrderPlaced` fans out; audit reconstructs the order.

---

## Phase 2 — Trading, supply, traceability

**M5 Commission engine** — first-class **commission schemes** (basis = gross margin) with **validity windows** and **team / channel / country assignments** (wholesale ≠ retail, per-country variants); scheme resolution; real-time preview; accrue→post→claw via TigerBeetle two-phase; provisional `std_cost` margin at accrual, true-up to actual batch margin at posting.
- *Accept:* the correct scheme resolves by team+channel+country+date within its validity window (and a more specific assignment beats a general one); a wholesale agent and a retail agent on the same product get different commission; preview updates live as price changes; zero on unapproved exception; accrual posts on dispatch and trues up to actual batch margin; cancel claws back; statement reconciles to ledger.

**M6 Inventory + ATP/allocation + dispatch + carriers** — stock, movements, concurrency-safe allocation **per line or tranche**, serial capture, Rhenus/carrier adapter, serial→destination, OTD; partial/tranche dispatch + delivery.
- *Accept:* concurrent last-unit allocation never over-commits; serialised line can't dispatch without serials; dispatch decrements stock and records carrier/tracking/destination; **a scheduled tranche allocates/dispatches/delivers independently and invoices per drop**.

**M7 Batch / landed cost / serial genealogy** — `lot_batch` (per-lot **USD** SKU cost + freight + duty + FX, `fx_basis` spot/hedged), **specific-identification** costing (no weighted-average), serial lifecycle, generation handling.
- *Accept:* two lots of one SKU carry different landed costs (price, freight, FX all able to differ); each serial resolves its own lot cost into margin/inventory; **no averaging anywhere**; from a serial you get batch→order→customer→lifecycle; from a batch you get all serials/holders.

**M8 Activation ingest + warranty provision** (doc 04 §Serial, §Warranty) — Pulsar `athena-placement-versioned` consumer, first-write-wins, installer/owner enrichment; **warranty clock starts at activation**; per-unit provision register, straight-line release cycle, claims draw-down, consolidated exposure; **retroactive backfill from all historical activations**.
- *Accept:* first V3 placement creates one activation bound to installer+owner and flips the unit off-shelf; V2 ignored; re-placement doesn't double; redelivery idempotent; activation opens a warranty provision on the unit's specific batch cost; the nightly release advances outstanding; replaying historical activations reconstructs the current consolidated exposure; balance-sheet posting is emitted for the downstream consumer, not posted in Conduit.

**M9 Purchasing/receiving + supply planning + stock operations** — POs, GRN, landed-cost roll-up, backorder auto-allocation (per tranche), replenishment; **cycle counts, location transfers (in-transit), write-offs/damage/adjustments — all maker-checker with immutable logging + ledger write-downs at batch cost**.
- *Accept:* receiving lands cost, increments stock, auto-fills oldest backorders by requested date; a sustained activation run-rate change moves replenishment; **a cycle-count variance, a transfer, and a write-off each require a second approver (maker≠checker), post immutable movements, write the inventory value to the ledger at the units' batch cost, and are fully reconstructable**.

**M9b Returns / RMA (first-class)** — *see deep-dive doc 09 (planned).* Several return types (full unit, part-only/component, multi-unit, DOA, warranty replacement, goodwill) with lifecycle, disposition routing (restock/refurbish/scrap/return-to-supplier), serial lifecycle + genealogy, ledger reversal, commission claw, replacement-order issuance; maker-checker approval.
- *Accept:* a part-only and a full-unit return follow distinct flows; returned serials route to the correct disposition and never silently re-enter sellable stock; ledger reverses at the unit's batch cost and commission claws; a warranty replacement issues a new order and starts a fresh warranty.

**M10 Deal Desk + rebates; MRPeasy/Ghost Busters/Athena-catalogue migration + cutover.**
- *Accept:* exception workflow ends only at CEO memo (immutable); migration brings serial/activation/shipment/batch history with audited opening balances (`migration_record`), validated by a stock count at cutover.

---

## Phase 3 — Forecasting, intercompany, ERP-as-consumers, connect

**M11 H6Q** (doc 04 §H6Q) — **distributed weekly bottom-up capture**: owners forecast their own accounts (mobile/tablet/web, async across timezones), every estimate versioned per owner/account/cycle, **auto-rolled up** with no central re-keying; coverage/WoW/sell-through; **drill-down channel → sub-channel → segment → customer → branch + dual aggregation by branch and by sales agent**; outstanding-forecaster tracking; forecast-accuracy (estimate vs actual); new catalogue SKUs appear automatically; layered web/iPad board, export-only spreadsheet.
- *Accept:* opening a weekly cycle creates an outstanding submission per owned account and notifies owners; a rep submits per-account/SKU/scenario from any device and it rolls up bottom-up to channel/market and re-aggregates by agent (reconciling); revising an estimate keeps the prior (append-only history); "who still owes this week" is visible; coverage/WoW recompute live (no reconciliation); ex-Octopus/ex-Motability are toggles; a unit-only viewer sees no money; ship-not-activate shows overhang; accuracy scores an owner's past estimates against actuals.

**M12 Intercompany + transfer pricing + tax/customs + treasury hedges** — paired legs, cost-plus off batch cost, linked ledger transfers, import VAT/duty; **`fx_hedge` register (own admin permission), hedge-designated cost FX, consolidated USD reporting/translation**.
- *Accept:* a cross-entity move produces two reconciling legs, transfer price reproducible from policy+batch cost, import tax surfaced; a designated hedge sets a lot's cost FX to the contracted rate (audited `fx_basis='hedged'`) and draws down its notional; consolidated exposure translates to USD using the hedge register. (Year-1 mode: single UK ← Luxshare-UK hop; multi-tier Singapore hub is config, not code.)

**M13 ERP/GL & P&L consumers + Xero** — AR/AP/inventory projections off the ledger; **downstream P&L recognises matched revenue + COGS on delivery (ASC 606)** from `dispatch.delivered`, reclassifying `COS_CLEARING`→COGS; Xero invoice/tax feed (swappable); group presentation-currency (USD) reporting.
- *Accept:* every financial event posts to TigerBeetle and reaches Xero via the queue; the P&L consumer recognises revenue and COGS together on delivery using specific batch cost; swapping the accounting consumer needs no core change.

**M13b Period close, reconciliation & Auditability Center** (doc 14 §5–6) — close checklist + period **lock**; automated **reconciliations** (TB↔GL, GL↔Xero, inventory↔counts, AR↔invoices) with sign-off; **control register + run/evidence**; **lineage explorer** (figure → transfers → events → documents → replay); money-integrity + time/period (preview-reslice) panels; read-only **`auditor`** role/portal; evidence export.
- *Accept:* a month cannot lock with an open reconciliation exception; a reported revenue figure drills to its transfers→events→source documents and **re-derives by replay**; "preview reslice" shows exactly which transactions move period under a different reporting TZ *before* commit; a control's "re-perform now" runs its `evidence_query` and records a `control_run`; the auditor role can view and export everything financial but edit nothing.

**M14 Companion app (Flutter — mobile/tablet/web); Horizons feed; reporting/exports; HubSpot replication.**
- *Accept:* one **Flutter** codebase serves mobile, tablet and web; an owner submits weekly forecasts for their accounts and sees their own real-time commission, offline-tolerant across timezones (queued submissions re-validate server-side on sync); units→revenue→COGS→GP feed reaches Horizons; reports respect data layers; CRM/deals replicate out to HubSpot at the end of the Conduit flow.

---

## Decisions — resolved & still open

**Resolved:**
- **Commission** — first-class **schemes**, basis = **gross margin %**, with **validity windows** and **team/channel/country assignments** (wholesale ≠ retail; per-country variants). Exception treatment per scheme.
- **Athena/retail boundary** — Athena keeps web checkout/cart/Stripe and **feeds Conduit the completed retail sale**; Conduit handles the **inventory consumption + sale** (stock, ledger, genealogy, sell-through), not the checkout.
- **Backbone** — **Pulsar** + **Avro** registry, conforming to the platform pattern: **Nix/npins** builds, **Consul** discovery+config, **Terraform** infra (doc 01 §6.1).
- **Hyperview** — separate Prophet project; integrates as a retail **forecast source** into H6Q (Phase 3, M11).
- **Event-driven scope** — event-driven end-to-end with a complete, indefinitely-retained, replayable log (ERP attaches later as a consumer, backfilled by replay); state-stored aggregates in Postgres; event-sourcing reserved for the TigerBeetle ledger. Pure system-wide ES rejected (PII erasure, reporting, cross-aggregate concurrency) — doc 01 §3a. (was #4)
- **Luxshare billing currency = USD always**; FX into landed cost per designated rate (spot or hedged); hedge instruments are treasury/Phalanx scope but the applied rate is auditable (doc 04 §FX). (was #6)
- **Cost method = strict specific-identification batch landed cost; NO weighted-average** — each serial carries its lot's cost; lot cost can move SKU price + freight + duty + FX lot-to-lot (doc 02 §G, doc 04 §Ledger). (was #7)
- **COGS recognition = on delivery**, but **P&L construction is downstream**, not Conduit: Conduit relieves inventory into `COS_CLEARING` and emits `dispatch.delivered` with matched revenue + COGS amounts; the downstream P&L/GL recognises them (ASC 606) — doc 04 §Ledger. (was #9)
- **GAAP/SOX readiness is a design principle** (doc 01 §3b): immutable ledger, append-only audit, specific-identification costing, segregation of duties, reconstruction guarantee.
- **FX hedges** are first-class (`fx_hedge`: currency pair + validity window + contracted rate), under a dedicated **Treasury** admin permission; a designated hedge sets the FX applied to a lot's USD cost and drives consolidated reporting (doc 02 §A, 04 §FX, 05). *(Resolves the FX-on-cost basis: spot by default, hedged rate when a hedge is designated.)*
- **Warranty** is a first-class feature: clock starts at **activation**, per-unit provision register + straight-line release cycle + claims + consolidated exposure, fully rebuildable by retroactive activation ingest; balance-sheet posting downstream (doc 02 §G, 04 §Warranty).
- **Group presentation currency = USD** (confirmed).
- **Entities + tax registrations are customisable; procurement topology is config**: target = operating markets ← Singapore hub ← Luxshare; **year-1 "pre-global" = UK ← Luxshare-UK single hop**; switching is configuration, not migration (doc 02 §A).
- **H6Q hierarchy:** channel → sub-channel → segment → customer → branch, with dual aggregation **by branch and by sales agent** (doc 02 §C/§K, 04 §H6Q).
- **Batch-number scheme (default):** `<SUPPLIER>-<MRP_SKU>-<YYYYMM>-<seq>` (e.g. `LUX-hv-310-sg-t2-202607-014`), unique per supplier/variant/receipt-month; the literal is data, so a different rule is a config change.
- **Commission true-up (#17):** posted entries are **never reopened**; a periodic **true-up run (quarterly default, configurable)** books the delta on actual margins as a current-period adjustment. Commission computes **real-time** and shows in the **agent companion app** (own scope) on login (doc 02 §J, 04 §Commission).
- **Invoice = delivery, ASC 606 (#18):** the invoice is **auto-triggered by the delivery event**, so invoice/delivery never diverge and revenue + COGS recognise matched on delivery; Conduit enforces this (no invoice before control transfer) — doc 04 §Orders/§Ledger.
- **Warranty term (#8):** release runs over **activation + legal warranty (jurisdiction-specific, mandatory) + extension**; `legal_warranty` per jurisdiction/family + `warranty_extension`; release straight-line over the term by default (doc 02 §G, 04 §Warranty).
- **Entity/market seeding (#11):** one operating entity + tax registration + functional currency **per supported country**. The full set — **23 markets across NA, Europe, APAC + International, with currencies and locales — is seeded in doc 02 §A** (US/Canada tax and EU/non-EU/export routing flagged for the tax engine). Year-1 seed = UK only.
- **Localization:** the app + customer documents render per locale across **15 languages** (incl. CJK + Thai); reference data in doc 02 §A (`locale`/`currency`/`market`), i18n workstream in doc 10.
- **Channel taxonomy (#12):** from H6Q — `retail`, `installer`, `energy` (octopus, ovo), `distributor`, `automotive`; **runtime-extensible** by attributing accounts to channels in the UI (no code). *(MyEnergi removed — competitor.)*
- **Retail forecasting (#13):** retail is **per geography** with sub-divisions and **its own agents per operating market**; coverage by market/sub-channel/agent.
- **Seed roles (#14):** `retail_sales_agent`, `customer_service_agent`, `fulfilment_agent`, `tax_specialist`, `finance`, `admin` (+ required CEO approver, Treasury) — doc 05 §4.
- **HubSpot (#16):** Conduit is source of truth and **replicates CRM/deals out to HubSpot at the end of the flow**; HubSpot retained until Conduit is proven, then retired (doc 01).
- **H6Q is distributed bottom-up capture:** owners forecast **their own accounts weekly** via mobile/tablet/web, async across timezones; every estimate is versioned per owner/account/cycle, **auto-rolled up** into H6Q (no central re-keying); new catalogue SKUs appear automatically; forecast accuracy (estimate vs actual) tracked per owner (doc 02 §K, 04 §H6Q, 06).
- **Companion app = Flutter (#15):** one Flutter codebase for mobile + tablet + web — nothing else; offline-tolerant. Back-office desk stays React/TS (doc 01 §6).

**Still open (collect before the milestone; none change the architecture):**

| # | Decision | Needed by |
|---|---|---|
| 8b | Warranty release **curve** — straight-line (default) vs failure-rate curve; claim *workflow* depth (register+draw-down assumed) | M8 |
| 11b | The **exact country list** from the chooser → entity/currency/tax per country (year-1 only needs UK) | M1 |
| 12b | Final sub-channel + **segment list** to seed | M3 |
| 13b | Which **named accounts** get their own H6Q line (scenario cuts); Hyperview-vs-manual precedence default for retail | M11 |
| 14b | **Field→data-layer** membership map + exact layer defaults per seed role | M2 |

These are product/finance calls, not blockers to starting the spine.
