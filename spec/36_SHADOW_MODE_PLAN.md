# 36 — Shadow-mode execution plan (S1–S4)

**Strategy (2026-06-18):** Conduit's destination is the **top of Hypervolt's software topology**
(Rippling-Unity-style — everything else, ERP included, eventually subservient to it). The near-term
path is the deliberate inverse: **run Conduit in shadow.** Build inbound integrations so every artifact
it cares about flows INTO Conduit automatically; run the **entire event pipeline + immutable ledgers +
every engine** on that live data; let senior teams review and refine for months; cut over only when the
books tie.

This doc is the **step-by-step technical execution plan + living tracker** for that work. It adapts the
proven slice/acceptance format of [`34_PHASE2_PLAN`](./34_PHASE2_PLAN.md), and is governed by the design
in [`33_INGEST_SYNC`](./33_INGEST_SYNC.md) (continuous ingest + dual-run principles — read its §1 first,
it is load-bearing and not repeated here) and the cutover gates in [`18_MIGRATION_CUTOVER`](./18_MIGRATION_CUTOVER.md).
The high-level dashboard lives in [`STATUS.md`](./STATUS.md); acceptance source-of-truth is [`07_BUILD_PLAN`](./07_BUILD_PLAN.md).

**Status legend:** ✅ done · 🟡 in progress / partial · 🔜 next · ⬜ not started · ⏸️ deferred (post-takeover).
Tick each slice's box as it lands; a slice is done only when its **Acceptance** holds.

---

## The four tracks

| Track | Status | Goal | Depends on |
|---|---|---|---|
| **S1 — Inbound durability spine** | ✅ DONE | Inbound data is never lost | — |
| **S2 — Live connectors** | 🟡 IN PROGRESS | Snapshots → continuous live streams into the inbox | S1 |
| **S3 — Run-live + refinement loop** | 🟡 PARTIAL | Senior teams validate Conduit vs source over months | S2 |
| **S4 — Finish dormant engines** | 🟡 IN PROGRESS | Shadow is whole, not hollow | parallel |

**The two hard invariants** (enforced everywhere, tested in S1/S3):
1. **Inbound is never lost.** A record is captured durably (PG) before any mapping; the relay + mapping
   consumer are at-least-once with idempotent handlers; an unmappable record is **quarantined** (raw +
   error retained), never dropped; a drifted re-pull re-enters the inbox.
2. **No outbound side-effects in shadow.** Conduit ingests, computes, posts to its **own** ledger, and
   reconciles — but every business-affecting outbound action (Xero push, HubSpot write-back, customer
   email/invoice, Stripe charge) is suppressed by `ShadowGuard` (33 §1.5). Conduit observes; it does not act.

---

## S1 — Inbound durability spine (the inbox) ✅ DONE (2026-06-18)

The mirror of the transactional outbox: a durable landing zone + Pulsar transport + a mapping leg that
reuses the boot handlers. Built on the ~70% that already existed (`IngestConnector`/`IngestRunner`/
`IngestSink`/`SyncStateRepo`/`sync_state`/`ingest_record` — 33 §2–3).

```
connector.pullSince → IngestRunner.drain → IngestSink → ingest_record          ① DURABLE PG LEDGER (status='received')
ingest_record (status='received') → InboundRelay → Pulsar conduit.inbound      ② TRANSPORT
conduit.inbound → InboundMappingConsumer → SnapshotLoader.mapInbound(handlers)  ③ MAP (same code as boot)
                                            → engines + outbox  → status='processed'
                                            on failure → status='failed' (quarantine, raw+error retained)
```

- [x] **S1.1 — `ingest_record` → relay-driven inbox.** Migration `V1_1_7__inbound_inbox.sql`: add
  `seq BIGSERIAL`, `status` (`received|published|processed|failed`), `published_at`, `processed_at`,
  `attempts`, `last_error`; hot-path index `WHERE status='received'`; quarantine index `WHERE status='failed'`;
  `view:ingest_record` perm for admin/ceo/finance/auditor. *Applied live (ingest_record was empty).*
- [x] **S1.2 — drift re-queues.** `IngestSink.write` (domain/ingest): on a payload-hash change the upsert resets
  `status='received'`, clears `published_at`/`processed_at` → the new shape re-flows; an unchanged re-pull is left exactly as-is.
- [x] **S1.3 — transport.** `event/InboundEnvelope` (`source,dataset,source_id,source_hash,payload`) +
  topic `conduit.inbound` + `event/InboundPublisher` (`PulsarInboundPublisher`, single cached producer, keyed by source).
- [x] **S1.4 — inbox repo.** `ingest/InboxRepo`: `fetchReceived`, `markPublished` (NEL of keys), `markProcessed`
  (a `ConnectionIO` the consumer threads into the mapping tx), `markFailed`, `quarantine`, `requeue`, `statusCounts`.
- [x] **S1.5 — relay.** `ingest/InboundRelay` (mirror of `OutboxRelay`): publish the `received` backlog in `seq`
  order → mark `published`. Wired in consumer `Main` as `Supervised("inbound-relay", …)` @ 1s.
- [x] **S1.6 — shared mapping.** `SnapshotLoader.mapInbound(source,dataset,payload)(after)`: looks up the SAME
  boot handler, runs `handler ⨾ after` (the inbox status flip) in **one tx** → live data and git-snapshot share
  one mapping codebase. Unknown source family → `Left` (→ quarantine).
- [x] **S1.7 — mapping consumer.** `consumer/InboundMappingConsumer`: `conduit.inbound` → `mapInbound` → engines+outbox;
  parse/handler/unknown-family failures → `markFailed` + ack (poison never redelivered forever); infra failure → nack.
  Wired as `Supervised("inbound-mapping", …)`.
- [x] **S1.8 — dead-letter desk surface.** `api/routes/InboxRoutes`: `GET /inbox/health` (per-source status counts),
  `GET /inbox/quarantine?limit&offset` (raw payload + error retained), `POST /inbox/requeue` (operator re-queue,
  `edit:reconciliation`). Registered in api `Main`.
- [x] **S1.9 — acceptance IT (DONE).** `api-it/InboxIntegrationSuite` against a testcontainers Postgres: land a
  row (durable `received`) → relay publishes in seq order + marks `published` → `mapInbound` maps via the boot
  handler into the engine (`exchange_rate`) + marks `processed`; an unmappable row → `failed` with raw payload
  retained; the relay re-run never re-publishes; a drifted re-pull re-enters the inbox. **3/3 green** — invariant #1 discharged.

**Acceptance (S1):** a connector write lands durably before mapping; the relay publishes in `seq` order; the
mapping consumer dedupes redelivery; an unmappable row is quarantined (never lost) and visible at `/inbox/quarantine`;
a drifted source row re-flows automatically.

---

## S2 — Live connectors (the gating track) 🟡 IN PROGRESS

> **Status 2026-06-19:** the scheduler (S2.0) + HubSpot (companies, contacts, deals, line_items, tickets,
> company→company branches) + MRPeasy (customer_orders, shipments+serials, purchase_orders, lots→staging) are
> all built, probed against the **real APIs**, unit-tested, and wired token-gated. Remaining: Xero read (S2.4),
> carrier (S2.5); activation push (S2.3) is largely pre-existing (`PlacementConsumer`). Dormant until creds + a
> consumer restart.

Turn the one-time ndjson snapshots into **continuous live streams** landing in the S1 inbox. The connector
abstraction, runner and sink already exist (33 §2–3); what's missing is **(a) a scheduler that drives them**,
**(b) real HTTP API implementations** behind the `*Api` seams (today: test stubs only), **(c) credentials**, and
**(d) the activation/placement push source**. House rule (from 34): build behind the seam + fixture so it's
tested now and lights up when creds arrive.

> **Build to the contract.** The exact source-field → Conduit-column map for every connector below is specced in
> [`37_INTEGRATION_CONTRACTS`](./37_INTEGRATION_CONTRACTS.md) (extracted from the live `SnapshotLoader.handlers`,
> since live + boot share the mapping). Implement each `*Api` against its §; do not guess fields.

### S2.0 — The ingest scheduler  ·  effort: S  ·  external: none
**Goal:** a background driver that runs each `(connector, dataset)` on a cadence, draining pages until caught up,
landing every record via `IngestSink` (cursor advances only on a clean commit — at-least-once).
**Files:** new `ingest/IngestScheduler` (or per-source loops in consumer `Main`, matching the existing 6h-job
pattern). Reuses `IngestRunner.drain(connector, dataset)(sink.write(connector.source))` + `SyncStateRepo`.
**Detail:** one supervised fiber per source; cadence per source (CRM ~5–15m, MRPeasy ~5–15m, activations near-real-time
via push). A **cold cursor = full backfill**, a warm cursor = the steady-state delta (33 §1.3) — same code path.
Per-source concurrency + `MultiplierRedeliveryBackoff`-style backoff on `consecutive_failures` (read from `sync_state`).
**Acceptance:** with a fixture connector, the scheduler drains a cold cursor to completion, advances `sync_state`,
lands rows `received`; on the next tick with no new data it's a no-op; a mid-batch failure leaves the cursor put (re-pull).
- [ ] scheduler fiber(s) wired in consumer `Main`
- [ ] per-source cadence + backoff on `consecutive_failures`
- [ ] fixture-driven test of cold-drain → warm-delta → no-op

### S2.1 — HubSpot live API  ·  effort: M  ·  external: HubSpot private-app token (SSM `/prod/athena/hubspot`)
**Goal:** `HubSpotApi.get(objectType, modifiedSince)` hits the real CRM v3 API; the existing `HubSpotConnector`
(datasets `companies, contacts, deals, line_items`; watermark `hs_lastmodifieddate`; page 100) parses + cursors unchanged.
**Files:** new `ingest/HttpHubSpotApi` (ember `Client` + bearer); `EnvironmentConfig` `hubspot { token }` block
(env/SSM in shadow). **Add `support_tickets`** to the connector's `objectTypes` + a `hubspot` handler branch in
`SnapshotLoader` for tickets (RMA tickets already ingest via `ingest/hubspot/rma_tickets.ndjson` — fold the live
pull into the same handler).
**Detail:** v3 shape `{results:[{id, properties:{…}}], paging:{next:{after}}}`; page via `after`; `modifiedSince`
→ the `hs_lastmodifieddate` search filter. Maps through the SAME `hubspot` handler the boot ndjson uses (→ MDM
golden record, deals, RMA chain). Live pull **supersedes** the committed scrapes once warm.
**Acceptance:** a cold run backfills companies/contacts/deals/line_items/tickets to the inbox and maps to parties/
deals/rma; a warm run pulls only `> cursor`; the MDM golden record updates from a live contact edit (drift re-flows).
- [ ] `HttpHubSpotApi` + config/creds  · [ ] `support_tickets` dataset + handler  · [ ] cold-backfill → warm-delta verified live

### S2.2 — MRPeasy live API  ·  effort: M  ·  external: MRPeasy `access_key`+`api_key` (SSM `/prod/athena/mrpeasy/*`)
**Goal:** `MrpeasyApi.get(endpoint, modifiedSince)` hits the real REST API; the existing `MrpeasyConnector`
(datasets `customer_orders, shipments, stock_lots, purchase_orders, articles`; watermark `modified`) is unchanged.
**Files:** new `ingest/HttpMrpeasyApi` (ember + raw headers `access_key`/`api_key`, base `https://app.mrpeasy.com/rest/v1`);
`EnvironmentConfig` `mrpeasy { access_key, api_key, base_url }`.
**Detail (known quirks, from the MDM/cost work):** `/items` is capped at 100 and **ignores `start`** — paginate
purpose-built endpoints (`customer-orders`, `shipments`, `purchase-orders`) on `modified`; harvest articles by code
where needed. Lands as order/dispatch/`lot_batch`/serial/po via the `mrpeasy` handler → keeps COGS, inventory,
genealogy current. MRPeasy is the **inventory + landed-cost authority** (18 §0), so it wins those facts.
**Acceptance:** a new MRPeasy customer order + shipment appears as a Conduit order + dispatch within a sync cycle;
a new stock-lot lands a `lot_batch` with real landed cost; a PO lands a purchasing row; redelivery is idempotent.
- [ ] `HttpMrpeasyApi` + config/creds  · [ ] pagination/quirk handling  · [ ] order/shipment/lot/po land + map verified live

### S2.3 — Activation / placement push source  ·  effort: M  ·  external: Pulsar `athena-placement-versioned`
**Goal:** live charger activations flow into the inbox in near-real-time (a push source normalised to the same
`IngestRecord`, 33 §2 — a webhook/stream is a latency optimisation over a poll, never a second code path).
**Files:** new `consumer/PlacementInboundConsumer` subscribing to `athena-placement-versioned` (record
`AthenaPlacementVersionedRecord(device, placementId, version)`, sub `conduit-placement-versioned-subscription-1`
per CLAUDE.md) → writes each to `ingest_record` (source `placements`) via `IngestSink`, which the relay+mapper then
drive through the existing `placements` handler → `ActivationService` (warranty clock at activation) + serial→owner.
**Detail:** first-write-wins, re-placement doesn't double, redelivery idempotent (M8 semantics already built).
**Acceptance:** a live placement opens an activation + warranty provision on the unit's specific batch cost and
flips it off-shelf; V2 ignored; re-placement no-op; replay reconstructs exposure.
- [ ] `PlacementInboundConsumer` → `ingest_record`  · [ ] maps via `placements` handler → activation+warranty  · [ ] idempotency verified

### S2.4 — Xero live API (read-only in shadow)  ·  effort: S  ·  external: Xero OAuth2 (config exists)
**Goal:** `XeroApi.get(endpoint, modifiedSince)` reads Invoices/Contacts/Payments for **reconciliation only** (the
authority cross-check, not a write — Xero is downstream). The `XeroConnector` + `XeroConfig`/OAuth token flow exist
(`XeroAccountingConsumer` already does client-credentials). Outbound push stays muted by `ShadowGuard`.
**Files:** new `ingest/HttpXeroApi` reusing the `XeroAccountingConsumer` token machinery.
**Acceptance:** Xero invoices/payments land in the inbox and feed the AR↔Xero / GL↔Xero reconciliations (S3); no
write ever leaves Conduit in shadow.
- [ ] `HttpXeroApi` (read)  · [ ] feeds the Xero-side reconciliations  · [ ] confirmed no outbound in shadow

### S2.5 — Carrier inbound (Rhenus) tracking  ·  effort: M  ·  external: carrier API/feed
**Goal:** replace the stored-field carrier stub with live inbound shipment/track events (dispatch → in-transit →
delivered) so delivery (and ASC-606 recognition timing) reflects reality.
**Files:** new connector/consumer landing carrier events into the inbox → dispatch/delivery state. **Acceptance:**
a live "delivered" event advances the dispatch to delivered and triggers recognition; OTD reflects real timestamps.
- [ ] carrier inbound connector  · [ ] delivery state + recognition driven by real events

**Acceptance (S2 overall):** every source listed has a live connector landing into the S1 inbox on a cadence;
real new records (a HubSpot deal, an MRPeasy order, a field activation) appear in Conduit's engines within one sync
cycle; the **sync-health board** (`sync_state`) and **inbox health** (`/inbox/health`) show all sources green and current.

---

## S3 — Run-live + the refinement loop 🟡

Drive the live streams through every engine and make Conduit's correctness **continuously visible** to senior teams —
the months-long loop that earns the takeover. Much exists: `ShadowValidationService` (5-check battery → `shadow_finding`
triage queue, idempotent re-runs, `/shadow/*`, scheduled 6h), `DualRunReconciler` (aggregates the source side from
`ingest_record` vs Conduit), the Govern desk view, `ShadowGuard` muting outbound.

### S3.1 — Continuous derived-vs-source reconciliation  ·  effort: M
**Goal:** the diff IS the product (33 §1.6). Extend the `shadow_finding` battery beyond the current 5 checks to cover
every newly-live stream: order count/value vs MRPeasy + HubSpot, AR vs Xero, stock-on-hand vs MRPeasy lots, activation
count vs placement registry, deal pipeline vs HubSpot. Each discrepancy → a triaged finding (human-resolvable, auto-resolve on clear).
**Files:** `shadow/ShadowValidationService`, `close/DualRunReconciler`. **Acceptance:** a deliberate source/Conduit divergence
raises a `shadow_finding` within a cycle; clearing the cause auto-resolves it; human triage is preserved across re-runs.
- [ ] extend the check battery per live stream  · [ ] auto-resolve-on-clear retained  · [ ] each check has a fixture test

### S3.2 — Inbox + sync observability & alerting  ·  effort: S
**Goal:** the never-lose invariant is *monitored*, not just designed. Prometheus gauges: inbox depth by status,
quarantine count, oldest `received` age (relay lag), `sync_state` lag + `consecutive_failures` per source.
**Files:** `metrics/ConduitMetrics` (the existing registrar). **Acceptance:** a stalled relay or a growing quarantine
is visible on the dashboard and alertable; sync lag per source is graphed.
- [ ] inbox gauges  · [ ] sync-lag/failure gauges  · [ ] dashboards/alerts

### S3.3 — The senior-team review desk  ·  effort: M  ·  external: none
**Goal:** the surfaces senior teams actually use to inspect + give feedback: the **Inbox desk** (health board +
quarantine list + requeue, consuming `/inbox/*`) and the **sync-health board** (consuming `sync_state`), alongside the
existing Govern shadow-findings view. **Files:** `conduit-desk/` (new Inbox/Sync views). **Acceptance:** an operator
sees per-source freshness, drills a quarantined row to its raw payload + error, and requeues it after a fix — all in the desk.
**Screens specced:** [`20_BACKOFFICE_DESK`](./20_BACKOFFICE_DESK.md) §9b — **D23 Inbox & quarantine**, **D24 Sync-health board**, **D25 Shadow findings review**.
- [x] D23 Inbox view (health/quarantine/requeue) — Inbox.tsx, live in the desk  · [ ] D24 sync-health board  · [ ] D25 shadow-findings review + feedback capture

### S3.4 — Cutover-readiness gates  ·  effort: S  ·  external: none
**Goal:** make "are the books tied?" a single, honest readout against the 18 §4 cutover gates (trial balance, AR↔Xero,
inventory↔count, zero unresolved high-severity findings, all sources current). **Acceptance:** a one-screen readiness
panel shows every gate green/red with its drill-through; takeover is a decision, not a guess.
- [ ] readiness panel mapping the 18 §4 gates to live checks

**Acceptance (S3 overall):** Conduit runs continuously on live inbound with outbound muted; any divergence from a
source system surfaces as a triaged finding within a cycle; senior teams have the desk surfaces to review, comment,
and watch the cutover gates converge over the parallel window.

---

## S4 — Finish the dormant engines (parallel) 🟡

So shadow is whole, not hollow. These engines are built + tested but inert live for lack of a real source or a final wire.

### S4.1 — Commission on a real source  ·  effort: M  ·  external: comp-plan + agent roster
**Goal:** M5 accrues real numbers. Today `sales_agent`/`commission_scheme` = 0, so `CommissionConsumer` accrues £0.
**Detail:** ingest a real agent roster (HubSpot deal owners are a candidate seed) + the real commission schemes (basis =
gross margin %, validity windows, team/channel/country assignments per 07 M5) → the accrual lights up on the live order/
dispatch stream. **⚠️ DECISION:** confirm the authoritative comp-plan source. **Acceptance:** the correct scheme resolves
by team+channel+country+date; accrual posts on dispatch and trues-up to actual batch margin; a statement reconciles to ledger.
- [ ] decide + ingest the agent/scheme source  · [ ] accrual lights up on the live stream  · [ ] statement ties to ledger

### S4.2 — Fuzzy MDM triage to zero  ·  effort: M  ·  external: model batch (Anthropic)
**Goal:** clear the **36,281 fuzzy MRPeasy↔HubSpot candidates** (never a guessed merge). Run the model matcher
(claude-sonnet-4-6, verdicts keyed on stable MRPeasy name so they replay) over the backlog in batches; the human triage
queue + merge/branch UI already exist (#34). **Acceptance:** the candidate queue drains; every merge carries loser→winner
lineage; no auto-merge below the confidence gate; verdicts replay cross-machine.
- [ ] batch the backlog through the matcher  · [ ] human triage the ambiguous tail  · [ ] queue at/near zero, lineage intact

### S4.3 — Hedged-COGS ledger posting  ·  effort: S  ·  external: none
**Goal:** plug `HedgeMath.effectiveRate` into the COGS posting path so hedged lots post at the effective (not spot) rate
(M12-Treasury remaining slice). **Acceptance:** a designated hedge sets a lot's cost FX to the contracted rate (`fx_basis='hedged'`),
COGS posts at that rate, and consolidated exposure translates via the hedge register; trial balance holds.
- [ ] wire `HedgeMath.effectiveRate` into recognition COGS  · [ ] hedged-vs-spot COGS verified on a real lot

### S4.4 — CRM write-side (minimal, shadow-safe)  ·  effort: M  ·  external: none
**Goal:** the read-only gaps that block accurate inbound capture — contact/deal/pipeline-stage **edits inside Conduit**
(still no write-back to HubSpot — that's deferred outbound). **Acceptance:** an operator can correct/annotate a master
account, deal stage, or branch link in the desk; the change is audited; nothing leaves Conduit.
- [ ] contact/account edit API + audit  · [ ] deal/pipeline-stage edit  · [ ] permission-builder API (doc 06)

---

## S5 — Depth & flow backlog (P2/P3, fold in per owning milestone) 🟡

Genuinely unspecced flow/depth gaps surfaced by the 2026-06-18 spec audit (vs `10_REMAINING_TO_PLAN` §B/§C).
Not on the S2 critical path, but tracked here so nothing lives only in conversation. **Spec-first** before build.
- [ ] **S5.1 — Warranty CLAIM lifecycle** (raise→assess→approve→repair/replace/refund→close). *ABSENT* — only the
  provision register is specced (04 §Warranty). Needs a `warranty_claim` state machine + ledger posting + RMA (09) link. (M8 depth)
- [ ] **S5.2 — Notifications engine** — *SHALLOW* (only companion push, 23 §2). Channels (push/email/in-app), templates
  (per-locale), preferences, digests, the event→notification consumer. (M14-adjacent; the `NotificationDelivery` relay exists.)
- [ ] **S5.3 — Search model** — *ABSENT*. Searchable entities (orders/accounts/serials/deals), index strategy
  (PG FTS vs OpenSearch), layer-scoped result projection. (desk usability)
- [ ] **S5.4 — Kits/bundles** — *ABSENT*. BOM, assemble/disassemble stock, kit serialisation, BOM relief. (M3/M6)
- [ ] **S5.5 — Blanket / standing agreements** — *ABSENT*. Header-level call-off above the line-level `delivery_tranche` — confirm the pattern is real first. (M4)
- [ ] **S5.6 — Allocation-priority policy** — *ABSENT* (placeholder "configurable"). The rule when stock is short (date/age/tier/channel). (M6)
- [ ] **S5.7 — Catalogue lifecycle** — *ABSENT*. NPI/new-product, SKU supersession/EOL, ongoing `mrp_sku` map maintenance. (M3)

Each spec'd in (or as a deep-dive off) its owning module's doc, then built when that module's live stream lands.

## Explicitly deferred (post-takeover) ⏸️

Not gaps under shadow — out of scope until Conduit is trusted as system of record:
- **All outbound / write-back** — M14 HubSpot-out (CRM/deals replicate out), Horizons outbound feed, any source write-back.
- **Flutter companion app (M14)** — PARKED (separate `ux` repo, needs the design pass).
- **M13-Tax external vendor adapter** (Avalara/TaxJar) — a dormant seam; no outbound tax filing in shadow.

---

## Consolidated tracker

| ID | Slice | Track | Status |
|---|---|---|---|
| S1.1–S1.8 | Inbox spine (migration, drift, transport, repo, relay, shared mapping, consumer, desk route) | S1 | ✅ |
| S1.9 | Acceptance IT (PG round-trip; map-to-engine; quarantine; idempotency; drift) | S1 | ✅ |
| — | **Integration field contracts** (per-source maps, doc 37) | S2 | ✅ |
| S2.0 | Ingest scheduler (drives connectors → inbox on a cadence) | S2 | ✅ |
| S2.1 | HubSpot live API — companies, contacts, deals (company assoc + pipeline), line_items (deal_line), company→company branch hierarchy (BranchLinkService); support tickets ✅ | S2 | ✅ |
| S2.2 | MRPeasy live API — orders + shipments + serials (POs ✅, lots→staging ✅) | S2 | 🟡 |
| S2.3 | Activation/placement push source | S2 | ⬜ |
| S2.4 | Xero live API (read-only) | S2 | ⬜ |
| S2.5 | Carrier inbound (Rhenus) | S2 | ⬜ |
| S3.1 | Continuous derived-vs-source reconciliation | S3 | 🟡 |
| S3.2 | Inbox + sync observability/alerting | S3 | ⬜ |
| S3.3 | Senior-team review desk — D23 Inbox view DONE; D24 sync-health (Sync tab) live; D25 shadow-findings (Shadow tab) live | S3 | 🟡 |
| S3.4 | Cutover-readiness gates panel | S3 | ⬜ |
| S4.1 | Commission on a real source | S4 | ⬜ (⚠️ decision) |
| S4.2 | Fuzzy MDM triage to zero | S4 | 🟡 |
| S4.3 | Hedged-COGS ledger posting | S4 | ⬜ |
| S4.4 | CRM write-side (shadow-safe) | S4 | ⬜ |
| S5.1 | Warranty claim lifecycle | S5 | ⬜ (ABSENT) |
| S5.2 | Notifications engine | S5 | 🟡 (shallow) |
| S5.3 | Search model | S5 | ⬜ (ABSENT) |
| S5.4–S5.7 | Kits/bundles · blanket agreements · allocation-priority · catalogue lifecycle | S5 | ⬜ (ABSENT) |
| — | Outbound / companion / tax-vendor | deferred | ⏸️ |
