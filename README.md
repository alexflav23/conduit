# Conduit

Hypervolt's event-driven **master system of record**: CRM + orders + contract pricing (ADLP) + commission +
inventory/traceability + double-entry ledger (TigerBeetle) + the self-improving revenue **forecast engine**.

- **The spec** lives in [`spec/`](./spec) — read `spec/00_README.md` first.
- **The engineering contract** (house conventions, milestones) is [`CLAUDE.md`](./CLAUDE.md).
- **Design handoff**: `spec/27` (page-by-page feature map + live screenshots) → `spec/22` → `spec/20`.

Everything below is reproducible on a fresh machine: the repo carries its own data
(git-versioned NDJSON snapshots in [`ingest/`](./ingest)) and an idempotent loader — **checkout → run → seeded**.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 19 (temurin) | or use `shell.nix` (nix + npins pins sbt/jdk) |
| sbt | 1.12.x | |
| Docker | any recent | compose v2 |
| Node + yarn | Node ≥20, yarn 1.x | desk only |
| Python 3 | ≥3.9 | feed scrapers only |

## Quick start (the whole stack, seeded)

```sh
git clone https://github.com/alexflav23/conduit.git && cd conduit
./run-local.sh          # sbt stage + docker compose up: postgres/pulsar/tigerbeetle/consul/keycloak/api/consumer
```

Up when: `curl localhost:9990/health` → `OK`. The API loads every `ingest/**/*.ndjson` snapshot at boot
(deterministic + idempotent — re-boots converge to the same state). Optional demo fixtures (a CEO user, a
seasonal demo account): `psql -h localhost -p 5532 -U conduit -d conduit -f scripts/demo-seed.sql` (pw `conduit`).

**The desk (back-office UI):**

```sh
cd conduit-desk && yarn && yarn start    # vite on :3002, proxies /api → :8080
```

Sign in with a dev token (`dev:demo-ceo` after the demo seed; `dev:agent-e2e` after the e2e seed) — the dev
door only works against non-prod backends. For Google Workspace sign-in set `VITE_GOOGLE_CLIENT_ID` (see
`terraform/README.md` for the one-time Google Cloud Console setup).

### Ports (compose; chosen to not collide with athena's defaults)

| 8080 API · 9990 health · 9464 API metrics · 9466 consumer metrics | 5532 postgres · 6650/8085 pulsar · 3022 tigerbeetle · 8500 consul · 8090 keycloak |
|---|---|

## Tests

```sh
sbt test                 # unit (domain): money/allocate properties, models, selector, policy engine…
sbt apiIt/test           # integration (testcontainers: postgres/pulsar/consul — needs Docker)
sbt schemaCheck          # Avro BACKWARD-compat gate
sbt fmt                  # scalafmt
cd conduit-desk
yarn build               # type-checks + builds the desk
yarn test                # vitest unit layer (state machine / api contract / session) — jsdom, no backend
./run-e2e.sh             # FULL desk e2e: boots the API on the LOCAL postgres :5432 (not compose!), seeds
                         # e2e/seed.sql, runs all Playwright specs, tears down. Needs a local postgres
                         # with a `conduit` db. NOTE: frees port 8080 (kills the compose API) — re-run
                         # `docker compose up -d` afterwards.
node scripts/capture-screens.mjs   # re-captures spec/design-assets/desk/*.png against the live API (:3002 + :8080)
```

## The forecast engine (doc 26) — operating it

The engine is pure Scala inside this repo (no Python/ML infra). Postgres is the store; everything below
talks to the **compose** DB (`localhost:5532`).

```sh
# full refit: fit+score+materialize every origin (calendar-derived — quarter-close auto-extends), ~20 min
sbt "scripting/runMain com.hypervolt.conduit.scripting.RealBacktest"
# selection-only refit over the existing evidence ledger (~75 s) — for iterating on selector logic
sbt "scripting/runMain com.hypervolt.conduit.scripting.Rematerialize"
# the verification harness: 8-quarter WAPE matrix + nowcast/forward (THE means are the accept/reject metric)
sbt "scripting/runMain com.hypervolt.conduit.scripting.PolicyTournamentReport"
# publish the live rows the desk H6Q board reads (forecast_entry, append-only supersession)
sbt "scripting/runMain com.hypervolt.conduit.scripting.LivePublish"
# the board artifact: /tmp/conduit-comparables.html (business actuals, ASP panel, P80/P50/P20, pass-through β)
sbt "scripting/runMain com.hypervolt.conduit.scripting.ComparablesHtml"
```

**House rule (memorize):** every model/selector change is judged by the 8-quarter backtest means from
`PolicyTournamentReport` — improve or revert (including the reverted model's `model_accuracy` rows).
Seven falsified ideas are documented in `spec/26` + the Forecast Engine desk tab; don't retry them.

### Refreshing the data (the dual-run feeds)

```sh
scripts/refresh-feeds.sh   # activations delta + MRPeasy incremental + reload + rescore + republish + git commit
```

Needs (none of these live in this repo — they are estate credentials):
- **prod tunnel** for activations: `ssh -fNL 15432:prod-athena.ct4y8vmbn3ed.eu-west-1.rds.amazonaws.com:5432
  flav@bastion.prod.euw1.aws.hypervolt.co.uk` (skips gracefully when down)
- `~/projects/hypervolt/athena/.env` (MRPeasy API keys) and `~/projects/hypervolt/ghost-busters/.env`
  (Athena DB creds) — sourced by the script.
- MRPeasy scraping is **incremental** (tail + 3,000-row recheck window); `MRPEASY_FULL=1` forces a full re-walk.

## Deploying (staging/prod on AWS, the athena pattern)

Everything is written and `fmt`-clean under [`terraform/`](./terraform) — **read `terraform/README.md`**: the
apply order (`roles-global → rds → records → tigerbeetle → api → consumer`), the SSM/Secrets contract, the
one-time **Google Workspace OAuth setup** (Internal consent screen; the client id feeds
`/<env>/conduit/GOOGLE_OAUTH_CLIENT_ID` + `VITE_GOOGLE_CLIENT_ID`), and `scripts/provision-pulsar.sh` for
topics/subscriptions at deploy. `terraform init` needs the estate GitLab SSH key + AWS role. CI packaging and
publish live in `.gitlab-ci.yml` + `scripts/publish*`.

## Repo layout

| Path | What |
|---|---|
| `domain/` | All domain logic: money/ledger core, pricing, inventory, forecast engine, access control |
| `api/` | http4s/tapir server (:8080), Flyway migrations (`api/src/main/resources/db/migration/`) |
| `consumer/` | Pulsar consumers + outbox relay + background fibers (supervised independently) |
| `api-it/` | Integration suites (testcontainers) |
| `scripting/` | Operational entrypoints (backtests, reports, publishers) |
| `conduit-desk/` | The React/StyleX back-office (12 tabs incl. the Forecast Engine explainer) |
| `ingest/` | Git-versioned NDJSON data snapshots (MRPeasy, activations, Stripe, SMMT, H6Q) — the data ships with the repo |
| `spec/` | The full requirements + design corpus (00–27) |
| `terraform/` | The complete AWS deploy (athena pattern) |

Git remote: `github` → `https://github.com/alexflav23/conduit.git`; push every green slice to **both**
`m0-m1-foundations` and `main`.
