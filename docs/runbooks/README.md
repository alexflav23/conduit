# Conduit operator runbooks

Hands-on operational procedures. Each fires from a specific alert (see `terraform/observability/conduit.rules.yml`)
or a planned operation, and ends with a verification that re-performs the relevant control.

| Runbook | When | Defends |
|---|---|---|
| [staging-deploy.md](./staging-deploy.md) | First bring-up / redeploy to `conduit.staging.hypervolt.co.uk` | the deploy is repeatable + Pulsar-provisioned |
| [dlq-replay.md](./dlq-replay.md) | `ConduitDLQNotEmpty` (`dlq_depth > 0`) | CTRL-DLQ-EMPTY |
| [projection-rebuild.md](./projection-rebuild.md) | `ConduitOutboxBacklog`, or a drifted read-model | CTRL-OUTBOX-DRAINED / CTRL-REPRO |
| [migration-cutover.md](./migration-cutover.md) | Legacy → Conduit cutover | opening trial balance = 0; cutover ties to the penny |

Principle throughout: the **outbox is the immutable log** and every consumer is idempotent on `event_id`, so
recovery means re-running the same handler — never a second write path. Verification always re-performs a
`CTRL-*` control rather than asserting by eye.
