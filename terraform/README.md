# Conduit — Terraform (end-to-end deploy)

Mirrors `athena/terraform`. Each component is a standalone Terraform root with its own S3-backed state
and an `<env>-conduit-<component>-operator` RBAC role (created by `roles-global`). State bucket
`eu-west-1.tf.hypervolt` / `terraform.eu-west-1.hypervolt`, account `242724708940`, region `eu-west-1`.

Environments are Terraform **workspaces**: `staging`, `prod` (and `default`/`global` for `roles-global`).

## Components

| Dir | What | Module |
|---|---|---|
| `roles-global/` | Per-(service,env) RBAC operator roles + the records-bucket boundary. Apply **first**, in the `default` workspace. | `gitlab.com/hypervolt/iam-rbac-roles/aws` |
| `rds/` | PostgreSQL 16, Multi-AZ in prod. Writes the runtime creds secret `<env>/conduit/rds-db-credentials/conduit.json` and the `conduit-rds` security group. | `terraform-aws-modules/rds/aws` |
| `conduit-tigerbeetle/` | The double-entry ledger cluster (6 replicas prod / 4 staging), one EBS volume per replica; writes `tigerbeetle-settings.json` to the pkgs bucket. Cluster ids 300 (staging) / 400 (prod). | `…/modules/ec2-instance` + `nixos-bootstrap` |
| `conduit-records/` | The **WORM** document store: S3 bucket with object-lock (COMPLIANCE in prod, GOVERNANCE non-prod, 7y) + versioning. Backs `S3DocumentStorage`. | native S3 |
| `conduit-api/` | The http4s/tapir API on an autoscaling group (nixos-bootstrap), `:8080` + `:9990` health + `:9464` Prometheus. Flyway runs on boot. Reads RDS creds, Keycloak + Stripe secrets, the records bucket. | `…/modules/ec2-autoscaling-group` + `nixos-bootstrap` |
| `conduit-consumer/` | The single consumer: outbox relay + Xero + revenue-recognition + Stripe-settlement fibers. Holds the TigerBeetle client (no TB in the API). | `…/modules/ec2-autoscaling-group` + `nixos-bootstrap` |

## Apply order

```
roles-global   (workspace: default)   # creates the operator roles every other component assumes
rds            (workspace: staging|prod)
conduit-records
conduit-tigerbeetle                    # first apply with -var cold_start=true
conduit-api
conduit-consumer
provision-pulsar                       # scripts/provision-pulsar.sh — topics + the UFE subscription (see below)
```

**Pulsar provisioning is a required deploy step**, not optional. Topics live outside Terraform (estate
convention) but are part of every deploy: after `conduit-consumer` applies, run
`PULSAR_ADMIN="pulsar-admin --admin-url https://pulsar.<env>.service.consul:8080" scripts/provision-pulsar.sh`
(idempotent — re-running is a no-op). It creates the `conduit.*` topics and pins the
`conduit-placement-versioned-subscription-1` subscription on `athena-placement-versioned` **before** the
consumer first connects, so no UFE activation events are missed in the deploy window. The topic list MUST
match `EventEnvelope.Topics.byAggregate` (the script carries a keep-in-sync note — `conduit.returns` was the
one that drifted).

Per component:

```sh
cd terraform/<component>
terraform init
terraform workspace select staging   # or prod
terraform plan
terraform apply
```

> The modules are private (`git::ssh://git@gitlab.com/hypervolt/...`); `terraform init` needs the GitLab
> SSH deploy key, so `validate`/`plan` only run inside the estate (or CI). Offline, `terraform fmt` checks
> HCL syntax.

## Versions

`deploy-versions.yaml` pins the deployed package per env (the 8-char git short SHA, written by the CI
`publish` stage on a protected branch). Empty = floating default (staging).

## Secrets (Secrets Manager, per env)

- `<env>/conduit/rds-db-credentials/conduit.json` — DB creds (written by `rds/`)
- `<env>/keycloak-configuration/conduit-api/*` — JWT/JWKS config (Keycloak federation — later pass)
- `<env>/conduit/stripe/*` — Stripe webhook signing secret (`STRIPE_WEBHOOK_SECRET`)
- `<env>/conduit/xero/*` — Xero OAuth2 client credentials

## Google Workspace sign-in (the first-pass production auth gate)

The API verifies Google ID tokens server-side (`GoogleTokenVerifier`): audience = our OAuth client id,
issuer = Google, `hd = hypervolt.co.uk`, verified e-mail, expiry. One-time Google Cloud Console setup
(the ghost-busters pattern, enforcement moved server-side):

1. **APIs & Services → OAuth consent screen** → User type **Internal** (restricts to the Workspace).
2. **Credentials → Create credentials → OAuth client ID → Web application**:
   - Authorized JavaScript origins: `https://conduit.hypervolt.com`, `https://staging-conduit.hypervolt.com`,
     `http://localhost:3002` (dev). No redirect URI (Google Identity Services button flow).
3. The client id (`…apps.googleusercontent.com`) is public, not a secret:
   - SSM `/<env>/conduit/GOOGLE_OAUTH_CLIENT_ID` (flat name = env var, per the mk-env convention) → the API
   - `VITE_GOOGLE_CLIENT_ID` at desk build time → the sign-in button
4. RBAC stays the access gate: a domain account signs in but holds **zero grants** until an admin assigns a
   role (auto-provisioned `app_user`, deny-by-default). Dev `dev:<id>` tokens are refused by prod backends.

## Not in Terraform

Pulsar topics/namespaces are provisioned via the Pulsar admin API at deploy time (CLAUDE.md §6), not here —
but **not optional**: `scripts/provision-pulsar.sh` is a mandatory step in the apply order above, and the
deploy is incomplete without it.

## Observability

Metrics scrape via the estate `hv-telemetry` stack (the `prometheusScrapeSources` lines in each module's
bootstrap: API `:9464`, consumer `:9465`). The Conduit SLO alert rules live in
[`observability/conduit.rules.yml`](./observability/conduit.rules.yml) — load them into the estate
Prometheus/Alertmanager (see that file's header). They defend the doc-19 §C.1/§C.3 invariants: API
availability + latency, outbox relay lag (`outbox_unpublished_count`), DLQ depth (`dlq_depth`), open
reconciliation exceptions (`reconciliation_exception_count`), and consumer/instance liveness.
