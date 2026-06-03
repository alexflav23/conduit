# Conduit — Technical Specification Pack

Build-grade specification for **Conduit**, Hypervolt's event-driven master system of record (CRM + order processing + pricing/ADLP + commission + inventory/traceability + double-entry ledger + forecasting + ERP-as-consumers). This pack is written to be handed to engineers / Claude Code and implemented module by module.

This is **not** a requirements summary. Each feature document carries field-level schemas, API contracts, event payloads, algorithms (pseudocode), state machines, validation rules, permission mappings, and acceptance tests.

## Documents

| # | Document | Contents |
|---|----------|----------|
| 00 | **README** (this) | Pack map, conventions, status |
| 01 | **ARCHITECTURE** | Services, event flow, outbox, stack, deployment topology, the Phase-1 spine |
| 02 | **DATA_MODEL** | Full field-level PostgreSQL schema (all domains) — the source of truth for storage |
| 03 | **EVENTS** | Event envelope, registry rules, every domain event with payload schema, producers/consumers |
| 04 | **DOMAIN_LOGIC** | Algorithms: pricing resolution, ADLP enforcement, ATP/allocation, commission, TigerBeetle posting, H6Q coverage, FX |
| 05 | **ACCESS_CONTROL** | RBAC + scope + data-layer projection, permission resolution algorithm, audit |
| 06 | **API** | REST contracts per module (paths, methods, request/response, errors) |
| 07 | **BUILD_PLAN** | Phased milestones, per-feature acceptance criteria, test strategy |
| 08 | **APP_SCREENS** | Flutter companion app — screen-by-screen spec (for Claude Design) |
| 09 | **RETURNS** *(planned deep-dive)* | First-class returns/RMA — types (full unit, part-only, DOA, warranty replacement…), lifecycle, restock/refurb/scrap, ledger reversal, commission claw |
| 10 | **REMAINING_TO_PLAN** | Backlog — deep-dives, parked functional areas, flow gaps, NFR/ops, and open decisions still to plan |
| 14 | **FINANCIAL_INTEGRITY** | Typed money (Squants) & pixel-perfect math, UTC-instant/period time model & timezone reslicing, US GAAP treatments, SOX/ICFR controls + PCAOB-grade auditability, and the in-product Auditability Center |

> Documents 02–05 are the load-bearing ones and are written deepest. 06/07 give the contract surface and the build sequence. Module-level deep dives that extend this pack (CRM, H6Q, Intercompany/Tax, Integrations, Migration) follow the same template and slot in as 08+.

## Conventions

- **DB:** PostgreSQL 16. Types are literal: `UUID`, `TEXT`, `NUMERIC(p,s)`, `INTEGER`, `BIGINT`, `BOOLEAN`, `TIMESTAMPTZ`, `JSONB`, `CITEXT` (case-insensitive text, for emails/codes). Money is **`NUMERIC(18,4)`** plus a `currency CHAR(3)` column — never floats. All tables carry `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`. Soft-delete via `deleted_at TIMESTAMPTZ NULL` on mutable business records; transactional records are never hard-deleted.
- **Scoping columns:** every transactional/business table carries `entity_id UUID`, and where relevant `market_id UUID`, `channel_id UUID` — the axes access scoping and pricing key on.
- **Money truth:** financial movements are posted to **TigerBeetle** (doc 04 §Ledger); Postgres holds the relational/searchable projection. Postgres rows reference TigerBeetle via `tb_transfer_id NUMERIC(39,0)` (128-bit) where applicable.
- **Typed money & pixel-perfect math:** a currency-tagged `Money` type (BigDecimal, no floats, no implicit cross-currency), explicit `RoundingPolicy` per boundary/jurisdiction, conserving (largest-remainder) allocation, provenanced FX; **Squants** for physical quantities (kWh/kW). Full spec: **doc 14**.
- **Time & periods:** all timestamps are **UTC instants** (`TIMESTAMPTZ`); fiscal **period assignment is a projection** of `occurred_at AT TIME ZONE :reporting_tz` over a fiscal calendar, never baked irreversibly into rows — so timezone reslicing is a re-projection, not a migration (doc 14 §2). Group consolidation/presentation = **USD**.
- **Controls from day one:** US GAAP treatments, SOX/ICFR controls (segregation of duties, immutable audit, reconciliation, period lock) and PCAOB-grade re-performable auditability are foundational, not retrofitted (doc 14).
- **Eventing:** state changes are emitted via a **transactional outbox** (doc 03). Consumers are idempotent on `event_id`.
- **Typed core + governed-flexible edge:** financial/operational truth (money, tax, quantities, IDs, status, anything driving ledger/allocation/pricing/commission) lives in **typed columns**. Descriptive/segmentation/workflow data evolves via a **governed property registry + `attributes` JSONB** bag, validated against the registry — flexible but not freeform (doc 02 §M). Avro governs the stable event spine; custom attributes ride as a JSON/`map` field so they evolve without Avro schema bumps.
- **Backend:** Scala 2.13 / http4s / doobie / cats-effect / Flyway (matches the Athena + Ghost Busters house stack). Back-office frontend React/TS; **companion app (mobile/tablet/web) is Flutter — single codebase, nothing else**. Auth: Keycloak (OIDC). Backbone: Pulsar (consolidating today's SQS+Pulsar). Ledger: TigerBeetle. Accounting consumer: Xero. Retail payments: Stripe. 3PL: Rhenus + carriers.
- **IDs in API:** UUIDs as strings. Timestamps ISO-8601 UTC. All money fields are objects `{ "amount": "587.5000", "currency": "GBP" }`.
- **Auth on API:** every endpoint requires an authenticated principal (Keycloak JWT); authorisation is enforced by the policy layer in doc 05 (object + action + section + scope + data-layer). Reseller API uses a scoped service JWT.

## How to use with Claude Code

Build in the order in doc 07. Do **not** start feature modules before the spine (doc 07 Phase 1: event backbone + access control + ledger + catalogue/pricing + order capture) is implemented and tested — everything downstream consumes it. Each feature's "Acceptance" block is its definition of done; turn those into the test suite first.

## Status

This delivery contains 00–07. It specifies the full spine and the distinctive subsystems (ADLP, commission, ledger, traceability, H6Q) to build depth. Items still marked **OPEN** inside documents are the genuine product decisions listed in the requirements set (e.g. exact commission basis, scenario cuts, Luxshare billing currency) — they are flagged inline where they affect implementation and collected in 07 §Decisions.
