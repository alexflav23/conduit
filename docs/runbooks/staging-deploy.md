# Runbook — Staging deploy (`conduit.staging.hypervolt.co.uk`)

The end-to-end first-bring-up of Conduit on AWS staging. Mirrors the Athena deploy. All Terraform roots are
standalone with S3-backed state; environments are Terraform **workspaces** (`staging`). Account `242724708940`,
region `eu-west-1`.

## Prerequisites
- AWS session for the `staging-conduit-*-operator` roles (this estate uses aws-vault + MFA — cache a session
  first: `aws sts get-caller-identity` must return before any `terraform` call, or init/plan hang).
- GitLab SSH deploy key on the box (module sources are `git::ssh://git@gitlab.com/hypervolt/...`).
- The deploy package published: CI `publish` stage writes the 8-char SHA into `terraform/deploy-versions.yaml`
  for `staging`. Confirm it is non-empty before applying `conduit-api`/`conduit-consumer`.
- Secrets present in Secrets Manager (see `terraform/README.md` §Secrets): RDS creds are written by `rds/`;
  Stripe/Xero/Keycloak secrets must exist (Keycloak optional for staging — Google Workspace sign-in is the gate).
- SSM `/staging/conduit/GOOGLE_OAUTH_CLIENT_ID` set (the desk sign-in client id).

## Apply order (each: `terraform init && terraform workspace select staging && terraform plan && terraform apply`)
1. `roles-global` — **workspace `default`**, not staging. Creates the operator roles every other root assumes. Apply once per account.
2. `rds` — PostgreSQL 16 (single-AZ on staging). Writes `staging/conduit/rds-db-credentials/conduit.json` + the `conduit-rds` SG.
3. `conduit-records` — the WORM document bucket (object-lock GOVERNANCE non-prod, 7y).
4. `conduit-tigerbeetle` — **first apply with `-var cold_start=true`** (formats the data file). Cluster id 300 (staging), 4 replicas.
5. `conduit-api` — the http4s/tapir ASG (`t4g.small`, 1 replica staging). Flyway migrates RDS on boot.
6. `conduit-consumer` — the outbox relay + ledger posters + all consumers (incl. `return-effector`). Holds the TB client.
7. **`scripts/provision-pulsar.sh`** — REQUIRED (topics are not in Terraform):
   ```sh
   PULSAR_ADMIN="pulsar-admin --admin-url https://pulsar.staging.service.consul:8080" scripts/provision-pulsar.sh
   ```
   Idempotent. Creates the `conduit.*` topics + the `conduit-placement-versioned-subscription-1` subscription
   on `athena-placement-versioned` (pins the start position before the consumer connects).

## Smoke test (post-apply)
- `GET https://conduit.staging.hypervolt.co.uk/health` → `OK` (also `:9990/health` on the instance).
- `GET :9464/metrics` (API) and `:9465/metrics` (consumer) scrape — confirm `dlq_depth`, `outbox_unpublished_count`
  appear (then reconcile the `http_server_*` names in `terraform/observability/conduit.rules.yml`, see its header).
- Sign in to the desk with a `@hypervolt.co.uk` Google account; confirm it lands with **zero grants** (deny-by-default)
  until an admin assigns a role.
- Place one keyboard-only test order through the desk; confirm `OrderPlaced` flows (outbox → relay → topic) and the
  ledger posts (consumer logs "revenue recognised" / a balanced trial balance via the Proof tab).

## Rollback
- App regression: revert `deploy-versions.yaml` to the prior SHA and `terraform apply conduit-api`/`conduit-consumer`
  (the ASG auto-refresh recycles to the pinned package).
- Infra change: `terraform apply` the prior committed state; never hand-edit live resources.
- RDS data: staging is disposable; for a clean slate, drop + recreate (Flyway re-migrates on next API boot).

## Notes / known gaps at staging
- **Auth:** Google Workspace sign-in only; Keycloak federation is a later pass (`staging/keycloak-configuration/*` optional).
- **Observability:** load `terraform/observability/conduit.rules.yml` into the env Prometheus/Alertmanager.
- Multi-market features (i18n, multi-jurisdiction tax/doc templates, reseller API) are Phase-2 — not required for the UK-only staging bring-up.
