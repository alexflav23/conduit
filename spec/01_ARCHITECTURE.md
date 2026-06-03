# 01 — Architecture

## 1. Shape

Conduit is a **modular monolith with an event spine**, not microservices on day one. One Scala/http4s deployable exposes the API and runs in-process modules; all cross-module and cross-system communication that crosses an integration boundary goes through the **event backbone** (Pulsar). State changes are persisted to PostgreSQL and emitted as events via a **transactional outbox**; financial movements are additionally posted to **TigerBeetle**. This keeps the system coherent and debuggable early, while the event spine means any module can later be peeled into its own deployable without changing contracts.

```
                       ┌───────────────────────── Conduit (Scala/http4s) ─────────────────────────┐
 Web desk (React/TS) ──┤  API layer (tapir/http4s)  →  Policy layer (authz: object/action/        │
 iPad H6Q board ───────┤                                 section/scope/data-layer)                 │
 Mobile (RN) ──────────┤  Modules: CRM · Pricing/ADLP · Orders · Inventory/Traceability ·          │
 Reseller API (JWT) ───┤           Commission · H6Q · Intercompany/Tax · Purchasing · Supply       │
                       │  Persistence: PostgreSQL (relational + projections)                       │
                       │  Ledger: TigerBeetle (double-entry, immutable)                            │
                       │  Outbox relay  ───────────────────────────────────────────┐               │
                       └───────────────────────────────────────────────────────────┼───────────────┘
                                                                                    ▼
                                                            ┌──────────── Pulsar (event backbone) ───────────┐
                                                            │ topics per aggregate; schema registry; replay  │
                                                            └───┬─────────┬─────────┬─────────┬──────────┬────┘
   consumers ───────────────────────────────────────────────► │ Ledger  │ H6Q     │ Xero    │ HubSpot  │ Notif│
                                                               │ poster  │ proj.   │ acct    │ mktg     │      │
   inbound feeds ──► UFE/Pulsar (activations) · Athena checkout (web orders) · Keycloak (identity)
```

## 2. Request → state → event → fan-out (the core loop)

Every mutating action follows one path:

1. **API** receives request; **policy layer** authorises (object+action+section+scope+data-layer). Reject → `403` with no data leakage.
2. **Module service** runs domain logic in a single **PostgreSQL transaction** that writes (a) the business rows and (b) one or more rows into the **`outbox_event`** table. Financial effects are computed but posted to TigerBeetle by a consumer (step 5), not in this transaction (TigerBeetle is a separate store; we avoid a distributed transaction by making the ledger an idempotent consumer of the event).
3. **Commit.** Because the business change and the outbox row commit atomically, an event can never be lost or emitted for an uncommitted change.
4. **Outbox relay** (background fiber, or Debezium CDC) reads unpublished `outbox_event` rows in order per `partition_key`, publishes to the matching Pulsar topic, marks `published_at`. At-least-once.
5. **Consumers** (independent subscriptions) react idempotently on `event_id`:
   - **Ledger poster** → TigerBeetle transfers (two-phase where lifecycle applies).
   - **Projection builders** → read models / materialised views (H6Q coverage, account history, stock summaries).
   - **External adapters** → Xero (invoices), **HubSpot (CRM/deal replication — Conduit is source of truth, replicates out at the end of the flow; retained until Conduit is proven, then retired)**, carriers, notifications.
   - **Saga/process managers** → multi-step workflows (e.g. order → allocation → dispatch).

Ordering is guaranteed **per aggregate** via `partition_key` (e.g. `order_id`, `serial`). Cross-aggregate ordering is not assumed.

## 3. Why this resolves the known failure modes

- **Dual-write drift** (DB commits, event doesn't, or vice-versa): impossible — outbox row is in the same transaction.
- **Mirror corruption / silent sync staleness** (the Ghost Busters pain): there is no mirror; consumers rebuild projections by **replay** from the durable log.
- **Pulsar redelivery / charger re-placement** (the activation pain): consumers dedupe on `event_id`; activation is first-write-wins on `serial`.
- **No-single-source finance** (the Athena→Xero/HubSpot split): TigerBeetle is the financial SoR; Xero/HubSpot are consumers.

## 3a. Event-driven scope (and where not to event-source)

Conduit is **event-driven end to end**: every state change emits a durable event via the outbox, and *all* downstream processing — including a future ERP — attaches as a consumer with **no core change**. The log is **complete** (every transition, not just notable ones) and **retained indefinitely** (hot ≥30d, then S3), so any new consumer can be **backfilled by replay** — that is the property that lets you defer the ERP/processing decisions without sacrificing anything.

This is deliberately **not** full *event-sourcing* (the log replacing the database as source of truth) everywhere. Authoritative current state lives in **PostgreSQL aggregates**; true event-sourcing is reserved for the **financial ledger (TigerBeetle, immutable by construction)**. Pure system-wide event-sourcing was rejected because it creates three real limitations for this domain:
1. **PII erasure** — GDPR/right-to-erasure conflicts with an immutable log holding contact PII (CRM). State-stored + crypto-shred keeps erasure tractable; PII is kept out of long-retained event payloads.
2. **Reporting** — pure ES means never querying truth directly, only projections; state-stored aggregates keep ad-hoc/operational queries simple.
3. **Cross-aggregate invariants** — e.g. not overselling stock — are enforced with a Postgres row-lock (doc 04 §ATP); under pure ES that becomes an eventual-consistency conflict problem.

Net: full event-driven optionality (replayable, complete, ERP-ready) without the ES tax. *(Resolves the event-sourcing decision.)*

## 3b. Controls & GAAP / SOX readiness (design principle)

Conduit is designed on the assumption that **Hypervolt will be a Nasdaq-listed, SOX-bound, US-GAAP reporter**. Auditability and controls are first-class, not retrofitted:
- **Immutable financial record:** TigerBeetle (append-only, no destructive edits); corrections are reversing transfers, never overwrites.
- **Complete, append-only event log + `audit_log`** with before/after + actor on every material change; Admin cannot edit audit (doc 05).
- **Specific-identification inventory costing** — each serial carries its own lot's landed cost; **no weighted-average** (doc 04 §Ledger). Cost, price and FX basis are all reproducible and audited.
- **Segregation of duties / maker-checker:** Deal Desk assembles exceptions but only the CEO approves (doc 04 §ADLP); pricing changes are governed and versioned; permission/role changes are audited.
- **Matched recognition:** revenue and COGS recognised together on delivery (ASC 606) downstream, off the same event (§5, doc 04 §Ledger).
- **Reconstruction guarantee:** any price, cost, discount, exception, commission, stock figure or permission state is reconstructable from `audit_log` + the event log + TigerBeetle alone.
- **Pixel-perfect money & explicit time:** typed currency-tagged `Money` (no floats, no implicit FX), conserving allocation, provenanced rates; UTC instants with **fiscal period as a re-projectable parameter** (timezone reslicing = replay, not migration); period **lock** prevents back-posting. This — plus the GAAP index, the SOX control register, PCAOB-grade lineage/re-performance, and the in-product **Auditability Center** — is specified in full in **doc 14 (Financial Integrity)**, and is treated as Phase-1 foundational.

## 4. Backbone

- **Pulsar**, consolidating today's SQS (Athena) + Pulsar (Ghost Busters). One cluster, topics per aggregate type (`conduit.orders`, `conduit.inventory`, `conduit.activations`, `conduit.pricing`, `conduit.commission`, `conduit.ledger`, `conduit.crm`, `conduit.forecast`). Partitioned by aggregate key. Tenant/namespace `conduit`.
- **Schema registry**: Avro (matches avro4s already in use). Backward-compatible evolution enforced in CI (doc 03 §Evolution).
- **Inbound feeds:**
  - **UFE** placement events on `athena-placement-versioned` (existing) → Activation module (doc 04 §Activation).
  - **Athena (retail):** Conduit does **not** replicate Athena's web checkout/cart/payment. Athena remains the retail order processor and **feeds Conduit the completed retail sale**; Conduit consumes it as a `retail` order that **depletes inventory and records the sale** (stock movement + ledger revenue/COGS + serial/genealogy + sell-through), not a checkout. Stripe/cart stay in Athena for now. (Absorbing checkout later is additive — same `order.placed` contract.)
  - **Hyperview (retail forecasts):** Prophet-based model output (ad-spend + inputs) lands as `forecast_entry source='hyperview'` for the retail channel (doc 04 §H6Q). Phase 3.
- **Retention**: ≥ 30 days hot for replay; tiered offload to S3 for long replay/backfill.

## 5. Ledger boundary (TigerBeetle)

- TigerBeetle holds **accounts** and **transfers** only (doc 04 §Ledger). It is the immutable money record. It is **not** queried for business reporting — Postgres projections of ledger events serve reads.
- One **ledger per currency** (TigerBeetle ledgers are single-currency). Cross-currency = two linked transfers through an FX-clearing account (doc 04 §FX).
- The **Ledger poster** consumer is the only writer to TigerBeetle. Postgres stores `tb_transfer_id` back-references.

## 6. Stack & deployment

Conduit conforms to the existing platform pattern — it is a citizen of the same estate as Athena and Ghost Busters, not a standalone app. Reuse the house service template (config, health, metrics, discovery, build) rather than inventing parallel mechanisms.

| Concern | Choice |
|---|---|
| Language/HTTP | Scala 2.13, http4s (Ember), tapir (typed endpoints + OpenAPI) |
| DB access | doobie + HikariCP; Flyway migrations |
| Relational store | PostgreSQL 16 (RDS, Multi-AZ), eu-west-1 |
| Ledger | TigerBeetle cluster (3+ replicas) |
| Backbone | Apache Pulsar + Avro schema registry |
| Auth | Keycloak (OIDC); JWT bearer on API; service JWTs for reseller/integrations |
| Frontend (back-office) | React + TypeScript (Vite), shared API client generated from OpenAPI — the desk/admin web app |
| Companion app | **Flutter (single codebase) — mobile + tablet + web, nothing else**; the agent/forecaster surface (weekly H6Q capture, own real-time commission, order/CRM lookups), offline-tolerant across timezones; consumes the same OpenAPI |
| Payments | Stripe (retail; in Athena for now — see §8) |
| Accounting | Xero (consumer) |
| 3PL/carriers | Rhenus (+ DPD/UPS/Rainus) behind a `CarrierAdapter` interface |
| **Build / dev env** | **Nix** (`shell.nix`, deps pinned via **npins**) — reproducible builds and dev shells, matching the house pattern |
| **Service discovery / config** | **Consul** — service registration + discovery + KV config; Conduit registers and discovers Pulsar, Postgres, TigerBeetle, Keycloak, and peer services via Consul rather than hard-coded endpoints |
| **Infra** | **Terraform** (all infra as code); Docker images; GitLab CI; AWS eu-west-1 |
| Observability | Prometheus metrics, structured logs (log4cats), OpenTelemetry traces keyed by `correlation_id` |

### 6.1 Platform conformance (required)
- **Nix + npins:** the repo ships a `shell.nix`/flake and pins dependencies with npins, as Athena does; CI and local dev use the same Nix shell. No "works on my machine" build paths.
- **Consul:** Conduit registers its instances and resolves dependencies (DB, Pulsar brokers, TigerBeetle, Keycloak, Xero/Stripe egress, peer services) through Consul service discovery; runtime config via Consul KV (with secrets via the house secrets mechanism, not in env files checked in).
- **Terraform:** topics/namespaces (Pulsar), DB, TigerBeetle, Consul services, IAM are all declared in Terraform; nothing provisioned by hand.
- **Health/metrics endpoints** and the http4s service skeleton follow the same shape as the existing Scala services so they slot into the platform's deployment, monitoring and discovery without special-casing.

## 7. The Phase-1 spine (must exist before any feature module)

1. Migrations + `outbox_event` + Pulsar topics + schema registry + relay.
2. Keycloak integration + **policy layer** (doc 05) — object/action/section/scope/data-layer, enforced server-side.
3. TigerBeetle ledgers (per currency) + **Ledger poster** consumer.
4. **Catalogue + variants** + **centralised ADLP pricing** (doc 04 §Pricing).
5. **CRM company/contact** + **Order capture** emitting `OrderPlaced` (doc 04 §Orders).
6. Audit projection (consumer of the staff-action stream).

Everything else (commission, inventory/traceability, activations, H6Q, intercompany, supply, ERP/GL consumers) attaches to this spine as modules + consumers.
