# Conduit — Build Status, Ignition & Shadow-Mode Plan

_As of 2026-06-18. Assessed by reading the live code (310 Scala files, 89 migrations), the
testcontainers integration suites, and the running Postgres (`conduit-postgres`) row-by-row.
Companion to [`spec/07`](./07_BUILD_PLAN.md) (the acceptance source of truth) and the root
`CLAUDE.md` (the implementation contract)._

> **Update 2026-06-18 — STRATEGY REFRAMED: shadow-mode first** (governs everything below).
> Conduit's destination is the **top of Hypervolt's software topology** (Rippling-Unity-style —
> everything else, ERP included, eventually subservient to it). The near-term path is the deliberate
> inverse: **run Conduit in shadow.** Build inbound integrations so every artifact it cares about
> (POs, support tickets, RMAs, deals, activations, stock moves) **flows INTO Conduit automatically**;
> run the **entire event pipeline + immutable ledgers + every engine** on that live data; let senior
> teams review and give feedback; refine for months before takeover. Consequences now baked in:
> **(1) no outbound/write-back yet** — M14 HubSpot-out, Horizons-out and any source write-back are
> DEFERRED; the Flutter companion is PARKED. **(2) inbound data must NEVER be lost** — the hard
> architectural constraint. **(3) finish the dormant engines** (commission, fuzzy-match triage,
> hedged-COGS) so shadow is whole, not hollow. The work is now organised as four tracks **S1–S4**
> (see "Shadow-mode operating model" below), replacing the old Phase-C/D framing.
>
> **S1 SHIPPED (2026-06-18)** — the **durable inbound spine** (the mirror of the outbox). `ingest_record`
> evolved into a relay-driven inbox (`received → published → processed → failed`); `InboundRelay`
> publishes the backlog to Pulsar `conduit.inbound`; `InboundMappingConsumer` maps each row through the
> **same `SnapshotLoader` handlers as boot** (one mapping codebase for live + snapshot) into the engines
> + outbox; unmappable rows are **quarantined** (raw payload + error retained, never dropped) and surfaced
> at `/inbox/{health,quarantine,requeue}`. A drifted re-pull re-enters the inbox automatically. Migration
> `V1_1_7` applied live. **S2 next**: a live scheduler driving the (already-built) `IngestRunner`/connectors
> + real HubSpot/MRPeasy/activation API impls + credentials, so real records start landing.

> **Update 2026-06-18 — the CRM/MDM layer went live** (Phase C, M4). A full Master Data
> Management golden-record was built on top of the dormant trade substrate: every customer the
> business has ever touched — across MRPeasy, HubSpot (companies + 154k contacts), the placement
> registry and Keycloak — is now correlated into **one master account**, with serial→owner
> genealogy, branch hierarchy, model-assisted matching, and a customer-360 desk. See the **CRM/MDM
> (M4)** section after the table. This is net-new beyond the 2026-06-16 ignition plan and is the
> bulk of recent work.

> **Update 2026-06-18 — Home 3.0 cost gap CLOSED** (B2 tail). The last COGS hole — the Home 3.0
> family (`hv-350/375/310`, 9,116 dispatched serials) — had no MRPeasy `avg_cost` (it's drop-shipped,
> 0 stock), but the units were built in-house and each carries real **manufacturing orders** with the
> actual production `item_cost`. Pulled the quantity-weighted MO build cost per SKU (£204–285, 1,200+
> finished units each) → committed `ingest/cost/mrpeasy_home3_buildcost.ndjson` (no proxy). Wired with
> zero code change (the `cost` handler + `createLegacyLots` cost `supplier='MRPeasy'` SKUs, then
> `linkSerials` → `emitRecognitionEvents`). Released into recognition: **7,006 dispatches recognised**,
> trial balance held exact (debits = credits = £106,884,328.67, imbalance £0.00). **New P&L: recognised
> 28,920 dispatches · revenue £48.51m ex-VAT · COGS £25.45m · gross margin £23.06m (47.5%)** — same
> ~47% band, confirming the MO cost lands cleanly. Inventory stays £0.00 (all Home 3.0 dispatched,
> none on-hand). **Fully replayable from git**: the committed ndjson == live `supplier_cost`, and a
> clean boot regenerates lots → links → recognition automatically. No remaining uncosted dispatched
> serials.

> **Update 2026-06-18 — Phase C (C4–C7) closed + Conduit brand adopted.**
> - **C4 Pricing**: already seeded/verified (22 agreements, 198 rules, governed quoting).
> - **C5 Documents (M13-Docs)**: WORM pipeline stood up — MinIO local S3 (compose) + gapless series
>   + line-VAT backfill + dispatch-scoped invoices. **Historical invoice-PDF backfill was tried, then
>   reverted**: re-minting fresh 2026-numbered customer invoices for sales already invoiced in
>   MRPeasy/Xero — billed to MRPeasy stub parties, line items rendering as "MRPeasy import" — was
>   meaningless. Purged (2,632 docs + objects, series reset to 0). **Decision: historical invoicing =
>   the AR ledger rows (`order_invoice`, which tie AR), NOT minted PDFs.** The document engine
>   (FOP/WORM/gapless + dispatch line-scoping) is retained for invoices Conduit *issues going forward*
>   — which must resolve bill-to to the master account and use real line items. The **line-VAT
>   backfill and the tranche model are kept** (correct data regardless): 3,735 `delivery_tranche`
>   rows decompose multi-shipment orders, and orders reconcile to Σ dispatch invoices (22,356/24,022
>   exact; 1,663 partially shipped; 3 over-invoiced → Shadow Validation).
> - **C6 H6Q (M11)**: forecast-ownership seed (37 real ≥£100k accounts, owned by the operator) +
>   a scheduled consumer opener → cycle **2026-W25** open with 37 outstanding capture slots; the
>   bottom-up spine is exercised (`/h6q/cycles`, `/h6q/my-forecasts`).
> - **C7 Provenance**: `SnapshotLoader` records `sync_state` per dataset load (22 datasets
>   backfilled — contacts 154,317 … cost 25). The orphan table now has a writer.
> - **Brand**: adopted the Conduit "Articulate" junction mark + purple Iris favicon/app-icon across
>   the desk (rail, sign-in, browser tab) from the Claude Design `identity/` project.
> All replayable via ignition/compose.

## TL;DR

**The code is built and CI-proven across essentially every milestone (M0–M13b + M-Pricing +
M13-Docs + M13-Tax), and the historical book has been ignited** — real P&L (£48.51m rev / 47.5% GM),
balanced ledger, 150k-account MDM golden record, order/serial/RMA topology, all replayable from a
clean boot. The original "import the history" goal is essentially met.

**The mission is now shadow mode** (see the reframe update above). The question is no longer "is the
engine built" — it almost always is — but **"is it fed by a live inbound feed, running on current
reality, and producing outputs senior teams can review?"** That reframes the milestone table below:
read each row through the **shadow lens** — most engines are ✅/🟢 built, and the remaining work is
**connecting them to live inbound (S2), validating against source (S3), and finishing the few that
are hollow without a real feed (S4)**. Outbound is out of scope (deferred). The S1–S4 tracks below
are the governing plan; the Phase-A/B ignition history is retained as the record of how we got here.

> **No-fake-data rule still governs.** Most ignition steps complete state from real data (mark
> historical dispatches delivered, open warranty from real activations, recognise revenue on real
> order lines). A few steps (batch granularity, landed-cost components) have **no real source** and
> need a decision — flagged as ⚠️ DECISION below.

## Shadow-mode operating model (S1–S4) — the governing plan

The four tracks that take Conduit from "ignited on history" to "running live in shadow, trusted enough
to take over." S1→S3 are a dependency chain; S4 runs in parallel.

| Track | Status | Goal | What it is |
|---|---|---|---|
| **S1 Inbound durability spine** | ✅ DONE (2026-06-18) | Inbound data is never lost | The inbox: `ingest_record`→relay→`conduit.inbound`→mapping consumer (reuses boot handlers)→engines+outbox; drift re-queues; failures quarantine (raw retained) at `/inbox/*`. The mirror of the outbox. |
| **S2 Live connectors** | 🔜 NEXT | Snapshots → continuous streams | A scheduler driving the already-built `IngestRunner`/`IngestConnector` set on a cadence + **real API impls + credentials** for HubSpot (deals, contacts, companies, RMA + support tickets), MRPeasy (POs, MOs, items, stock moves), activation/placement registry. Each lands in the S1 inbox. Today the `*Api` traits are test stubs only. |
| **S3 Run-live + refinement loop** | 🟡 PARTIAL | Senior-team feedback over months | Drive live inbound through every engine; extend the existing `shadow_finding` harness to continuously diff Conduit's derived state vs source; the desk views senior teams review + comment on. The loop that earns the takeover. |
| **S4 Finish dormant engines** | 🟡 IN PROGRESS | Shadow is whole, not hollow | Commission (find a real `sales_agent`/`commission_scheme` source so it stops accruing £0); the **36,281 fuzzy MDM candidates** (model + human triage); hedged-COGS ledger posting; CRM write-side gaps. |

**Explicitly out of scope until takeover** (was Phase D2): all **outbound** — M14 HubSpot-out, Horizons
outbound feed, any write-back to ERP/source systems — and the **Flutter companion app (PARKED)**. The
M13-Tax external vendor adapter (D1) stays a dormant seam (no routed vendor required in shadow).

### The milestone table through the shadow lens

The table below is the build-completeness record (mostly ✅/🟢). Re-read for shadow, each milestone maps
to a track: **fed live? → S2** · **validated vs source? → S3** · **hollow without a real feed? → S4**.
- **S2 (needs a live feed)**: M4 CRM/orders, M6 inventory/dispatch, M7 batch/genealogy, M8 activation/RMA,
  M9 purchasing, M9b returns — all built and ignited on history; they go live when their connector streams.
- **S4 (hollow until real source data lands)**: M5 commission (0 agents/schemes), M-Pricing tiers (empty
  live), M12 intercompany/TP (0 entities), M12-Treasury hedged-COGS posting.
- **S3 (validate + close the loop)**: M13 GL/P&L, M13b period-close/recon/audit, M10 shadow-validation —
  the dual-run comparison surfaces that senior teams sign off against.

## Milestone status

| Milestone | Status | One-line |
|---|---|---|
| M0 Scaffold | ✅ DONE | Multi-module sbt, nix, compose (pg/pulsar/TB/consul/keycloak), CI, `/health`. |
| M1 Foundations | ✅ DONE | Typed Money, conserving allocate, outbox+relay, TB deterministic ids, period lock, TZ projection — all property/IT-tested. |
| M2 Access control | ✅ DONE | PolicyEngine + scope predicate + layer projection + FieldLayerMap (82 rows) + 11 preset roles, server-side. |
| M3 Catalogue + ADLP pricing | 🟢 SEEDED | Engine + `/pricing/quote` done & tested; **real price book now live** — 198 prices from the Comprehensive Pricing workbook → 22 `price_agreement`s (Retail open-list, Installers segment, 20 customer-set: Octopus/YESSS/CEF/Rexel/…) + 949 party links. Quote verified: retail HV3PRO £575, an OVO party £435.18 (its contract tier). Reproducible via `ingest/pricing/`. **Governance airtight** (doc 24 §3): order placement prices from the tier — a typed/non-tier price → `NonTierPrice` reject, unpriced SKU → `NoPrice`. **Pricing desk view** (Sell) surfaces the book grouped by agreement. CM cost tiers preset (Volex HV3PRO + **legacy in-house mfg from real MRPeasy `avg_cost`, 19 lots**); Luxshare ICT future shift still to wire. IC transfer pricing built at recognition (`FlashTitle`→`ic_match`, procurement markup); auto transfer-doc/order is the post-2027 piece (dormant — no procurement entities yet). |
| M-Pricing (doc 24) | 🟡 PARTIAL | **Built, not "spec-only"** — `price_agreement`, tiers, retrospective ASC-606 rebate→ledger all coded & tested; tables empty live. |
| M4 CRM + Order capture | 🟢 MOSTLY (MDM live) | Order capture strong & IT-tested. **Order→ledger commitment wired**: ignition replays the order book as `order.placed`, `OrderCommitmentConsumer` records the sales backlog (ASC 606: no GL at placement), `/finance/backlog` + `/orders/{id}/commitment`. Baseline ties: **committed £60.55m = recognised £36.64m + open £23.91m**. **Master Data Management now live (golden record)** — see the CRM/MDM section below: **150,325 master accounts** (38,353 orgs + 111,972 individual owners), 95,869 contacts attributed, 90,652 serials owner-linked, deal/pipeline book + customer-360 + cmd+K search + phone customer→installer bridging shipped. **Remaining**: no contact/deal *write* API (read + ingest only); pipeline-stage editing. |
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

## CRM / MDM — the golden-record layer (M4, live as of 2026-06-18)

Net-new beyond the original ignition plan. The dormant trade substrate had parties as disconnected
rows per source; this unifies every customer touchpoint into one **Conduit master account** and
hangs the serial-number lifecycle off it. **Fully reproducible from committed `ingest/` ndjson on a
clean machine — no API calls at boot.**

**What's live (real numbers, running DB):**
- **150,325 master accounts** — `party` with `parent_party_id IS NULL`, merged losers excluded.
  Of these **38,353 organisations** (installers/wholesalers/retail/energy/fleet) + **111,972
  individual consumers** (charger owners). **532 branches** hang off parents (CEF-style hierarchy,
  auto-detected by parent-name + manually assignable).
- **249,052 source links** (`account_source_link`) tying each master to its origin identities:
  111,974 placement-owner, 95,869 HubSpot-contact, 23,722 HubSpot-company, 17,487 MRPeasy. This is
  the lineage — every account shows exactly which systems it was assembled from.
- **Serial → owner genealogy**: **90,652 of 108,455 serials** carry `owner_party_id`, resolved
  serial(hex)→DynamoDB placement→Keycloak `retail-customers`. A charger traces to the consumer who
  owns it; the consumer traces back through bulk-buyer → owner chains.
- **Matching**: deterministic exact links auto-bind; fuzzy MRPeasy↔HubSpot pairs go to a review
  queue (**36,281 candidates pending**) — never a guessed merge. A **model matcher** (Anthropic
  Message Batches, claude-sonnet-4-6) verdicts the ambiguous set; verdicts keyed on stable MRPeasy
  *name* so they replay cross-machine. **152 merges** applied with full loser→winner lineage.
- **Phone pre-association** (2026-06-18): a phone is a person-level key. Where email didn't already
  unify them, an exact phone match bridges **384 consumers to the installer who sold/fitted their
  charger** — conservative (2-party only, consumer↔org only, never overwrites), stamped as a
  reviewable `sold_via` soft link, not a merge.
- **Desk (CRM screen)**: Accounts list leads with First/Last/Email/Phone; **cmd+K** searches the
  whole master by name·email·phone (digit-normalized); customer-360 detail shows sources/lineage,
  branches, contacts (with `end_customer` vs `contact` entity-type), order book, **charger
  lifecycle** (serial → status → warranty days left → replaces/replaced-by), and the phone-matched
  installer. Clicking a serial deep-links to Batch & genealogy and auto-traces it. Deal/pipeline
  book attributed per company.

**Honest gaps (M4 remainder):** no contact/deal/pipeline **write** API yet (everything is
read + ingest-derived); pipeline-stage editing and the permission-builder API (doc 06) unbuilt;
36,281 fuzzy candidates await human/model triage; V2 owner coverage limited by the placement
registry. Phone bridges include occasional coincidental shared-phone links — surfaced labelled
("matched by phone"), reviewable, never silently merged.

## Ignition plan (dependency-ordered)

### ✅ Phase A IGNITED (2026-06-16) — the P&L is live on real history
- **A1 done**: 39 monthly accounting periods open (2023-10 → 2026-12).
- **B2 done**: 9 Volex opening lots (HV3PRO fleet, specific-id landed cost, real USD @ ECB + £8) **+ 19 legacy in-house lots from real MRPeasy `avg_cost`** (GBP, supplier `Hypervolt In-House`, pulled via the Athena SSM keys `/prod/athena/mrpeasy/*`). **Costed coverage 27,106 → 9,120 uncosted dispatched serials**: all **17,914 HV-PR-117x** (66% in-house-manufactured bulk) now carry a real cost basis. Lights up M7 genealogy. **Honest remaining gap**: the **Home 3.0 family** (`hv-350-{ub,sg,uw}`, `hv-375-{ub,sg}`, 9,120 serials) is not in MRPeasy — those dispatches still recognise revenue without a COGS basis until their cost source lands.
- **A3 done**: **21,914 dispatches recognised** through the production pipeline (emit `dispatch.created` → relay → `RevenueRecognitionConsumer` → TigerBeetle), up from 19,406 once the legacy HV-PR-117x units gained a cost basis and were released into recognition. **Trial balance exact (debits = credits, £98,812,301.99)**. P&L: **revenue £43.64M ex-VAT · COGS £23.22M · gross margin £20.41M (46.8%)** — same ~47% band as the pre-legacy £36.6M/£19.3M book, confirming the legacy COGS lands cleanly. **AR ↔ backfill invoices ties** (£52,363,134.62 inc-VAT; 17,206 revenue-bearing dispatches invoiced, 4,708 free/£0 dispatches correctly uninvoiced). Lights up M13 (revenue/GL/P&L) on real data; hedged-COGS hook (`HedgeMath.effectiveRate`) ready.
- **A4 done**: per-period P&L live on `/finance/pnl` (recognition date = dispatch date, so revenue re-projects to the real fiscal period — 2025-10: rev £2.66m, VAT £532k, COGS £1.31m, 51% GM). Close drill run on 2025-10: **TB↔GL reconciliation ties EXACTLY (£63.25m)**, finance closed the period, admin's lock correctly **blocked by 2 unsigned reconciliation exceptions** (SoD + lock-gate both proven). Seeded demo-finance + demo-admin users (finance/admin roles) — CEO holds neither edit:accounting_period nor edit:reconciliation (correct SoD).
- **CLEAN LOCK achieved (2025-10 LOCKED)** — and now **fully replayable from a clean `down -v` boot** (no hand-seeding):
  - **Inventory↔count MATCHES** (£0.00): ignition posts the opening INV (DR INV / CR opening-equity) via `OpeningInventoryConsumer` on a per-dispatch COGS basis, so INV nets exactly to physical (fleet fully sold-through). No longer a sign-off.
  - **AR↔invoices MATCHES** (£43,969,414.56): recognition auto-issues one backfill `order_invoice` per historical dispatch (14,758, keyed by `dispatch_id`, idempotent) at the exact AR figures — no manual issue step.
  - **TB↔GL MATCHES** (£82.53m debits = credits this period).
  - **gl↔Xero** signed off (genuinely no Xero connected — honest exception, not hidden).
  - SoD principals (`demo-finance`/`demo-admin`/`demo-ceo`) seeded by migration. Finance closed → admin locked, closer≠locker enforced. The whole auditable close→lock cycle re-derives on every boot.
- **Still deferred** (real, control-caught gaps): (1) issue invoices for the recognised AR → clears AR↔invoices (M13-Docs / C5); (2) post opening inventory balances when minting lots → clears inventory↔count (`SyntheticOpeningLots` opening-INV transfer). Plus: recognise the 25% uncosted dispatches once their cost lands; make the whole ignition reproducible at boot (emit `dispatch.created` from the MRP ingest + opening-INV from lot creation) — currently lots/periods/recognition live in the running DB, not yet on a clean-boot path.

### ✅ Boot recovery + AWS portability (2026-06-16)
The whole ignition is now **reproducible from a clean boot** — verified by a full `docker compose down -v` → fresh boot reconverging to the **identical** state (106,154 serials / **96,958 costed** (9,120 uncosted = Home 3.0 only), 39 periods, balanced ledger, P&L **£43.64m rev / £20.41m margin / 46.8%**, 21,914 dispatches recognised, INV net £0.00) with **no manual steps**:
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

### Phase C — flows, wiring & remaining backfills ✅ DONE (2026-06-18)
All of C1–C7 landed (see the milestone table + the Phase-C/brand update above): C1 ledger-commitment
consumer, C2 M6/M9 routes+consumers, C3 commission accrual wired, C4 pricing book seeded (22 agreements /
198 rules), C5 documents engine (historical PDFs reverted → AR-only by decision), C6 H6Q cycle opened,
C7 `sync_state`/`ingest_record` writers (and `ingest_record` has since become the S1 inbox).

### Phase D → folded into the S-tracks
- **D1.** M13-Tax external vendor adapter (Avalara/TaxJar): a dormant **seam only**, not built — and not
  needed in shadow (no outbound tax filing). Revisit at/after takeover.
- **D2.** M14 — Flutter companion, Horizons feed, HubSpot **outbound**: **DEFERRED (outbound) / PARKED
  (companion)** under shadow-mode. Not on the near-term path.

### Hygiene
- Remove the stale duplicate `conduit/` subdirectory (a 240-file/61-migration snapshot shadowing the live root tree).

## Open work, sorted by shadow track (S1 done)
**S2 — live connectors (the gating track now):**
1. No scheduler drives the connectors live — `IngestRunner`/`IngestConnector`/`IngestSink` exist; nothing polls them on a cadence or feeds the S1 inbox.
2. The `HubSpotApi`/`MrpeasyApi`/`XeroApi` traits have **no live HTTP implementations** (test stubs only) + no credential wiring (HubSpot SSM token; MRPeasy access/api keys).
3. M6 carrier integration is a stored-field stub, not a live Rhenus adapter (inbound shipment/track events).

**S3 — run-live + validate:** drive the live streams through the engines; extend `shadow_finding` to diff derived-vs-source continuously; senior-team review desk.

**S4 — finish dormant engines:**
4. M5 commission accrues £0 — no real `sales_agent`/`commission_scheme` source ingested.
5. 36,281 fuzzy MDM candidates await model+human triage; no contact/deal/pipeline **write** API.
6. M12-Treasury hedged-COGS **ledger posting** not plugged into the COGS path (`HedgeMath.effectiveRate` ready).
7. M-Pricing / M12 intercompany tables empty live (gated on real tier/entity data).

**Deferred (not gaps under shadow):** M13-Tax vendor adapter (D1), M14 companion/Horizons/HubSpot-out (D2).
