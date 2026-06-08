# 15 — Delivery Milestones (the build register)

The single source of truth for **what is built, what is next, and the exact verifiable steps** for every
remaining milestone. Pairs with `07_BUILD_PLAN.md` (the original phasing + per-feature acceptance) — this
doc is the *living* register: status, dependencies, the backing spec doc, the sub-steps, and the
verification gate for each. A milestone is **done only when its acceptance tests pass** (unit + integration,
and Playwright/e2e for UI).

**Legend:** ✅ done · ◐ partial · ⬜ not started · 🔒 blocked-on-dependency.

---

## 1. Current state (snapshot)

Implemented and verified end-to-end: **Phases 1 & 2 complete (M0–M10 incl. M9b), plus M11 + M12, and M13 ~70%.**
All milestone suites are green (weaver unit/property + integration against real Postgres + TigerBeetle via
testcontainers + Playwright e2e for the desk). Backend conforms to the Athena house stack (Scala 2.13 /
http4s / tapir / doobie / Pulsar+avro4s / TigerBeetle / Keycloak / Nix / Consul). The React+StyleX desk covers
order / deal-desk / H6Q (demand matrix + flow + supply + shelf); a `consumer` process runs the outbox relay +
Xero invoice + revenue-recognition consumers. Pushed to `github/main`.

> **Last updated through M13: Xero + dispatch-invoice + credit terms + recognition/P&L + GL + document-gen core.**
> Update this register as each milestone lands.

| | Milestone | Status | Backing docs | Tests |
|---|---|---|---|---|
| — | **M0** Scaffold + dev env | ✅ | `CLAUDE.md`, 01 | compile + boot + /health |
| P1 | **M1** Foundations (Money/period/outbox/Avro/TigerBeetle) | ✅ | 01, 02§L, 03, 04§Ledger, 14 | ✓ |
| P1 | **M2** Access control (RBAC/scope/data-layer) | ✅ | 02§B, 05 | ✓ |
| P1 | **M3** Catalogue + ADLP pricing + `/pricing/quote` | ✅ | 02§D/§E, 04§Pricing/§ADLP | ✓ |
| P1 | **M4** CRM(parties) + Order capture | ✅ | 02§C/§F, 04§Orders/§Credit, **11** | ✓ + e2e |
| P2 | **M5** Commission engine | ✅ | 02§J, 04§Commission | ✓ |
| P2 | **M6** Inventory + ATP/allocation + dispatch + carriers | ✅ | 02§F/§G, 04§ATP | ✓ |
| P2 | **M7** Batch / landed cost / serial genealogy | ✅ | 02§G, 04§Ledger/§FX | ✓ |
| P2 | **M8** Activation ingest + warranty provision | ✅ | 02§G, 04§Serial/§Warranty | ✓ |
| P2 | **M9** Purchasing/receiving + supply + stock ops | ✅ | 02§H, 04§Stock ops | ✓ |
| P2 | **M9b** Returns / RMA | ✅ | **09** | ✓ |
| P2 | **M10** Deal Desk + rebates + migration/cutover | ✅ | 04§ADLP, **18** | ✓ |
| P3 | **M11** H6Q forecasting (matrix/waterfall/supply/shelf/desk/local) | ✅ | 02§K, 04§H6Q, **12**, 08 | ✓ + e2e |
| P3 | **M12** Intercompany + transfer pricing + tax/customs + hedges | ✅ | 02§A/§I, **13**, **16** | ✓ |
| P3 | **M13** ERP/GL + P&L + Xero + documents + **tax/customs engine** | ◐ | 04§Ledger, **16**, **17** | ✓ (see below) |
| P3 | **M13b** Period close + reconciliation + Auditability Center | ◐ | 14§5–6, **20** | ✓ (core) |
| P3 | **M14** Companion app + desk + Horizons + reporting + HubSpot | ◐ | 08, **20**, **21** | desk (no companion) |
| X | **NFR / Security / Ops-DR** (cross-cutting, P1 launch-blocker) | ⬜ | **19** | — |

**M13 sub-status** (◐ ~99% — invalidation, Order Collection Ledger, extra document types and the **real tax/customs engine** complete; tail = CJK/Thai font embedding + the sales-VAT-on-dispatch final-quote consumer):
- ✅ Xero feed — swappable `AccountingConsumer`, ported from Athena (OAuth2 + PUT /Invoices, idempotent, local no-op); `order.invoiced` consumer.
- ✅ Invoice on **dispatch** (ASC 606) + per-contact **credit terms → due date → cash waterfall**.
- ✅ **Revenue + COGS recognition at dispatch** (consumer) + **P&L read-model** (`/finance/pnl`) — proved on the ledger.
- ✅ **Payments / cash application** (`PaymentService`) — settles AR on the ledger (DR cash/clearing, CR AR), allocates to invoice(s), open→part_paid→paid, idempotent on source ref; Stripe payout relieves the clearing into bank net of fees. AR-aging + DSO analytics (`/finance/ar-aging`).
- ✅ **Stripe webhook ingestion** (ported from Athena) — public `POST /api/v1/stripe/webhook` verifies the signature (Stripe SDK HMAC), records the event idempotently (`stripe_event`, no TB in the API), and a consumer drain (`StripeInboundProcessor`) settles it via `PaymentService`. `StripeWebhook` parser (charge → settle AR, `payout.paid` → relieve clearing net of fees); `StripeWebhookSpec`, `StripePaymentSuite`, `StripeWebhookRouteSuite` green.
- ✅ **GL/AR trial balance off the ledger** (`GlProjectionService`, ties out Σdr==Σcr). *Route pending TB-in-API or a `gl_entry` Postgres projection.*
- ✅ **Document generation** (doc 17) — gapless/immutable numbering (`document_number_series/number`, void consumes a number, no reuse/gap), the `invoice` as a rendered projection of truth (totals read off `order_invoice` + conservation guard), template fallback resolution, WORM `document` row + `document.issued`. **Real PDF engine: Apache FOP** (XSL-FO → PDF, NOT PDFBox), byte-deterministic so the sha re-performability control holds (`FopDocumentRendererSpec`); **object store: S3** (`S3DocumentStorage`, object-lock+versioning WORM bucket; LocalStack in dev/CI — `S3DocumentStorageSuite`). **Auto-generated on `order.invoiced`** (`DocumentGenerationConsumer`); **`/documents` REST** (list/metadata/PDF download, layer-projected — `DocumentHttpSuite`); **Documents desk tab** (list + download + invalidate; Playwright + Chrome verified). *Pending: proforma/commercial-invoice/statement types + per-locale CJK font templates.*
- ✅ **Invoice invalidation** (doc 13 §void, ASC 606) — immutable reversal: negates recognition on TigerBeetle (AR/Revenue/VAT/COGS/INV net to zero, `InvoiceReversalSuite`), flips the invoice → `void` + `invoice_reversal` fact, mints a **credit note** correcting the original (original PDF kept WORM + badged), drops it from AR-aging/waterfall, voids it in **Xero**, and a **refund** returns the cash. `POST /api/v1/invoices/{no}/void` (edit:order; refund needs approve:order) emits `invoice.void_requested` → consumer performs it (no TB in API). Mistakes/cancellations/refunds/corrections; pre-dispatch change = the amend flow.
- ✅ **Order Collection Ledger** (the perfect log) — the order is the root; the back-and-forth (invoice→collect→void→refund→re-invoice) is a **projection over the immutable event stream**. `OrderLifecycleRepo` = flat event timeline (from the append-only outbox) + per-invoice collection cycles (status/void/replaced-by/credit-note + recognised/paid/refunded/outstanding). `GET /api/v1/orders/{id}/lifecycle` (view-gated, money layer-walled) + **Lifecycle desk tab**. **Causal threading**: the void→credit-note→refund cycle shares one `correlation_id` (= reversal id) with `causation_id` back to the void request — recorded on the events, not reassembled (`OrderLifecycleSuite`, `OrderLifecycleHttpSuite`, `InvoiceVoidCorrelationSuite`; Playwright + Chrome).
- ✅ **More legal document types** (doc 17 §5) — proforma, packing list (volume-only), commercial invoice (HS/origin/incoterms + customs value), customer statement — through a generalized FOP renderer + shared `finaliseDoc`, all gapless/WORM/idempotent (`DocumentTypesSuite`).
- ✅ **Sales VAT + per-jurisdiction exposure on the immutable ledger** (M13-VAT, doc 16 §1.3 / doc 04 §Ledger). VAT is now **determined by the engine at the recognition point** (`context=invoice`, tied to the immutable `tax_quote`), not pricing's per-line amount, and **attributed to the place-of-supply jurisdiction**. A configurable, effective-dated **`selling_entity` map** (jurisdiction → active entity, maker-checker) resolves the seller-of-record — so a sale books against the right Hypervolt entity in each jurisdiction (re-point DE: HV-UK→HV-GmbH by config), each entity booking in its own functional currency. **Per-jurisdiction VAT exposure** (accrued − reversed − remitted = outstanding) is a reproducible projection over immutable rows, and a **remittance** depletes it on the ledger (DR `VAT:<entity>` / CR `BANK:<entity>`, API→`tax.vat.remit_requested`→consumer, deterministic id); `VatRemittanceService.reconcile` proves the projection ties to the `VAT:<entity>` balance. **Per-event reversal**: a cancellation recalls revenue, VAT, COGS **and** outbound carriage (a new first-class ledger leg) — proving the model isn't "a ledger per cost category"; a new cost is an account code + a recorded leg, reversed with no change to the void logic. Controls: VAT-conservation, no-over-remit. Suites: `SellingEntityHttpSuite`, `CarriageReversalSuite`, `VatRemittanceSuite` + recognition/reversal/PnL/GL all green; **Tax desk** gains the entity map + VAT exposure/remittance board (Playwright + Chrome). *Year-1 UK: VAT account stays entity-level (one jurisdiction per entity), so the projection and the ledger coincide.*
- ✅ **Real tax/customs engine** (doc 16) — replaces the stub. Tax is a **quote, not a rate column**: the regime catalogue is split from **effective-dated `tax_rate` rows** (a change is a new dated row, never an edit) that are **multi-level** (US state+county+district, CA GST+PST) and **postcode-prefix** aware — one rate row = one component. Every amount is computed via the **`Money` library + `RoundingPolicy`** (no float; `tax` in the no-float lint), with line-vs-invoice conserving rounding (largest-remainder). `TaxClassifier` resolves place-of-supply (domestic / intra-EU reverse-charge / B2C / export / import incl. CH/NO / US destination / CA federal+provincial). `TaxDeterminationService` resolves the provider from **`tax_routing`** (pluggable `TaxProvider` port; rate-table default, external vendor a drop-in), persists the **immutable reproducible `tax_quote`** (request/response snapshots, superseded never deleted), advances **US/CA nexus** with threshold events, and emits `tax.quoted`. Real engine wired behind the doc-13 §6 intercompany import boundary (replaces `StubTaxEngine`). **REST** (`/tax/quote`, `/tax/quotes`, `/tax/regimes|rates|routing|registrations|nexus`) layer-projected + **maker-checker** rate governance (tax_specialist proposes → CFO activates, effective-date supersession). **Tax desk tab** (quote tester + rate-table admin + nexus board; Playwright + Chrome verified). **ICFR controls** (VAT conservation, reproducibility evidence, external-evidence retention, nexus gating) re-performed by the `ControlRunner`. `TaxClassifierSpec`/`TaxComputeSpec`/`TaxEngineSuite`/`TaxDeterminationSuite`/`TaxHttpSuite`/`TaxControlsSuite` green. *Remaining integration: the sales-VAT-on-`dispatch.delivered` final-quote consumer (today sales VAT is pricing-owned via `price_rule.taxRatePct`); US/CA route to rate-table for year-1 demonstrability — flipping to an Avalara/TaxJar/Stripe-Tax adapter is a `tax_routing` row, and nexus **gating** activates with that external path (§4.4b), while nexus tracking/alerting is live now.*

**M13b sub-status** (◐ core done):
- ✅ **Period close + lock** (`PeriodCloseService`) — open→closed→lock; lock gated on no unsigned reconciliation exceptions / no pending close tasks; segregation of duties (closer ≠ locker); posting into a locked period barred.
- ✅ **Reconciliation engine** (`ReconciliationService`) — AR↔invoices + TB↔GL tie-outs off the ledger, expected/actual/variance/status, sign-off; an unsigned exception blocks the lock.
- ✅ **Control runner** (`ControlRunner`) — re-performable `evidence_query` → pass/fail + `control_run`; seeded CTRL-DOC-GAPLESS / CTRL-RECON-EXCEPTIONS / CTRL-INV-CONSERVATION.
- ✅ **Auditability lineage** (`LineageService`) — figure → order_invoice → ledger transfer ids → events → issued PDF.
- ✅ **Auditability Center desk tab** (`AuditRoutes` + `Auditability.tsx`) — close board (close/lock), SOX control register with re-performable runs, lineage explorer; Playwright + headless-Chrome verified.
- ⬜ Remaining reconciliations (GL↔Xero, inventory↔counts), reconciliation-run REST (needs TB-in-API/consumer), evidence export.

> Every backing doc exists (deep-dives 09/11/12/13 + launch-blockers 16–21). No "unwritten spec" blockers —
> only implementation. Open: companion-app Flutter-vs-React (M14). **Finance desk tab built** (P&L + cash
> waterfall + credit-terms admin; Playwright + headless-Chrome verified); the Auditability Center UI (GL /
> close board / reconciliation / lineage) is still API-ready-but-unbuilt.

---

## 2. Carry-over residuals (close these as their enabling milestone lands)

- **M4-R — CRM depth** (doc 11): deals/pipelines/stages, deal→order conversion, account-history projection,
  ownership, party merge/dedupe, promote-to-billable policy, consignment-at-branch. *Built so far:* parties +
  billing/credit + orders + tranches + amendments + ADLP exceptions. **Lands with M11** (deals feed H6Q) and a
  CRM pass.
- **M5-R — Commission true-up + lifecycle wiring** (doc 04§Commission): accrual currently uses `std_cost`
  margin (the provisional basis). **True-up to actual batch landed cost needs M7**; **auto accrue-on-dispatch
  / claw-on-cancel needs M6**. The engine + two-phase lifecycle are in place; only the event wiring + true-up
  run remain.

---

## 3. Remaining milestones — verifiable step breakdown

Each milestone lists **depends-on**, **sub-steps** (each independently testable), and the **acceptance gate**
(from `07`/the backing doc). Build sub-steps in order; verify each before the next. Pure logic → ScalaCheck/
weaver unit tests; persistence/eventing/ledger → `api-it` testcontainer integration; UI → Vitest + Playwright.

### M6 — Inventory + ATP/allocation + dispatch + carriers  · depends: M4 (orders/tranches), M1 (events)
1. **Stock model** — `stock_item`, `stock_movement` (append-only, on-hand = Σ movements); migration + repo. *Verify:* on-hand reconstructs from movements.
2. **ATP + concurrency-safe allocation** — `allocate(line|tranche)` with `SELECT … FOR UPDATE` row-lock + serial `SKIP LOCKED` (04 §ATP). *Verify:* N parallel allocators on 1 unit → exactly one wins (race test).
3. **Serial capture + dispatch** — `dispatch`/`dispatch_line`, `CarrierAdapter` (Rhenus/DPD/UPS stub), serial→destination, OTD. *Verify:* serialised line can't dispatch without serials (422); dispatch decrements stock, records carrier/tracking.
4. **Per-tranche fulfilment** — allocate/dispatch/deliver per `delivery_tranche`, independently. *Verify:* a 2×250 order's tranches allocate/dispatch/deliver/invoice independently.
5. **Wire M5-R** — emit `dispatch.delivered`; commission accrual posts on dispatch; cancel claws. *Verify:* accrual posts on dispatch, void on cancel (extends M5 suite).
- **Gate (07 M6):** concurrent last-unit allocation never over-commits; serial-gated dispatch; per-tranche independence.

### M7 — Batch / landed cost / serial genealogy  · depends: M6 (serials/stock)
1. **`lot_batch`** — per-lot USD cost + freight + duty + FX (`fx_basis` spot/hedged), `landed_unit_cost` derivation (04 §FX). *Verify:* two lots of one SKU carry different landed costs (price/freight/FX differ).
2. **Specific-identification costing** — each `serial_unit.lot_batch_id` resolves its own cost into margin/inventory/COGS; **no weighted-average anywhere**. *Verify:* serial resolves its lot cost; CI/test asserts no averaging.
3. **Genealogy** — `unit_lifecycle_event` append-only; serial→batch→order→customer and batch→all-serials. *Verify:* both genealogy directions resolve.
4. **Close M5-R true-up** — true-up run recomputes commission on actual batch margin (04 §Commission). *Verify:* posted accrual trues up to actual batch margin delta.
- **Gate (07 M7):** different landed costs per lot; specific-id everywhere; genealogy both directions.

### M8 — Activation ingest + warranty provision  · depends: M7 (batch cost basis)
1. **Activation consumer** — Pulsar `athena-placement-versioned` (record `AthenaPlacementVersionedRecord` per CLAUDE.md), first-write-wins on serial, V2 ignored, idempotent on redelivery (04 §Serial). *Verify:* first V3 binds installer+owner + off-shelf; V2 ignored; redelivery no double.
2. **Warranty provision** — clock starts at activation; `warranty_provision` at the unit's specific batch cost; `legal_warranty` + `warranty_extension` term; straight-line release; consolidated exposure (04 §Warranty). *Verify:* activation opens provision; nightly release advances; consolidated exposure sums.
3. **Retroactive backfill** — replay all historical activations → reconstruct current exposure. *Verify:* replay reconstructs exposure to today.
- **Gate (07 M8):** first-write-wins + idempotent; provision at batch cost; balance-sheet posting emitted downstream; replay reconstructs.

### M9 — Purchasing/receiving + supply planning + stock operations  · depends: M6, M7
1. **PO → GRN → landed cost** — `purchase_order`/`po_line`/`goods_receipt`/`landed_cost_component`; receiving lands cost + increments stock + creates `lot_batch`. *Verify:* receiving lands cost, auto-allocates oldest backorders by requested date.
2. **Replenishment** — `replenishment_suggestion` from run-rate/net requirement. *Verify:* sustained activation run-rate change moves replenishment.
3. **Stock ops (maker-checker)** — cycle counts, transfers (in-transit), write-offs/damage/adjustments; all maker≠checker, immutable movements, ledger write-down at batch cost (04 §Stock ops). *Verify:* count variance / transfer / write-off each need a 2nd approver, post immutable movements + ledger value, fully reconstructable.
- **Gate (07 M9):** backorder auto-fill; maker-checker on every stock op + ledger write-down + reconstruction.

### M9b — Returns / RMA  · depends: M6, M7, M8; **doc 09**
1. **RMA model + types** — extend `rma` stub per doc 09 (full unit / part-only / multi-unit / DOA / warranty replacement / goodwill). 2. **Lifecycle state machine** raise→assess→approve→receive→disposition→refund/replace→close (maker-checker). 3. **Disposition routing** restock/refurbish/scrap/return-to-supplier (serials never silently re-enter sellable stock). 4. **Ledger reversal** at specific batch cost + VAT reversal + **commission claw**. 5. **Replacement order** issues a new order + fresh warranty.
- **Gate (07 M9b / doc 09):** part-only vs full-unit distinct flows; correct disposition; ledger reverses at batch cost + commission claws; warranty replacement starts fresh warranty. *(15 acceptance points in doc 09.)*

### M10 — Deal Desk + rebates; migration & cutover  · depends: M1–M9; **doc 18**
1. **Deal Desk + rebates** — exception workflow ends at an immutable CEO memo; `rebate` budgets. *Verify:* exception ends only at CEO memo (immutable).
2. **Migration** (doc 18, the biggest go-live risk) — source→target mapping (MRPeasy/Ghost Busters/Athena), opening balances into TigerBeetle (specific batch cost), idempotent replay-path backfill, dual-run reconciliation, cutover validation (physical count ties to the penny), rollback. *Verify:* migration brings serial/activation/shipment/batch history with audited opening balances, validated by a stock count at cutover. ✅ **Built (part 2):** `migration_record`/`migration_batch` (V1_0_14, full provenance spine); `MigIds` (deterministic conduit/event/transfer ids + source-hash drift detection); `MigrationService.backfill` (atomic business-row + outbox + provenance, idempotent at 3 layers); `SyntheticOpeningLots` (weighted-average → specific-ID to the penny via largest-remainder, property-tested); opening balances post against `OPENING_BALANCE_EQUITY` (trial balance nets to zero); `reconcile`/`cutoverStockValidation` (zero-tolerance, writes `reconciliation`). Tests: `SyntheticOpeningLotsSpec` (property) + `MigrationCutoverSuite` (5 integration: idempotent backfill no-op, opening value ties to penny, opening-transfer replay safe, dual-run match/exception, cutover units+value tie).
- **Gate (07 M10 / doc 18):** the phased runbook G1–G6 gates pass; reconciliations zero before cutover.

### M11 — H6Q forecasting  · depends: M4-R (deals), M6/M8 (actuals); **doc 12** · ✅ **core shipped**
1. **Cycle engine** — weekly open/close, outstanding-submission per owned account, owner notifications (tz-agnostic). 2. **Versioned submissions** — append-only, superseded_by, full time-series. 3. **Bottom-up rollup** — account→…→market + dual aggregation by branch and by agent (reconciling). 4. **Scenarios + Hyperview** — P20/P50/P80, ex-account cuts, `source='hyperview'` precedence. 5. **Accuracy** — error/bias/MAPE vs actuals. 6. **Board** — layer-aware drill-down + export.
- ✅ **Built:** V1_0_15 (forecast_scenario P20/P50/P80 + ex-cuts, forecast_cycle one-open-per-cadence, forecast_submission, append-only forecast_entry, pipeline_coverage per-level + per-agent, sell_through, forecast_accuracy, field_layer_map, policy grants). `Coverage` (pure rollup: org axis + agent axis from the same leaves, ratios recomputed; property-tested reconciliation). `ForecastService` (openCycle idempotent + outstanding per owned leaf, append-only submit + no-op suppression + inline coverage recompute, skip, close). `CoverageProjector` (replayable slice rebuild). `H6QRoutes` (my-forecasts, submit/skip own-scope, cycles, scenarios, variants catalogue-live, outstanding who-owes, coverage board layer-projected, reconcile). Desk UI `H6Q.tsx` (capture grid: account × SKU × P20/P50/P80; coverage board: by-branch/by-agent toggle + reconcile badge). Tests: CoverageSpec (property), ForecastSuite + H6QHttpSuite (8 integration), Playwright h6q.spec (agent submits portion → rolls up → branch ≡ agent ✓). **Deferred (not yet built):** weighted-pipeline from deals (no deal table yet), dispatch/activation actuals into coverage, sell-through/overhang + V2/V3, Hyperview ingest + precedence, accuracy scoring job, WoW, ex-cut resolution, xlsx export, owner notifications.
- **Gate (07 M11 / doc 12):** cycle opens outstanding submissions + notifies; per-account submit rolls up bottom-up and re-aggregates by agent (reconciles); append-only revisions; "who still owes"; coverage/WoW live; ex-Octopus/ex-Motability toggles; volume-only sees no money; ship-not-activate overhang; accuracy scores past estimates.

### M12 — Intercompany + transfer pricing + tax/customs + treasury hedges  · depends: M7; **docs 13, 16**
1. **Procurement topology** — `procurement_parent_id` chain (year-1 UK←Luxshare-UK; multi-tier = config). 2. **Transfer pricing** — cost_plus/resale_minus/fixed off specific batch cost; reproducible `tp_document`. 3. **Paired legs** — two linked TigerBeetle transfers, FX_CLEARING bridge, atomic. 4. **Tax/customs engine** (doc 16) — pluggable `TaxProvider`/`TaxQuote`; UK/EU rate-table default, US/CA via Avalara/TaxJar/Stripe Tax; import VAT/duty. 5. **Hedges + consolidation** — `fx_hedge` designation, ASC 830 USD translation.
- **Gate (07 M12 / doc 13):** cross-entity move produces two reconciling legs; transfer price reproducible from policy+batch cost; import tax surfaced; designated hedge sets lot cost FX + draws notional; consolidated USD via hedge register.

### M13 — ERP/GL + P&L consumers + Xero + documents  · depends: M6–M12; **docs 16, 17**
1. **GL projections** — AR/AP/inventory off the ledger. 2. **P&L recognition** — matched revenue + COGS on `dispatch.delivered` (ASC 606), reclassify `COS_CLEARING`→COGS, downstream. 3. **Xero feed** — swappable accounting consumer. 4. **Document generation** (doc 17) — invoices/credit-notes/proformas/packing-lists/commercial-invoices/statements; per-locale/jurisdiction templates; gapless numbering; PDF; invoice auto-issued on delivery.
- **Gate (07 M13):** every financial event posts to TB and reaches Xero via the queue; P&L recognises rev+COGS together on delivery at specific batch cost; swapping the accounting consumer needs no core change; legal documents render per locale/jurisdiction.

### M13b — Period close + reconciliation + Auditability Center  · depends: M13; **docs 14, 20**
1. **Close + lock** — checklist + period lock (no back-posting). 2. **Reconciliation engine** — TB↔GL, GL↔Xero, inventory↔counts, AR↔invoices, with sign-off. 3. **Control register + runs** — re-perform `evidence_query`. 4. **Auditability Center** (doc 20 screens) — lineage explorer (figure→transfers→events→documents→replay), money-integrity + time/preview-reslice panels, read-only `auditor` portal, evidence export.
- **Gate (07 M13b / 14 §5–6):** a month can't lock with an open reconciliation exception; a revenue figure drills to transfers→events→documents and re-derives by replay; "preview reslice" shows period moves before commit; "re-perform now" records a `control_run`; auditor can view+export but edit nothing.

### M14 — Companion app + back-office desk + Horizons + reporting + HubSpot  · depends: M11–M13b; **docs 08, 20, 21**
1. **Companion app** — field surface (spec 08: Flutter; **OPEN decision** — Flutter vs React+StyleX PWA, see CLAUDE.md §5), offline-tolerant. 2. **Back-office desk** — the full doc-20 screens (pricing governance, permission builder, Deal Desk, H6Q board, finance, supply, admin, Auditability Center) on the existing Vite+React+StyleX slice. 3. **Horizons feed + reporting** (doc 21) — units→revenue→COGS→GP feed; layer-respecting reports/exports. 4. **HubSpot replication** — CRM/deals out at end of flow.
- **Gate (07 M14):** one companion codebase serves mobile/tablet/web; owner submits weekly forecasts + sees own real-time commission offline-tolerant; Horizons feed reaches downstream; reports respect data layers; CRM replicates to HubSpot.

### X — NFR / Security / Ops-DR  · cross-cutting, **doc 19** (P1 launch-blocker)
Not a single milestone — verified continuously and gated at go-live: latency/throughput/availability SLAs;
RPO/RTO + backup/restore (Postgres/TigerBeetle/Pulsar); secrets/encryption; **GDPR erasure/DSAR crypto-shred
procedure**; STRIDE threat model; SOX controls index; alerting; DLQ-replay + projection-rebuild runbooks; CI
migration-safety (Flyway forward-only, Avro `schemaCheck`, no-float, secret-scan).
- **Gate (doc 19):** the verification block — latency budgets met under load; a DSAR erases PII while the
  financial skeleton stays intact + balances unchanged; DLQ-replay and projection-rebuild runbooks execute;
  backups restore within RTO; all CI gates green on every MR.

### Infra / deploy  · `terraform/` · ✅ **template authored** (mirrors `athena/terraform`)
End-to-end Terraform tree: `roles-global` (RBAC operator roles + WORM records boundary), `rds` (PG16, Multi-AZ
prod, writes the runtime creds secret), `conduit-tigerbeetle` (ledger cluster, cluster ids 300/400),
`conduit-records` (WORM S3: object-lock+versioning, 7y), `conduit-api` + `conduit-consumer` (ASG + nixos-bootstrap),
`deploy-versions.yaml` + README (apply order, secrets, workspaces). `terraform fmt` clean; private gitlab modules
need estate SSH for `init`/`plan`. *Pending: the `files/` config (application.conf/logback/mk-env.tmpl) + `.gitlab-ci.yml` publish→deploy stages.*

---

## 4. Recommended build order

Continue **M6 → M7 → M8 → M9** (the traceability/supply core; each unlocks the next, and M6+M7 close the
M5 residuals), then **M9b** (returns) and **M10** (deal desk + the migration runbook — start it early in
parallel since it's the biggest go-live risk). Then Phase 3 **M11 → M12 → M13 → M13b → M14**. Thread **doc-19
NFR/Security/Ops** work continuously (it gates go-live, not a milestone). Build each sub-step test-first against
its acceptance gate; keep the per-milestone suite green before moving on.
