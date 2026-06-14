# Conduit — Engineering Guide (house conventions + build plan)

Conduit is Hypervolt's event-driven master system of record (CRM + orders + pricing/ADLP +
commission + inventory/traceability + double-entry ledger + forecasting). Full requirements live
in [`spec/`](./spec) (read `spec/00_README.md` first). **This file is the implementation contract:**
the house conventions Conduit must conform to, distilled from the sibling repos in
`~/projects/hypervolt`, plus the verifiable milestone plan.

> Golden rule: Conduit is a **citizen of the existing estate**, not a greenfield app. When in doubt,
> copy Athena. Do not invent parallel mechanisms.

---

## 1. Where the house conventions come from (reference repos)

| Concern | Model on | Path |
|---|---|---|
| **Scala service (canonical template)** | **Athena** — has http4s/tapir, doobie, Pulsar+avro4s, **TigerBeetle**, Keycloak-JWKS auth, Nix, Consul, weaver tests all in one | `~/projects/hypervolt/athena` |
| Pulsar/Avro consumer patterns | ghost-busters (and Athena) | `~/projects/hypervolt/ghost-busters` |
| Scala style rules (MUST follow) | hypervolt-backend `CLAUDE.md` | `~/projects/hypervolt/hypervolt-backend/CLAUDE.md` |
| **Frontend (Conduit desk)** | **hyperstore** (Vite + React + StyleX), mirrored by `hyperadmin/ui` | `~/projects/hypervolt/hyperstore` |
| Infra (Terraform/Nix/Consul/CI) | Athena `terraform/`, `shell.nix`, `.gitlab-ci.yml`; estate Terraform modules | `~/projects/hypervolt/athena`, `git@gitlab.com:hypervolt/terraform.git` |
| Local dev stack | the estate compose file | `~/projects/hypervolt/docker-compose.checkout.yml` |

Repos are **separate GitLab repos** under `git@gitlab.com:hypervolt/<name>.git`, **not** a monorepo.
Services are standalone (no cross-service artifact deps); intra-repo modules wire via sbt `.dependsOn`.
There is **no internal artifact registry** for Scala — copy patterns, don't import.

---

## 2. Backend stack & exact versions (pinned to Athena)

Scala **2.13.16**, sbt **1.12.9**, JDK **21** LTS (temurin; `eclipse-temurin:21-jre` runtime). JDK 19 was
non-LTS and is removed from nixpkgs at EOL — the dev shell + CI build on `temurin-bin-21`.

```
http4s 0.23.26 (ember)      tapir 1.10.4 (+ json-circe, http4s-server, swagger-ui-bundle,
circe 0.14.3                            opentelemetry-metrics, cats)
cats 2.10.0 / cats-effect 3.5.4         doobie 1.0.0-RC5 (core/postgres/hikari)
postgresql 42.7.7           flyway 11.12.0 (+ flyway-database-postgresql)
log4cats 2.7.0 / logback 1.4.5          typesafe config 1.4.1
pulsar-client + pulsar-client-admin 4.0.4   avro4s 4.1.2 / avro 1.12.0
tigerbeetle-java 0.16.46    squants 1.6.0 (physical quantities)
auth0 jwks-rsa 0.22.1 + java-jwt 4.4.0  (exclude jackson-databind transitive)
opentelemetry-sdk 1.35.0 + exporter-prometheus 1.35.0-alpha
weaver 0.8.4 (weaver-cats + weaver-scalacheck)  testcontainers-scala 0.41.0
   testcontainers: postgresql 1.20.0, pulsar 1.20.4, consul 1.18.3
better-monadic-for 0.3.1 compiler plugin
```

`build.sbt` shape: a top-level `lazy val Versions = new { ... }`, `sharedSettings`, version from
`git.gitHeadCommit.value.map(_.take(8))`. Modules (mirror Athena):

```
conduit/                  root aggregate
├── domain/               domain logic, services, repositories (Athena's "orderProcessing")
├── api/                  http4s/tapir server (:8080), Flyway on startup; JavaServerAppPackaging
├── api-it/               integration tests (testcontainers: postgres/pulsar/consul)
├── consumer/             Pulsar consumers + outbox relay + background jobs; JavaAppPackaging
└── scripting/            one-off scripts (e.g. TigerBeetle id gen — reuse Athena's helpers)
```

Package root: `com.hypervolt.conduit.*`. Ports: API **8080**, health/admin **9990** (`GET /health` → `"OK"`),
Prometheus **9464** (env `PROMETHEUS_PORT`). Config via typesafe-config HOCON + `${ENV_VAR}` overrides,
under a `hypervolt { env = ${HYPERVOLT_ENV} ... }` root.

### Bootstrap shape (Athena `api/.../Main.scala`)
`IOApp.Simple`; a `Resources` for-resource (logger → `EnvironmentConfig.load` → `FlywayInit.run` →
ember client → Consul client + MachineId → OpenTelemetry SDK + `MetricsBuilder` → `Transactor.build`
(HikariCP) → DAOs → services); then `resources.use { ... EmberServerBuilder on 0.0.0.0:8080 with the
tapir router, health server on :9990 }`. Note: per house style **prefer flatMap chains over for-comprehensions**
in domain code, but the resource-acquisition block in `Main` may stay a `for` (it's the idiomatic exception).

### Persistence (doobie)
`Transactor.build[F]` wraps HikariCP, sets `application_name`. Repos use `sql"...".query[T]`/`.update`,
custom `Get`/`Put` for domain enums, `.transact(xa)`, wrapped in `metricsBuilder.time("name", ...)`.
**Flyway migrations**: `api/src/main/resources/db/migration/`, named `V{maj}_{min}_{patch}__desc.sql`.

### Auth (Keycloak via JWKS)
Keycloak is the IdP; tokens verified with auth0 `jwks-rsa` + `java-jwt` against Keycloak's JWKS endpoint
(`…/realms/<realm>/protocol/openid-connect/certs`), validating subject + audience. Tapir
`SecureEndpoint` for protected routes. The doc-05 **policy layer** (object/action/section/scope/data-layer)
sits *after* JWT verification and is Conduit-specific (net-new — no house equivalent).

---

## 3. Eventing — replicate, then extend

Athena & ghost-busters define **avro4s case-class schemas** (`SchemaFor.gen`/`Encoder.gen`/`Decoder.gen`)
and a custom `AvroPulsarSchema[T]` implementing Pulsar's `Schema[T]` with schema-version caching
(`supportSchemaVersioning = true`, binary `AvroOutputStream`/`AvroInputStream`). Copy `AvroPulsarSchema`
and `PulsarUtils` from Athena verbatim (they're already copied between repos). Consumers: `SubscriptionType.Shared`,
`SubscriptionInitialPosition.Earliest`, batch receive, ack-good/nack-bad, `MultiplierRedeliveryBackoff` (10s→1h).

**The known UFE topic** (for activation ingest, M8): topic `athena-placement-versioned`, record
`AthenaPlacementVersionedRecord(device: Option[String], placementId: String, version: Int)`. Conduit
subscribes with its **own** subscription name `conduit-placement-versioned-subscription-1`.

### What Conduit ADDS (net-new vs. the house — these are the architectural fixes the spec mandates)
1. **Transactional outbox** (`outbox_event` table, doc 02 §L) + an **outbox relay** fiber in `consumer/`.
   Neither Athena nor GB has this — they dual-write and suffer drift/staleness (the pain spec doc 01 §3
   calls out). Business row + outbox row commit in **one Postgres tx**; relay publishes in `partition_key`
   order, sets `published_at`. At-least-once; every consumer **idempotent on `event_id`**.
2. **Event envelope** (doc 03 §1) wrapping a typed Avro `payload`; topics per aggregate
   (`conduit.orders`, `conduit.inventory`, `conduit.activations`, `conduit.pricing`, `conduit.crm`,
   `conduit.commission`, `conduit.ledger`, `conduit.forecast`, `conduit.purchasing`).
3. **`BACKWARD` schema-compat CI gate** (`sbt schemaCheck`) — doc 03 §2.
4. **Custom attributes ride as an Avro `map<string,string>`**, never schema fields (doc 02 §M / 03 §2).

---

## 4. Financial integrity — build fresh per doc 14 (Phase 1, non-negotiable)

Athena's `Price.scala`/`CurrencyCode.scala` (BigDecimal + `HALF_EVEN` + typed `IncludingVat`/`ExcludingVat`
wrappers, GBP/EUR/AUD only) is a **style reference, not sufficient**. Conduit builds the doc-14 core:

- **`Money(amount: BigDecimal, currency: Currency)`** — no `Double`/`Float` anywhere (CI lint rejects it);
  cross-currency arithmetic is a **type/compile error**; only `convert(rate)` crosses, recording rate+source+rounding.
- **`RoundingPolicy`** — explicit per boundary (line/invoice/FX/posting) and per `tax_regime`; default HALF_UP.
- **Conserving `allocate(total, weights)`** (largest-remainder) — `Σ parts == total` always (ScalaCheck property).
- **Squants** for kWh/kW/A/m physical quantities (compile-time dimension safety).
- **TigerBeetle** = u128 integer minor units, **one ledger per currency**; `Money` ↔ TB integer 1:1;
  transfer `id` **deterministic from `event_id`(+leg)** so redelivery is a no-op. Reuse Athena's
  `GenerateTigerbeetleId`/`UuidToUInt128` scripting helpers.
- **UTC instants stored; fiscal period is a re-projection** (`occurred_at AT TIME ZONE :reporting_tz`),
  never baked into rows. `accounting_period(status open|closed|locked)` — posting to `locked` rejected at the ledger boundary.

These ship in the **M1 foundation** with a ScalaCheck property suite (doc 14 §5.4), not retrofitted.

---

## 5. Frontend — Conduit desk (back-office React/TS)

Stack = **hyperstore**, replicated exactly:
- **Vite 8.0.3, React 19.0.0, TypeScript 5.8.3**, package manager **yarn**, dev server `vite --port <n>`.
- **StyleX 0.18.2** — `@stylexjs/stylex` + dev `@stylexjs/babel-plugin` + build `@stylexjs/rollup-plugin`
  (copy hyperstore's dual-plugin `vite.config.ts` exactly — it's load-bearing for CSS extraction).
  Authoring: `const styles = stylex.create({...})` + `<div {...stylex.props(styles.x)}>`. Tokens via
  `stylex.defineVars` in `styles/tokens/colors.stylex.ts`; themes via `stylex.createTheme`
  (`styles/themes/{dark,pro}.stylex.ts`). **Hypervolt accent `#962DFF`**, dark-mode first-class.
- React Router v6 (locale-prefixed routes), **React Query v5** for server state, **React Hook Form** for forms,
  axios for HTTP. **Generate the TS API client from Conduit's OpenAPI** (tapir emits OpenAPI) — this is an
  improvement over hyperstore's hand-written types; keep types generated, single source of truth.
- i18n: **i18next + react-i18next**, ARB/JSON namespaces per locale (Conduit needs the full 15-locale set incl. CJK + Thai).
- **Data-layer awareness is mandatory** (doc 05/08): money widgets accept "hidden" and **collapse** — never render zeros/placeholders for a layer the user lacks.
- Tests: **Vitest 3.2.4** (unit, `src/**/__tests__`, jsdom) + **Playwright 1.55.0** (e2e, `src/__tests__/browser/*.spec.ts`,
  baseURL the dev server, chromium/firefox/webkit). Chrome DevTools MCP available for manual UI verification.

> **RESOLVED (companion app): full Flutter, no React.** The field companion app is **100% Flutter**, built the
> Hypervolt way — it joins the estate's Flutter Melos workspace (`~/projects/hypervolt/ux`) and reuses
> `hypervolt_ui_kit` (tokens/theme/components/responsive utils), `flutter_bloc`+RxDart, `go_router`, `dio`,
> Keycloak auth, Hive — with a **Conduit purple (`#962DFF`) theme variant** over the kit's machinery. iOS/iPad-first
> (width-class adaptive: bottom nav → rail → two-pane master-detail; Slide-Over/Split-View safe; Dynamic Type;
> biometrics; camera scan), offline-tolerant, data-layer-aware, server-authoritative. The **back-office desk stays
> React/StyleX** (doc 20) — desk and companion share brand tokens, not code. Full design+architecture spec:
> **`spec/23`**; screen-by-screen functional spec: `spec/08`.

---

## 6. Infra & local dev (conform to Athena)

- **Nix + npins**: `shell.nix` consuming `npins/sources.json` (nixpkgs-unstable channel pin); provides sbt/jdk/awscli.
  CI and local dev use the same shell. Mirror `athena/shell.nix`.
- **Terraform** (`git@gitlab.com:hypervolt/terraform.git` modules): `nixos-bootstrap` + `ec2-autoscaling-group`
  for the API; `rds-postgres` (PG 16, Multi-AZ in prod, eu-west-1); S3 state `eu-west-1.tf.hypervolt`,
  key `backend/conduit/terraform.tfstate`, account `242724708940`, role `…/rbac/<env>-conduit-operator`.
  Secrets in Secrets Manager (`<env>/conduit/rds-db-credentials/*`, `<env>/keycloak-configuration/conduit-api/*`).
  Pulsar topics/namespaces are **not** in Terraform today (provisioned via admin API) — Conduit should script
  topic creation at deploy (improvement; record if we add it to TF).
- **Consul**: service registers (`services.consul.services.conduit`, health check → :9990 `/health`); discovers
  deps via `*.service.consul` (`pulsar.service.consul:6650`, etc.). Config via env/HOCON + Secrets Manager (KV used minimally).
- **Docker**: `eclipse-temurin:21-jre` runtime, `sbt api/stage` builds the launcher first, expose 8080/9990/9464.
- **Local dev compose** (new `docker-compose.yml` in this repo, modeled on `docker-compose.checkout.yml`):
  postgres:16, apachepulsar/pulsar:4.0.1 standalone, hashicorp/consul, **ghcr.io/tigerbeetle/tigerbeetle:0.16.46**
  (reuse Athena's tigerbeetle entrypoint), keycloak (for auth dev). Use non-colliding host ports (Athena occupies the defaults).
- **CI** (`.gitlab-ci.yml`, nix-shell runners `x86_64-nix-24.11[-dind]`): stages **lint** (`sbt scalafmtCheck;Test/scalafmtCheck`
  + **`schemaCheck`** Avro gate + the **no-float** money lint), **compile/test**, **package** (`Universal/packageXzTarball`),
  **publish** (S3 + deploy-versions, protected branches). Frontend: `yarn test` + `yarn test:browser`.

---

## 7. House Scala style (from hypervolt-backend/CLAUDE.md — enforced)

- Prefer explicit **`flatMap`/`map` chains over for-comprehensions** (clearer types). (Exception: `Main`'s resource block.)
- **No single-use vals**; inline or point-free (`.flatMap(getB)` not `.flatMap(a => getB(a))`).
- **Parentheses, not braces**, for single-expression lambdas.
- Error handling: pick the right tool — `recover` / `recoverWith` / `adaptError` over a catch-all `handleErrorWith`.
- **No nested functions / no nested pattern matches** — flatten (`case Some(Success(y))`).
- **Comments are a code smell** — express it in names. Only comment intentionally non-idiomatic workarounds.
- **Small, focused tests** with descriptive names (weaver / `AnyWordSpec`-style nesting).
- **Imports at the top of the file**, never fully-qualified types inline (their "most common mistake").
- Compiler flags & scalafmt: copy `project/ProjectDefaults.scala` (incl. `-Xsource:3`) and `.scalafmt.conf` from the estate.

---

## 8. Spec reconciliations & open decisions (resolve as flagged)

1. **`party` vs `company` naming** — doc 02 §C supersedes `company`/`individual_customer` with one `party` table,
   but later tables/endpoints still say `company_id`/`branch_company_id` and doc 06 mixes `/parties` and `/companies`.
   **Canonical = `party`.** Generate schema and the OpenAPI/TS client on `party`; treat `company_id` in the spec as
   "a `party` of an organization type." Fix endpoints to `/parties`. (Do this before M4.)
2. **Field → data-layer map (decision 14b)** — doc 05 lists layers + seed roles but not the per-field
   `field_layer_map`. Must be enumerated before **M2** acceptance tests. Produce it as the first M2 artifact.
3. **Money** — build the doc-14 typed `Money`, don't reuse Athena's GBP/EUR/AUD `Price` (style ref only). §4 above.
4. **Outbox / schema-registry CI gate / envelope** — net-new vs house; §3 above.
5. **Companion app Flutter vs React** — OPEN, §5 above; non-blocking until M14.
6. Stale spec text to ignore: doc 04 §FX "presentation currency likely USD — confirm" (USD **is** confirmed everywhere else).
7. Year-1 seed = **UK only** (GBP, VAT 20, en), buying from Luxshare-UK; the 23-market table is the configured roadmap.
8. **Pricing is contract/tier-bound — nobody types a price** (doc 24). Every price is a governed **tier** inside a
   `price_agreement` (validity window, one-or-more-customer scope, volume bands); the order line binds to a tier and
   the server rejects any non-tier price. The ADLP **"exception" is a governed price-tier *request*** (maker-checker →
   admin/CEO), not an ad-hoc number on an order. **Volume tiers** (per-order / cumulative-prospective /
   cumulative-**retrospective**) are first-class, and the retrospective case is **ASC-606 variable consideration**:
   the full contract structure (validity, bands, rebates) **must propagate to revenue/AR/commission/ledger** (rebate
   accrual + true-up). Spec-only today → new milestone **M-Pricing** (evolves `price_rule`; doc 24 §10).

---

## 9. The build "step function" — verifiable milestones

Build in `spec/07` order. **A milestone is done only when its acceptance tests (from spec/07) pass** AND
(for UI) a Playwright run is green. Each milestone = backend impl + unit tests + integration tests
(testcontainers) + (where UI) Vitest + Playwright. Do **not** start a feature module before the Phase-1 spine is green.

Track progress with the Task tools. Per-milestone definition-of-done lives in `spec/07`; turn each "Accept" block
into the test suite **first** (test-first).

**Phase 1 — Spine (no feature work until green)**
- **M0 Scaffold** *(pre-spine, this repo has none yet)*: sbt multi-module build, `project/`, `shell.nix`+`npins`,
  `docker-compose.yml` (pg/pulsar/tigerbeetle/consul/keycloak), `.gitlab-ci.yml`, `application.conf`, `Main`/health
  skeleton, scalafmt. **Verify:** `sbt compile` green; `docker compose up` healthy; `GET /health` → `OK`.
- **M1 Foundations**: migrations (doc 02), `outbox_event` + relay + Pulsar topics + Avro envelope + `AvroPulsarSchema`;
  TigerBeetle ledgers per currency + Ledger-poster skeleton; **financial core** (`Money`/`Currency`/`RoundingPolicy`/
  conserving `allocate`/Squants), `exchange_rate`, UTC-instant + period-projection helper, `accounting_period` +
  lock, `control`/`reconciliation` tables; **ScalaCheck property suite**. **Verify (spec/07 M1):** atomic write+outbox;
  relay publishes in `partition_key` order; consumer dedupes redelivery; deterministic TB transfer id; no-float CI rule;
  `allocate` conservation property; `usd + eur` fails to compile; same events bucket to different months under two TZs;
  posting to `locked` period rejected.
- **M2 Access control**: policy layer, scope filter, data-layer projection, preset roles, permission-builder API,
  **`field_layer_map`** (decision 14b first). **Verify:** UK-wholesale user sees only UK-wholesale rows everywhere;
  Deal Desk can't read `price_rule.inter_entity` (absent from payload); revocation denies next request; all server-side.
- **M3 Catalogue + ADLP pricing**: families/variants, `price_rule`, `/pricing/quote`. **Verify:** governed/audited
  price change (not a migration); quote returns correct ex/inc-VAT + volume break + `adlp_category`; inter-entity layer-walled.
- **M4 CRM(parties) + Order capture**: party/contact/deal/pipeline; order placement → `OrderPlaced`; tranches/call-off;
  permission-gated pre-dispatch amend; ledger-commitment consumer; audit projection. **Verify:** compliant 3-line order
  places <60s keyboard-only; exception line holds `pending_ceo`; credit block; 500=2×250 tranches independently
  fulfillable; amend pre-dispatch re-prices/re-allocates, post-cutoff amend → 409; `OrderPlaced` fans out; audit reconstructs.

**Phase 2 — Trading/supply/traceability**: M5 Commission · M6 Inventory+ATP+dispatch+carriers · M7 Batch/landed-cost/
serial genealogy · M8 Activation ingest + warranty provision · M9 Purchasing/receiving + stock ops (maker-checker) ·
M9b Returns/RMA *(needs spec/09, not yet written)* · M10 Deal Desk + migration/cutover.

**Phase 3 — Forecasting/intercompany/ERP/connect + UI**: M11 H6Q · M12 Intercompany+TP+tax/customs+hedges ·
M13 ERP/GL+P&L+Xero · M13b Period close+reconciliation+Auditability Center · M14 Companion app + Horizons + reporting + HubSpot.

**Launch-blockers tracked but not yet specced** (`spec/10`): migration runbook, NFR/security(GDPR DSAR)/ops-DR docs,
back-office desk screen-spec, returns deep-dive (09). *(Done since: document generation — M13-Docs; the **tax/customs
engine** — M13-Tax, doc 16: effective-dated multi-level rate-table `TaxProvider` with the external Avalara/TaxJar/
Stripe-Tax path a `tax_routing` row + adapter away.)* None block starting the spine; fold in per their milestone.

---

## 10. Quick commands (to be wired in M0)

```
sbt compile / test                 # unit
sbt api-it/test                    # integration (testcontainers)
sbt schemaCheck                    # Avro BACKWARD-compat gate
sbt fmt                            # scalafmt
docker compose up -d               # local pg/pulsar/tigerbeetle/consul/keycloak
# frontend (conduit-desk/):
yarn start                         # vite dev server
yarn test                          # vitest unit
yarn test:browser                  # playwright e2e
```
