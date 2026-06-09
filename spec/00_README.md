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
| 09 | **RETURNS** | First-class returns/RMA — types (full unit, part-only, DOA, warranty replacement, goodwill…), lifecycle state machine, restock/refurb/scrap routing, ledger reversal at batch cost, commission claw, replacement order (supports M9b) |
| 10 | **REMAINING_TO_PLAN** | Backlog ledger — deep-dives, parked areas, flow gaps, NFR/ops, open decisions (now mostly discharged into docs 09/11–13/15–21) |
| 11 | **CRM** | Party/role deep-dive — pipelines/stages, deal→order conversion, account-history projection, ownership, party merge/dedupe, promote-to-billable policy, consignment-at-branch (supports M4-depth/M11) |
| 12 | **H6Q** | Forecasting at full depth — weekly cycle engine, versioned submissions, bottom-up rollup + dual aggregation (branch/agent), scenario toggles, Hyperview source, accuracy, board layout (supports M11) |
| 13 | **INTERCOMPANY_TAX** | Procurement chain (operating ← Singapore ← Luxshare; config), transfer-pricing methods + documentation off batch cost, paired linked legs, elimination, import VAT/duty (supports M12) |
| 14 | **FINANCIAL_INTEGRITY** | Typed money (Squants) & pixel-perfect math, UTC-instant/period time model & timezone reslicing, US GAAP treatments, SOX/ICFR controls + PCAOB-grade auditability, and the in-product Auditability Center |
| 15 | **DELIVERY_MILESTONES** | The living build register — status of every milestone, dependencies, backing doc, verifiable sub-steps, and acceptance gate (pairs with 07) |
| 16 | **TAX_CUSTOMS** | Tax & customs engine — pluggable `TaxProvider`/`TaxQuote`, VAT determination, US destination sales tax + Canada GST/HST/PST via Avalara/TaxJar/Stripe Tax, HS codes, Intrastat (supports M12/M13) |
| 17 | **DOCUMENT_GENERATION** | Invoices, credit notes, proformas, packing lists, commercial invoices, statements — per-locale/jurisdiction templates, gapless numbering, PDF, invoice-on-delivery (supports M13) |
| 18 | **MIGRATION_CUTOVER** | MRPeasy/Ghost Busters/Athena → Conduit: source→target mapping, opening balances into TigerBeetle, idempotent replay-path backfill, dual-run reconciliation, cutover stock-count validation, phased runbook + rollback (supports M10 — biggest go-live risk) |
| 19 | **NFR_SECURITY_OPS** | NFR (SLAs/RPO-RTO/scale/retention), security (secrets, encryption, GDPR DSAR crypto-shred, STRIDE threat model, SOX controls index) and ops/DR (alerting, DLQ-replay + projection-rebuild runbooks, backup/restore, CI migration-safety) — P1 launch-blockers |
| 20 | **BACKOFFICE_DESK** | React/TS desk — screen-by-screen (pricing governance/ADLP, permission builder, Deal Desk + CEO approval, full H6Q board, finance/ledger, supply planning, admin, Auditability Center) (Phase 2–3 UI) |
| 21 | **PLATFORM_SERVICES** | Cross-cutting services — notifications, search, reporting/exports + the Horizons units→revenue→COGS→GP feed, and localization/i18n across 15 locales (supports M14) |
| 22 | **DESIGN_HANDOFF** | The single front-door brief for Claude Design — consolidates the design language, authors the design-system/token + component-kit contract the code only stubs, inventories built-vs-to-design, fixes the UX invariants from the access/finance model, and defines the Figma↔StyleX↔Code-Connect workflow, deliverables and acceptance (supports the UI design pass / #9) |
| 23 | **COMPANION_APP_DESIGN** | The Flutter companion-app design & architecture spec (decision: full Flutter, no React) — built the Hypervolt way on `hypervolt_ui_kit` (`~/projects/hypervolt/ux`) with a Conduit purple theme variant; iOS/iPad-first width-class adaptive layouts, offline/sync + data-layer + server-authoritative contracts, the component kit, and the backend edge-features the field app needs (idempotency keys, push, missing REST surfaces). Pairs with doc 08's screen spec (supports M14) |
| 24 | **CONTRACT_PRICING** | Contract & volume-tiered pricing (ADLP re-stated): nobody types a price — every price is a governed tier in a `price_agreement` (validity, multi-customer scope, volume bands); the "exception" is a governed price-tier request; per-order vs cumulative (prospective/retrospective) tiers, with retrospective volume rebates propagated through revenue/AR/commission/ledger as ASC-606 variable consideration. Spec-only; new milestone M-Pricing |

> Documents 02–05 are the load-bearing storage/logic/access layer and are written deepest; 06/07 give the contract surface and build sequence; **15** is the living delivery register. The deep-dives (09, 11–13, 16–21) extend the spine on the same template and slot in per their milestone. **22** is the design handoff pack (the front door for the UI design pass); **23** is the Flutter companion-app design spec (pairs with **08**'s screens).

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

**Spec: complete across 00–21** — the spine (00–08), the financial-integrity core (14), all deep-dives
(09, 11–13), and the launch-blocker docs (16–21) are written to build grade; the backlog (10) is largely
discharged into them. **Build: M0–M5 implemented and verified** (64 tests green — unit/property + Postgres &
TigerBeetle integration + Playwright e2e). Live milestone status, dependencies and the verifiable
per-milestone step breakdown live in **doc 15**. Items still marked **OPEN** inside documents are genuine
product/finance decisions (collected in 07 §Decisions); none change the architecture.
