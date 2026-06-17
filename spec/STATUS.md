# Conduit — Build Status & Ignition Plan

_As of 2026-06-16. Assessed by reading the live code (310 Scala files, 84 migrations), the
testcontainers integration suites, and the running Postgres (`conduit-postgres`) row-by-row.
Companion to [`spec/07`](./07_BUILD_PLAN.md) (the acceptance source of truth) and the root
`CLAUDE.md` (the implementation contract)._

## TL;DR

**The code is built and CI-proven across essentially every milestone (M0–M13b + M-Pricing +
M13-Docs + M13-Tax). The live environment is _dormant_, not incomplete.** The historical data we
ingested is **trade substrate** (parties, orders, dispatches, serials, deals, exogenous series,
forecast runs) loaded by the forecasting `SnapshotLoader` — it has **never been driven through the
milestone engines**, so almost every milestone's own tables are empty in the running DB.

The remaining work is therefore mostly **ignition**: replay the real history through the engines
that already exist, in dependency order, so the live DB reflects reality across the board. Only a
short list of items are genuine net-new code. This _is_ the original goal — "the whole data import
engine, so Conduit can start with all the historical data."

> **No-fake-data rule still governs.** Most ignition steps complete state from real data (mark
> historical dispatches delivered, open warranty from real activations, recognise revenue on real
> order lines). A few steps (batch granularity, landed-cost components) have **no real source** and
> need a decision — flagged as ⚠️ DECISION below.

## Milestone status

| Milestone | Status | One-line |
|---|---|---|
| M0 Scaffold | ✅ DONE | Multi-module sbt, nix, compose (pg/pulsar/TB/consul/keycloak), CI, `/health`. |
| M1 Foundations | ✅ DONE | Typed Money, conserving allocate, outbox+relay, TB deterministic ids, period lock, TZ projection — all property/IT-tested. |
| M2 Access control | ✅ DONE | PolicyEngine + scope predicate + layer projection + FieldLayerMap (82 rows) + 11 preset roles, server-side. |
| M3 Catalogue + ADLP pricing | 🟢 SEEDED | Engine + `/pricing/quote` done & tested; **real price book now live** — 198 prices from the Comprehensive Pricing workbook → 22 `price_agreement`s (Retail open-list, Installers segment, 20 customer-set: Octopus/YESSS/CEF/Rexel/…) + 949 party links. Quote verified: retail HV3PRO £575, an OVO party £435.18 (its contract tier). Reproducible via `ingest/pricing/`. **Governance airtight** (doc 24 §3): order placement prices from the tier — a typed/non-tier price → `NonTierPrice` reject, unpriced SKU → `NoPrice`. **Pricing desk view** (Sell) surfaces the book grouped by agreement. CM cost tiers preset (Volex); legacy in-house mfg costs awaited; Luxshare future. IC transfer pricing built at recognition (`FlashTitle`→`ic_match`, procurement markup); auto transfer-doc/order is the post-2027 piece (dormant — no procurement entities yet). |
| M-Pricing (doc 24) | 🟡 PARTIAL | **Built, not "spec-only"** — `price_agreement`, tiers, retrospective ASC-606 rebate→ledger all coded & tested; tables empty live. |
| M4 CRM + Order capture | 🟡 PARTIAL | Order capture strong & IT-tested; **no contact/deal/pipeline API**. **Order→ledger commitment now wired + baseline rebuilt**: ignition replays the full order book as `order.placed` (49,948 events), `OrderCommitmentConsumer` records the sales backlog (ASC 606: no GL at placement), exposed at `/finance/backlog` + `/orders/{id}/commitment`. Baseline ties: **committed £60.55m = recognised £36.64m + open £23.91m**. Surfaced a real data anomaly for shadow validation (a £0-revenue/£238-COGS dispatch). |
| M5 Commission | 🟢 WIRED (dormant) | Accrue/post/claw + true-up TB-tested; now **wired**: `CommissionConsumer` (order.placed → provisional std-cost accrual, order-idempotent) + read routes (`/commission/statement`, `/entries`). Honestly dormant — `sales_agent`/`commission_scheme` = 0 (no real source), so it accrues 0 until real agents/schemes land. |
| M6 Inventory/ATP/dispatch | 🟢 WIRED | Concurrency-safe allocation + dispatch IT-tested; now **exposed**: `DispatchRoutes` (POST `/orders/{id}/dispatch`, POST `/dispatches/{id}/deliver` → recognition, GET `/inventory/availability` ATP), RBAC-gated (dispatch/stock_item). Carrier still a stored-field stub. |
| M7 Batch/landed-cost/genealogy | 🟠 SCAFFOLDED | Specific-id costing (no averages) coded & tested; **`lot_batch` = 0, serials unlinked** → can't resolve cost/genealogy live. |
| M8 Activation + warranty | 🟢 MOSTLY (real RMA data) | Warranty windows real on all 91,155 activated serials (activation + 36mo floor + 24mo if 5yr; corrected from the 24mo statutory). **Real HubSpot RMA tickets ingested** — pulled the RMA Pipeline (2732387) via the athena token: 1,953 tickets committed to `ingest/hubspot/rma_tickets.ndjson`; uses HubSpot's `rma_serial_number__s_n_` for the EXACT replacement serial (no inference). Materialises the units the MRPeasy ledger never had (2,301: 1,566 V2 + 735 V3, provenance `source='hubspot_rma'`), links the exact faulty→replacement genealogy, inherits the root warranty_end down chains (verified: replacement carries the original's end, never resets). **Real RMA stats** `GET /warranty/rma-stats`: 2,081 tickets, **245 V2→V3**, 381 V3→V3, 393 V2→V2, 1,189 faulty-V2 / 695 faulty-V3. `GET /serials/{id}/lifecycle` shows the family timeline + tickets (incl. real V2→V3 chains). **Honest gaps**: V2 warranty *windows* are NULL — V2 activation dates aren't in any ingested source; and the free-shipment↔RMA serial match is 0 (the £0 MRPeasy free-shipments and the HubSpot RMA replacements are disjoint channels — the real warranty replacements are the RMA tickets, not the free shipments). **Free-shipment classifier** (`free_shipment`): the COGS-without-revenue population is categorised distinctly — warranty_or_rma_replacement (2,655 / £1.21m), sample_converted (481 / £465k), sample_prospect (1,051 / £322k), r_and_d (22, Hypervolt/engineering — expensed to R&D not samples), marketplace_return (Amazon = returns, never samples). Derived from transparent source-backed rules (each row carries its basis), human-overridable, rebuilt 6h + on `/free-shipments/{summary,trend,warranty-metrics,rebuild,reclassify}`. **Warranty/RMA replacement rate = 17.99%** (avg £455) → the accrual basis for forward warranty liabilities. |
| M9 Purchasing/receiving + stock ops | 🟢 WIRED | Receiving, landed cost, maker-checker stock ops + TB write-down tested; now **exposed**: `PurchasingRoutes` (POST `/purchasing/orders`, `/lines`, `/receive` → lands cost, mints serials), RBAC-gated to procurement/admin (profitability layer). Stock-ops maker-checker route still to surface. |
| M9b Returns / RMA | 🟡 PARTIAL | Most complete supply-side: routes + consumer wired, spec/09 written; awaits live returns + batch linkage. |
| M10 Deal Desk + migration/cutover | 🟡 PARTIAL | Deal Desk (maker-checker ADLP) done & wired; migration has no entry point (test-only). **Shadow-validation harness now wired** (doc 33 §5): 5-check discrepancy battery → `shadow_finding` triage queue, idempotent re-runs (human triage preserved, auto-resolve on clear), `/shadow/{validate,summary,findings,findings/{id}/triage}`, scheduled 6h in consumer. Live triage queue + desk view (Govern). After the price-loss remediation + grading fix: **46 high** (45 uncosted older-SKU variants + 1 £3.20 over-recognition), **627 medium** (header-vs-line gap), **4,639 low** (free units, £0 variance). The "price-lost" cluster resolved: only **9 orders were truly unpriced** (import dropped all line prices → restored from header → +£630 recognised, re-recognised idempotently); the rest were free £0 lines within PAID orders (header belongs to the priced line, already recognised — no money missing), now graded low. All ties held (trial balance, AR↔invoices £43.97m). |
| M11 H6Q | 🟡 PARTIAL | Forecast engine + coverage + full API done, 1.7M predictions populated; **bottom-up cycle/submission spine empty** (no cycle opened). |
| M12 Intercompany/TP/tax/hedges | 🟡 PARTIAL | Full logic + routes + suites; dormant (0 rows) — gated on revenue cascade. |
| M12-Treasury FX hedging program | 🟢 MOSTLY DONE | Provider-agnostic schema + Ebury adapter + program service (required-notional, coverage, propose→sign→execute); **real Ebury position seeded** (facility/policy/Contract-3/continuation/approvals); **cost master** (real Volex USD bands); **exposure forecast** (forecast×cost, continuation sized £7.5m); **effectiveness stream** (hedged vs counterfactual all-spot — −£92k cost / ~67% vol cut); **/treasury routes + FX-Hedging desk**. Remaining: hedged-COGS *ledger posting* (plugs into A3/B2 COGS ignition via `HedgeMath.effectiveRate`); margin-call monitor; maturity-extension UI. |
| /activations desk (ghost-busters port) | 🟢 DONE — full replica | Capacity (connected-MW + forecast + V2G + DC comparison, net-new vs GB) · Live feed (real-time **SSE**, ● live, new-glow, **day navigation** prev/next/go-live, serial→account deep-link ↗) · Analytics (Day/Week/Month/Quarter bucket toggle, day-of-week average, frequency KPI). Backend: feed + capacity + series + kpis + SSE stream + by-date. Every GB feature ported + a Capacity story GB never had. |
| M13 ERP/GL + P&L + Xero | 🟡 PARTIAL | ASC-606 revenue→GL, P&L, real Xero connector; dormant — **0 dispatches delivered** so nothing posted. |
| M13b Period close + recon + audit | 🟡 PARTIAL | Close/lock SoD, 5 reconciliations, Proof Center, lineage; **no period even opened** live. |
| M13-Docs | 🟡 PARTIAL | Real Apache-FOP PDF engine + WORM + gapless numbering; 0 documents generated live. |
| M13-Tax (doc 16) | 🟡 PARTIAL | Effective-dated rate-table engine done & tested; **external vendor adapter is a seam only**; 0 quotes live. |
| M14 Companion + Horizons + HubSpot-out | 🔴 MISSING | No Flutter app, no Horizons feed, HubSpot is inbound-ingest only. |

## Ignition plan (dependency-ordered)

### ✅ Phase A IGNITED (2026-06-16) — the P&L is live on real history
- **A1 done**: 39 monthly accounting periods open (2023-10 → 2026-12).
- **B2 done**: 9 Volex opening lots, **79,044 serials costed (75% HV3PRO fleet)**, specific-id landed cost (real USD @ ECB + £8); lights up M7 genealogy. 25% older SKUs honestly uncosted.
- **A3 done**: **19,406 dispatches recognised** through the production pipeline (emit `dispatch.created` → relay → `RevenueRecognitionConsumer` → TigerBeetle). **Trial balance exact (debits = credits)**. P&L: **revenue £36.6M ex-VAT · VAT £7.3M (engine 20%) · COGS £19.3M · gross margin £17.4M (47%)**. Lights up M13 (revenue/GL/P&L) on real data; hedged-COGS hook (`HedgeMath.effectiveRate`) ready.
- **A4 done**: per-period P&L live on `/finance/pnl` (recognition date = dispatch date, so revenue re-projects to the real fiscal period — 2025-10: rev £2.66m, VAT £532k, COGS £1.31m, 51% GM). Close drill run on 2025-10: **TB↔GL reconciliation ties EXACTLY (£63.25m)**, finance closed the period, admin's lock correctly **blocked by 2 unsigned reconciliation exceptions** (SoD + lock-gate both proven). Seeded demo-finance + demo-admin users (finance/admin roles) — CEO holds neither edit:accounting_period nor edit:reconciliation (correct SoD).
- **CLEAN LOCK achieved (2025-10 LOCKED)** — and now **fully replayable from a clean `down -v` boot** (no hand-seeding):
  - **Inventory↔count MATCHES** (£0.00): ignition posts the opening INV (DR INV / CR opening-equity) via `OpeningInventoryConsumer` on a per-dispatch COGS basis, so INV nets exactly to physical (fleet fully sold-through). No longer a sign-off.
  - **AR↔invoices MATCHES** (£43,969,414.56): recognition auto-issues one backfill `order_invoice` per historical dispatch (14,758, keyed by `dispatch_id`, idempotent) at the exact AR figures — no manual issue step.
  - **TB↔GL MATCHES** (£82.53m debits = credits this period).
  - **gl↔Xero** signed off (genuinely no Xero connected — honest exception, not hidden).
  - SoD principals (`demo-finance`/`demo-admin`/`demo-ceo`) seeded by migration. Finance closed → admin locked, closer≠locker enforced. The whole auditable close→lock cycle re-derives on every boot.
- **Still deferred** (real, control-caught gaps): (1) issue invoices for the recognised AR → clears AR↔invoices (M13-Docs / C5); (2) post opening inventory balances when minting lots → clears inventory↔count (`SyntheticOpeningLots` opening-INV transfer). Plus: recognise the 25% uncosted dispatches once their cost lands; make the whole ignition reproducible at boot (emit `dispatch.created` from the MRP ingest + opening-INV from lot creation) — currently lots/periods/recognition live in the running DB, not yet on a clean-boot path.

### ✅ Boot recovery + AWS portability (2026-06-16)
The whole ignition is now **reproducible from a clean boot** — verified by a full `docker compose down -v` → fresh boot reconverging to the **identical** state (106,154 serials / 79,044 costed, 39 periods, balanced ledger 6,324,802,735, P&L £36.6m rev / £17.4m margin / 47%, 32 P&L months) with **no manual steps**:
1. **Flyway** migrations — schema + reference seeds (operating entity, Ebury facility/policy/contracts/approvals, providers).
2. **SnapshotLoader** at boot — loads `ingest/` (261k rows: parties/orders/dispatches/serials/activations/deals/exogenous/**cost**/**fx**/h6q).
3. **IgnitionService** at boot (idempotent, `IGNITE=false` to disable) — Volex supplier, stamp operating entity onto orders, mint costed opening lots + link serials, open periods, stock-item on-hand balances, build the exposure forecast, **emit `dispatch.created` events** → relay → `RevenueRecognitionConsumer` → TigerBeetle (recognition converges async, ~4.5 min), and **emit `inventory.opening`** → `OpeningInventoryConsumer` posts DR INV / CR opening-equity at the per-dispatch-basis total. `recognized_at = dispatch date` ⇒ the P&L re-projects to the right fiscal period automatically.
   - **Inventory now ties to the penny** (verified after a clean `down -v`): opening INV £19,278,612.79 = COGS relieved £19,278,612.79 ⇒ **INV net £0.00 = physical £0** (the fleet is fully sold-through; on-hand 0). The inventory↔count reconciliation **matches** (no sign-off) and is auto-replayable.
- **AWS-portable**: the Dockerfile now `COPY`s `ingest/` into the image (`INGEST_DIR=/app/ingest`), so a fresh deploy self-seeds + self-ignites with no external fetch. A fresh AWS env (RDS + the api & consumer images + TigerBeetle) boots into this exact state. For a point-in-time transfer instead, `pg_dump`/restore is always available.
- **Not auto-replayed (by design — operational, not seed state)**: the period close/lock and the AR-tie invoices. An operator re-runs the close on demand; the recognised P&L + balanced ledger recover automatically.

### Phase A — light up the financial spine with real history _(real data only)_
- **A1. Open accounting periods** for the trade history range (monthly, 2023→2026); past closeable, current open. Prereq for all posting. (`accounting_period` = 0 today.)
- **A2. Activation + warranty backfill (M8):** drive `ActivationService` from the 91,155 real `serial_unit.activated_at`; open warranty provisions. Fix `WarrantyService.backfill` to read `serial_unit.activated_at` (not the empty `activation` table) and invoke it from a boot step / script. ⚠️ DECISION: warranty/COGS cost basis until B2 — use real MRP `std_cost` as interim unit cost.
- **A3. Mark historical dispatches `delivered`** (they are real, the units are activated in the field) → fires `RevenueRecognitionService` for the historical book → AR/Revenue/VAT/COGS to TB + `gl_entry`.
- **A4. GL/P&L + close drill:** run GL projection + P&L for a historical period; open→close→lock a past period; run the five reconciliations + a lineage trace. (M13/M13b)

### Phase B — supply-side reality _(needs a data decision)_
- **B1. ⚠️ DECISION — cost/batch basis:** do we have real landed-cost source data (Luxshare invoices, freight, duty)? If yes, ingest it. If not, seed **opening lots** from MRP `std_cost` (standard migration practice, labelled as opening balances — not fabricated trade).
- **B2. M10 migration ignition:** opening `lot_batch` + serial→batch linkage + landed cost via the existing `MigrationService`/`SyntheticOpeningLots`; give it a CLI/route entry point. Unlocks specific-id COGS, genealogy, stock positions, warranty at true batch cost (re-cost A2/A3 if B1 yields real costs).
- **B3. Suppliers/POs/stock:** ingest real purchasing history if it exists; otherwise leave honest-empty (the screens already degrade cleanly).

### Phase C — flows, wiring & remaining backfills
- **C1.** Wire the **ledger-commitment consumer** for `order.placed` (M4 gap).
- **C2.** Wire **M6/M9 HTTP routes + consumers** (inventory/dispatch/purchasing/stock-ops) — currently test-only.
- **C3.** **Commission** (M5): wire accrual to order/revenue events + backfill the historical book.
- **C4.** **Pricing** (M3/M-Pricing): seed `price_rule`/`price_agreement` from real contract/price data (or wrap `order_line` actuals) so live quoting works.
- **C5.** **Documents** (M13-Docs): generate invoices/credit-notes for the historical invoiced book.
- **C6.** **H6Q** (M11): open a forecast cycle to exercise the bottom-up submission spine.
- **C7.** Ingest provenance: write `ingest_record` per load (or drop the orphan `ingest_record`/`sync_state` tables that have no DDL/writer).

### Phase D — net-new build (Phase-3 tail)
- **D1.** M13-Tax external vendor adapter (Avalara/TaxJar) — only if a routed vendor is required; the seam exists.
- **D2.** M14 — Flutter companion app (estate `~/projects/hypervolt/ux`, `hypervolt_ui_kit`, Conduit purple), Horizons feed, HubSpot **outbound** replication.

### Hygiene
- Remove the stale duplicate `conduit/` subdirectory (a 240-file/61-migration snapshot shadowing the live root tree).

## Genuine code gaps (vs. pure data backfill)
1. M8 `WarrantyService.backfill` reads empty `activation`; never invoked. (A2)
2. M4 ledger-commitment consumer for `order.placed` absent. (C1)
3. M6/M9 services have no route/consumer wiring (test-only). (C2)
4. M6 carrier integration is a stored-field stub, not a live Rhenus adapter.
5. M5 commission has no route/consumer. (C3)
6. M10 migration has no CLI/HTTP entry point. (B2)
7. M13-Tax external vendor adapter unwritten (seam only). (D1)
8. M14 companion app / Horizons / HubSpot-outbound unstarted. (D2)
9. `ingest_record`/`sync_state` orphan tables — no DDL, no writer. (C7)
