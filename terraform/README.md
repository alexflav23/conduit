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
```

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
- `<env>/keycloak-configuration/conduit-api/*` — JWT/JWKS config
- `<env>/conduit/stripe/*` — Stripe webhook signing secret (`STRIPE_WEBHOOK_SECRET`)
- `<env>/conduit/xero/*` — Xero OAuth2 client credentials

## Not in Terraform

Pulsar topics/namespaces are provisioned via the Pulsar admin API at deploy time (CLAUDE.md §6), not here.
