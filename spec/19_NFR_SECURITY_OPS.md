# 19 — Non-Functional Requirements, Security & Ops/DR

This document specifies the **non-functional requirements** (SLAs, latency budgets, throughput, availability, RPO/RTO, scale assumptions, retention), the **security model beyond authentication** (secrets management, encryption, the GDPR right-to-erasure / DSAR procedure, rate limiting, a STRIDE threat model, the SOX/ICFR controls documentation index), and the **operations / observability / disaster-recovery** posture (metrics-logs-traces, alerting, the DLQ-replay and projection-rebuild runbooks, backup/restore, environments + release + feature flags, CI migration-safety). It discharges the three **P1** backlog rows in doc 10 §D and is a **launch-blocker**: Conduit does not go live until everything here is green.

Design stance carries over from the pack: **Conduit is a citizen of the existing estate** (CLAUDE.md), the **ledger is the truth and every figure re-performs by replay** (doc 14), and the **immutable event log + crypto-shred** is what reconciles indefinite financial retention with GDPR erasure (doc 01 §3a). Nothing here invents a parallel mechanism: metrics/logs/traces, secrets, discovery and CI gates all reuse the house Athena pattern; the net-new pieces (outbox, schema gate, no-float gate, policy layer) are the ones the spec already mandates.

Cross-refs: latency budget and the policy layer are doc 05 (§6); the event spine, outbox, retention and PII-erasure scope are doc 01 (§2, §3a, §6); DLQ/replay/checkpoint mechanics are doc 03 (§3); the SOX `control`/`reconciliation` register and retention are doc 14 (§4–5); the house stack/version pins and CI gates are CLAUDE.md (§2, §3, §6).

---

## PART A — NON-FUNCTIONAL REQUIREMENTS

### A.1 Latency budgets (per request class, p95 / p99)

Budgets are **server-side wall-clock** (request received at Ember → response flushed), measured from the API histogram (`http_server_request_duration_seconds`, §C.1). They exclude client network RTT. Each budget is an SLO with an alarm (§C.3).

| Request class | Path (examples) | p95 | p99 | Notes |
|---|---|---|---|---|
| **Order capture** | `POST /orders` (compliant, ≤ ~3 lines) | **< 300 ms** | < 600 ms | The headline budget from doc 05 §6. One Postgres tx (business rows + outbox rows), no synchronous ledger/allocation (those are consumers). Scope predicates indexed on `entity_id/market_id/channel_id` (doc 05 §6). |
| Pricing quote | `POST /pricing/quote` | < 250 ms | < 500 ms | Pure read + resolution (doc 04 §Pricing); no writes. |
| Read / list (scoped) | `GET /orders`, `GET /parties` | < 200 ms | < 400 ms | Scope predicate + data-layer projection applied at the DB; pagination mandatory (≤ 100 rows/page). |
| Auth (token verify) | every request | < 15 ms | < 40 ms | JWKS cached in-process (Keycloak certs, doc-CLAUDE §2); cache miss (key rotation) is the p99 tail. |
| ADLP exception decision | `POST /adlp/exceptions/:id/decision` | < 300 ms | < 600 ms | Write + outbox; CEO-only (doc 05 §4). |
| Reseller API (scoped JWT) | `GET /reseller/*` | < 350 ms | < 700 ms | Same policy layer; rate-limited (§B.4). |
| Auditability lineage drill | Auditability Center (doc 14 §6) | < 2 s | < 5 s | Re-perform / replay class; not on the hot path; explicitly allowed to be slow. |
| Bulk export / report | reporting, DSAR export | best-effort | — | Async job; returns a job id, not a synchronous payload. |

**Async (event) latency** is budgeted separately because it is not in the request path:
- **Outbox relay lag** (business commit → Pulsar publish): p95 **< 1 s**, p99 < 5 s. This is the freshness of every downstream projection and external feed.
- **Ledger posting lag** (`order.invoiced`/`dispatch.delivered` → TigerBeetle transfer): p95 **< 2 s**. Money becomes visible in the GL projection within this.
- **Projection lag** (event published → read model updated): p95 **< 3 s** for operational projections (coverage, account history, stock summaries).

### A.2 Throughput & scale assumptions (year-1 → design ceiling)

Year-1 seed is **UK only** (GBP/VAT 20/en, buying from Luxshare-UK; CLAUDE.md §8.7). The design ceiling is sized so the 23-market roadmap does not force a re-architecture.

| Dimension | Year-1 (UK) steady | Design ceiling (multi-market) | Basis |
|---|---|---|---|
| **Orders/day** | ~500–2,000 (trade + retail feed) | 50,000/day | Trade orders captured directly; retail arrives as `order.placed` fed from Athena (doc 01 §4). |
| **Events/sec (mean)** | ~5–20 | 500 | Each order fans out to ~8–15 events (allocation, commission, ledger, projections); inventory/activation add a steady background rate. |
| **Events/sec (peak)** | ~100 | 2,000 | See peak multipliers below. |
| **Units under management** | ~100k–500k serials | 10M serials | Each serial carries genealogy + activation + warranty provision events (doc 03). Sized for cumulative installed base, not annual. |
| **Parties** | ~10k | 1M | Installers/wholesalers/branches/individuals (doc 02 §C). |
| **TigerBeetle transfers/day** | ~5k–20k | 1M | One ledger per currency; transfers are tiny u128 records (doc 14 §1.5) — TB is not the bottleneck. |
| **Concurrent back-office users** | ~50 | 1,000 | Desk + companion app; reseller API is separate. |

**Peak multipliers** (applied to mean to size headroom):
- **Daily peak ×5** — UK business-hours concentration (09:00–17:00 Europe/London).
- **Promotional / launch ×10** — NPI launch, channel promotion, quarter-end ordering push.
- **Migration backfill ×20 (bounded)** — the migration emitter (doc 18) replays history through the same outbox→Pulsar→consumer path; this is the highest sustained event rate the system sees and is the sizing case for consumer parallelism. It is bounded (runs once, off-peak, rate-limited) so it does not size the steady-state cluster, but consumers must keep up without unbounded DLQ growth.

**Sizing rule:** provision steady-state for `mean × daily-peak-×5`; verify (load test, §C.6) the system absorbs `×10` promotional and `×20` migration bursts with bounded, draining backlog (no DLQ accumulation, projection lag recovers to p95 within 10 min of burst end).

### A.3 Availability targets

| Component | Target | Measurement | Notes |
|---|---|---|---|
| **Order capture API** | **99.9%** monthly (≤ ~43 min/mo) | synthetic probe + real-traffic success ratio | The revenue-critical path. |
| Read/query API | 99.9% monthly | as above | |
| Reseller API | 99.5% monthly | as above | Lower tier; rate-limited; degrades before core. |
| Event backbone (Pulsar) | 99.95% | broker availability | At-least-once + durable log means a brief broker blip delays, never loses (doc 01 §3). |
| Ledger (TigerBeetle) | 99.99% | cluster quorum up | 3+ replicas, quorum writes (doc 01 §6). Ledger posting is async, so a TB blip backs up the poster, it does not fail order capture. |
| Async processing | no hard SLA; **bounded lag** | projection/relay lag SLOs (§A.1, §C.3) | Eventual; correctness > latency. |

Availability is **degradation-tolerant by design**: because mutations only require the Postgres tx (business rows + outbox) to commit, the write path stays up even when Pulsar, TigerBeetle, Xero or HubSpot are degraded — those drain from the durable outbox/log when they recover. The order-capture SLA therefore depends on **Postgres + the API**, not on the whole downstream estate.

### A.4 RPO / RTO

**RPO** (max tolerable data loss) and **RTO** (max tolerable downtime to restore) per store. Backup/restore mechanics in §C.5.

| Store | RPO | RTO | Mechanism |
|---|---|---|---|
| **PostgreSQL** (RDS, Multi-AZ) | **≤ 5 min** | **≤ 30 min** | Multi-AZ synchronous standby (near-zero RPO on AZ failure) + PITR via WAL archiving to S3 (5-min restore granularity for logical corruption). |
| **TigerBeetle** | **0 (no loss)** | **≤ 15 min** | 3+ replica quorum; a committed transfer is durable on quorum. Replica replacement rejoins from peers. Periodic offsite snapshot for region-loss DR. |
| **Pulsar** | **≤ 1 min** | **≤ 30 min** | BookKeeper quorum (ensemble/write/ack ≥ 3/2/2) + S3 tiered offload (doc 01 §6); unacked messages redeliver. The log is the recovery source for projections. |
| **S3 archive (events/WORM/backups)** | 0 | n/a (durability 11×9) | Versioned, cross-region replication for the WORM evidence bucket (doc 14 §5.3). |
| **Projections / read models** | n/a (derived) | **rebuildable** | Not backed up — **rebuilt by replay** from Pulsar/S3 (the projection-rebuild runbook, §C.4.2). RTO here = rebuild time, not restore time. |

**Region-loss DR (eu-west-1 unavailable):** RPO ≤ 5 min / RTO ≤ 4 h. Cross-region replicated RDS snapshot + TB offsite snapshot + Pulsar S3 offload restored into a standby region built from the same Terraform; projections rebuilt by replay. This is a documented, periodically-rehearsed manual failover (not active-active) — sized to the cost/benefit of a UK-seed business, revisited as markets scale.

### A.5 Retention & archival

Aligns with doc 01 §3a (complete log, retained indefinitely; hot ≥ 30d then S3) and doc 14 §5.3 (immutable evidence, indefinite).

| Data class | Hot retention | Archive | Erasable? |
|---|---|---|---|
| **Event log** (Pulsar) | **≥ 30 days** hot for replay | **S3 tiered offload, indefinite** | No payload PII to erase (PII kept out of long-retained payloads — doc 01 §3a). Crypto-shred handles any keyed PII reference (§B.3). |
| **`audit_log`** | indefinite (online) | — | Append-only; Admin cannot edit (doc 05 §5). Not erasable (financial/SOX evidence). |
| **TigerBeetle transfers** | indefinite | offsite snapshot | Never deleted; corrections are reversing transfers (doc 01 §3b). |
| **Postgres business rows** | indefinite (soft-delete via `deleted_at`; transactional rows never hard-deleted — doc 00) | — | PII fields crypto-shredded on erasure; the financial skeleton is immutably retained (§B.3). |
| **PII (contact details)** | retained while lawful basis holds | — | **Erasable** by crypto-shred (§B.3) on a valid request. |
| **WORM evidence packs** (doc 14 §5.3) | — | S3 Object-Lock, indefinite, cross-region | No. |
| **Operational logs/metrics/traces** | 30 d hot (logs), 13 mo (metrics), 7 d (traces) | per house policy | Logs must carry **no PII** (§B.1); scrubbed at the log appender. |

**Capacity model (storage):** event log ≈ `events/day × avg-event-size × retention`. At the design ceiling (`~50k orders/day × ~12 events × ~2 KB ≈ 1.2 GB/day` of envelope+payload, plus inventory/activation background) → ~40 GB/mo hot, offloaded to S3 thereafter; indefinite S3 retention is a cost line, not a capacity wall. Postgres growth is dominated by serial/genealogy and audit rows; sized at design ceiling to low-TB over multiple years — well within RDS limits with periodic partition/archival review.

---

## PART B — SECURITY

### B.1 Secrets management (house mechanism, nothing checked in)

Conduit conforms to doc 01 §6.1: **runtime config via Consul KV; secrets via the house secrets mechanism, never in env files checked into the repo.**

- **Source of truth:** AWS Secrets Manager (per CLAUDE.md §6) — `<env>/conduit/rds-db-credentials/*`, `<env>/keycloak-configuration/conduit-api/*`, plus Conduit-specific secrets: TigerBeetle cluster credentials, Pulsar auth token, Stripe/Xero/HubSpot integration keys, the **PII master-key-encryption-key reference** (§B.3), and reseller-API signing material.
- **Delivery:** secrets are injected at deploy via the Terraform-provisioned IAM role (`…/rbac/<env>-conduit-operator`) and surfaced to the process as env overrides into the typesafe-config HOCON (`${ENV_VAR}`), exactly as Athena does. **No secret is in `application.conf`, in the repo, in a Docker image layer, or in Consul KV in plaintext.** Non-secret config (endpoints, ports, feature-flag defaults) lives in HOCON/Consul KV.
- **CI gate (net-new, additive to the house gates):** a `secretScan` lint stage (gitleaks-style) fails the build on any committed credential pattern (DB URL with password, AWS key, JWT, private key, Stripe/Xero token). This runs in the **lint** stage alongside `schemaCheck` and the no-float lint (CLAUDE.md §6). **✅ Implemented** as the `sbt secretScan` task (patterns: `AKIA…` AWS key, `BEGIN … PRIVATE KEY`, three-part JWT, `sk_live_/rk_live_` Stripe, `scheme://user:pass@host` DB URL), wired into the `financial-gates` lint job; scans code+config, skips prose/build artefacts; fails a planted secret.
- **Rotation:** DB and integration credentials rotate via Secrets Manager rotation; the app re-reads on the documented rotation cadence (≥ quarterly, and immediately on suspected compromise). Keycloak JWKS rotation is handled by the cached JWKS client (re-fetch on unknown `kid`).
- **Least privilege:** the `conduit-operator` IAM role reads only its own secret prefixes; no shared/estate-wide secret access.
- **Logs carry no secrets and no PII:** log4cats appender scrubs known secret keys and PII fields; structured-log fields are an allowlist, not the whole object.

### B.2 Encryption

| Boundary | At rest | In transit |
|---|---|---|
| PostgreSQL (RDS) | AES-256 (KMS-managed; encrypted storage + encrypted snapshots) | TLS (require SSL on the JDBC connection) |
| TigerBeetle | encrypted EBS volumes (KMS) | TLS between client and replicas |
| Pulsar / BookKeeper | encrypted volumes; **PII never in payloads** (doc 01 §3a) so the log is not a PII store | TLS broker↔client; auth token (§B.1) |
| S3 (archive, WORM, backups) | SSE-KMS; Object-Lock (WORM bucket) | TLS |
| API ↔ clients | — | TLS 1.2+ only; HSTS; no plaintext HTTP |
| Internal service↔service | — | TLS via Consul-discovered endpoints |
| **Application-level PII** | **per-subject envelope encryption** (the crypto-shred substrate — §B.3) | as above |

Key management is AWS KMS for infrastructure-level keys; **application-level PII keys** are managed by the crypto-shred scheme below (a per-subject data key, wrapped by a KMS-held key-encryption-key).

### B.3 GDPR right-to-erasure / DSAR procedure (crypto-shred + immutable financial skeleton)

> **✅ Implemented (M-NFR.1).** `pii_key` (per-subject wrapped DEK) + `pii_record` vault (V1_0_46); `privacy.CryptoShred`
> (AES-256-GCM envelope encryption, KEK from `PII_KEK`/Secrets Manager, dev fallback), `privacy.PiiVault`
> (put/get/shred, `«erased»` tombstone), `privacy.DsarService` (maker-checker erasure → shred → `pii.shredded`
> event carrying **no PII**). REST: `POST /api/v1/privacy/dsar/erasure`, `…/dsar/{id}/approve`, `GET …/privacy/pii`.
> Control **CTRL-PII-SHRED** (a shredded key must have no wrapped DEK). `DsarSuite` proves erasure tombstones PII
> + destroys the DEK while the financial skeleton + invoice amounts survive and re-perform.
>
> **✅ Tombstone propagation now implemented (steps 5–6).** `privacy.PiiTombstoneService.propagate(subject)` overwrites
> the subject's served projection columns (`party` person-name, `contact` name/email/phone, `address`, `billing_profile`)
> with the `«erased»` tombstone in one transaction; `consumer.PiiShreddedConsumer` performs it off `conduit.crm` on the
> `pii.shredded` event (own subscription `conduit-pii-tombstone-1`, Shared+Earliest, idempotent → at-least-once-safe).
> `PiiTombstoneSuite` proves: after a governed erasure, **every** served PII column reads `«erased»`, the vault DEK is
> destroyed, and the order/`Money` skeleton is intact. *Note: the per-write column→vault encryption binds to the M4 CRM
> write endpoints when built — today `party`/`contact` are test-seeded (no production write path), so the vault is the
> proven encrypted store (`DsarSuite`) and the tombstone consumer governs the served columns. HubSpot anonymise rides
> the same `pii.shredded` event when the HubSpot adapter lands (doc 01 §2).*

This is the procedure doc 01 §3a and doc 14 §5.3 flag but do not write. It resolves the tension between **GDPR erasure** (a data subject can require their personal data be deleted) and **indefinite, immutable financial/audit retention** (SOX/PCAOB, doc 14). The mechanism is **crypto-shredding**: PII is encrypted with a per-subject key; erasure destroys the key, rendering the ciphertext permanently unrecoverable, while the **non-personal financial skeleton** (amounts, dates, IDs, ledger transfers) is retained intact and still reconciles.

#### B.3.1 Key hierarchy

```
KMS root KEK (per-env, in AWS KMS, never leaves KMS)
   └── wraps ──► per-subject Data Encryption Key (DEK)  [one per data subject / party]
                    └── encrypts ──► that subject's PII fields (envelope-encrypted ciphertext stored in the row)
```

- Each **data subject** (a `party` of a person nature, or a `contact`) has exactly one **DEK**, stored wrapped (encrypted by the KMS KEK) in a `pii_key(subject_id, wrapped_dek, status active|shredded, shredded_at, shredded_by)` table.
- PII columns (name, email, phone, address, etc.) are stored as **ciphertext** encrypted under that subject's DEK. The app decrypts on read for authorised principals (the `pii` data layer, doc 05 §3).
- **Crypto-shred = destroy the DEK.** Set `pii_key.status='shredded'`, overwrite `wrapped_dek` with NULL/tombstone. The ciphertext remains in place but is now undecryptable by anyone, forever (the KEK cannot reconstruct a destroyed DEK). This is irreversible and is the legal "erasure".

#### B.3.2 What is erasable vs. immutably retained

| Erasable (crypto-shredded) | Immutably retained (the financial skeleton) |
|---|---|
| Contact name, email, phone, postal/installation address | Order IDs, line IDs, quantities, **`Money` amounts**, currency, tax |
| `party`/`contact` personal descriptive fields tagged `pii` (doc 05) | TigerBeetle transfers (immutable by construction, doc 01 §3b) |
| PII inside the governed `attributes` bag tagged `data_layer='pii'` (doc 05 §3) | `audit_log` action records (actor, action, before/after of **non-PII** fields) |
| Free-text notes flagged as containing PII | Event log envelopes (no PII in long-retained payloads — doc 01 §3a) |
| Installer/owner personal identity on activation (the *person*, not the serial) | Serial, genealogy, batch, landed cost, warranty provision (asset/obligation facts) |

The retained skeleton still **re-performs**: a reported revenue figure drills transfer → event → order (doc 14 §5.1) even after the customer's name is shredded — the order and its money are intact; only the personal identity is gone. After shred, PII fields render as `«erased»` (a tombstone), never as a fabricated value or a null that looks like missing data.

#### B.3.3 DSAR procedure (step-by-step)

A **DSAR** (Data Subject Access Request) and a **right-to-erasure** request are handled by a governed workflow, audited end-to-end (it is itself a material action, doc 05 §5). Maker-checker: the requester logs and verifies; an authorised **Data Protection** approver decides erasure (requester ≠ approver).

**Access / portability request (Article 15/20):**
1. **Receive & log** the request → create `dsar_request(subject_id, type access|erasure, status, requested_at, requested_by, verified_at)`; emit `dsar.requested` (audited).
2. **Verify identity** of the data subject (out-of-band per the DPO procedure); record `verified_at`. Unverified requests do not proceed.
3. **Resolve the subject** to its `party`/`contact` and all linked records (orders, activations, deals) via the subject graph.
4. **Assemble the export** — an async job that decrypts (the subject's own PII, under the `pii` layer) and collates all personal data held, into a portable bundle (machine-readable + human-readable). This is a bulk-export class request (§A.1), not synchronous.
5. **Deliver** securely; set `status='fulfilled'`; emit `dsar.fulfilled` (audited). SLA: within the statutory window (default 30 days).

**Erasure request (Article 17), the crypto-shred path:**
1. **Receive, log, verify** as steps 1–2 above; `type='erasure'`.
2. **Lawful-basis check (maker-checker gate).** The Data Protection approver confirms no overriding lawful basis to retain the *personal* data (e.g. an open dispute). Note: financial/tax/audit retention obligations attach to the **skeleton**, not to the erasable PII, so they do **not** block erasure — they are satisfied by retaining the skeleton. Requester ≠ approver; the decision is audited with a memo.
3. **Locate the DEK** — `pii_key` for the subject (and any merged-party predecessors, doc 11 merge/dedupe).
4. **Crypto-shred** — set `pii_key.status='shredded'`, null the `wrapped_dek`, record `shredded_at/shredded_by`. All PII ciphertext for the subject is now permanently undecryptable. This is one transactional action; it emits `pii.shredded(subject_id)` (audited; the event carries **no PII**, only the subject id and the fact of erasure).
5. **Propagate the tombstone to projections** — the `pii.shredded` event is consumed by projection builders and external adapters (HubSpot replication, doc 01 §2): each replaces the subject's PII with the `«erased»` tombstone in its read model / downstream system, and issues the corresponding downstream-delete/anonymise (e.g. HubSpot contact erase). Because projections are rebuildable, a future rebuild re-derives the tombstone from the shredded key state, not from stale PII.
6. **Verify & close** — confirm no decryptable PII remains for the subject (a verification query attempts decrypt and expects failure across all stores), set `dsar_request.status='fulfilled'`, emit `dsar.fulfilled` (audited). SLA: statutory window.

**Edge cases:**
- **Backups/snapshots** taken before shred still contain the (encrypted) PII *and* the old wrapped DEK. The wrapped DEK in those backups is wrapped by the KEK — so to make backup PII truly unrecoverable, the shred also records the subject in a **shred-list** that is re-applied on any restore (restore runbook step, §C.5), and infra backups age out under their own retention. The ciphertext-without-DEK property means even an old backup, once its DEK is shred-listed, cannot be decrypted.
- **Merged parties** (doc 11): erasure shreds the surviving subject's DEK *and* all predecessor DEKs.
- **Re-identification risk:** the skeleton must not itself become PII. Free-text fields that could carry personal data are either tagged `pii` (and thus shredded) or governed out of long-retained payloads; this is reviewed as part of the `property_definition` change-management control (doc 14 §4.4).

### B.4 Rate limiting

| Surface | Limit (default; configurable per principal/tier) | Mechanism |
|---|---|---|
| **Reseller API** (scoped service JWT) | per-token quota (e.g. 60 req/s burst, 10 req/s sustained) + daily cap | token-bucket keyed on `sub`; `429` with `Retry-After`. |
| Authenticated back-office | generous per-user ceiling (abuse/runaway-client guard, not throttling normal use) | per-`sub` token bucket. |
| Auth/login (via Keycloak) | Keycloak brute-force protection (account lockout + backoff) | Keycloak realm config. |
| Bulk/export/DSAR | concurrency-limited job queue, not per-request | async job admission control. |
| Unauthenticated (health/metrics) | IP allowlist (internal/Consul mesh only) + tight per-IP limit | network policy + edge limit. |

Rate limits are a **preventive control**: they bound the blast radius of a compromised reseller token, a runaway client, and credential-stuffing. `429`s are emitted as a metric and alarmed if a single principal saturates (possible compromise — §B.5 Spoofing).

> **DEFERRED (not built; spec only).** This is fairness/isolation + admission control among *authenticated* principals
> — not perimeter/DDoS defence (everything is behind Keycloak; volumetric defence is the load-balancer/WAF's job, and
> login brute-force is Keycloak's). The app-side pieces — a per-`sub` token bucket + bulk/export **job-admission
> concurrency control** — are deferred: the per-tier reseller quota only matters once the **reseller API exists**
> (not yet built), and the job-admission gate lands with the bulk-export/reporting surface. Until then the edge limit
> + Keycloak cover the real exposure. Revisit when the reseller API or heavy async exports ship.

### B.5 STRIDE threat model (key flows)

Per-flow STRIDE (Spoofing / Tampering / Repudiation / Information disclosure / Denial of service / Elevation of privilege). Mitigations reference the spec's existing controls; net-new items are flagged.

**Flow 1 — Order capture** (`POST /orders`, doc 04 §Orders)

| Threat | Vector | Mitigation |
|---|---|---|
| Spoofing | Forged caller identity | Keycloak JWT verified against JWKS (CLAUDE.md §2); no anonymous writes. |
| Tampering | Client alters price/discount to underpay | Server resolves price from `price_rule` (doc 04); client-supplied price is never trusted; ADLP exceptions need CEO approval (doc 05 §4). |
| Repudiation | "I didn't place that order" | `audit_log` + event log capture actor + before/after (doc 05 §5); immutable. |
| Info disclosure | Seeing out-of-scope orders / hidden layers | Policy layer scope filter + data-layer projection (doc 05 §2–3); deny-by-default. |
| DoS | Order flood | Per-principal rate limit (§B.4); write path bounded by the single Postgres tx. |
| Elevation | Acting beyond role | Policy layer object/action/section/scope check server-side; revocation effective next request (doc 05 §6). |

**Flow 2 — Pricing / ADLP approval** (doc 04 §Pricing, doc 05 §4)

| Threat | Mitigation |
|---|---|
| Tampering | Pricing changes are governed, versioned, audited (`pricing.rule.changed`, doc 03); not a free edit. |
| Repudiation | ADLP decision carries `approved_by` + `memo_ref` (doc 03; doc 14 §4.1). |
| Info disclosure | `inter_entity` layer walled from Deal Desk (doc 05 §3 pricing-wall example). |
| Elevation | **Only CEO/CFO** approves exceptions; Deal Desk assembles but cannot approve (maker-checker, doc 14 §4.1); self-approval rejected. |

**Flow 3 — Ledger posting** (TigerBeetle, doc 01 §5, doc 14)

| Threat | Mitigation |
|---|---|
| Tampering | TB is append-only/immutable; corrections are reversing transfers, never edits (doc 01 §3b). |
| Repudiation | Transfer id is deterministic from `event_id` (CLAUDE.md §4); links transfer → event → source. |
| Info disclosure | TB not exposed to business reads; only the ledger poster writes (doc 01 §5); GL projection is layer-projected. |
| DoS / double-post | Ledger poster idempotent on `event_id`; redelivery is a no-op (deterministic transfer id). |
| Integrity | `Σ debits == Σ credits` per currency or the transfer is rejected (doc 14 §1.5, §5.4). |
| Elevation | Period **lock** rejects back-posting regardless of role (doc 05 §4, doc 14 §2.4). |

**Flow 4 — Reseller API** (scoped service JWT, doc 00, doc 01)

| Threat | Mitigation |
|---|---|
| Spoofing | Scoped service JWT, short-lived, signed; same JWKS verification. |
| Tampering | Same policy layer as internal; scoped to the reseller's `entity/market/channel`. |
| Info disclosure | Scope filter + layer projection on every response; a reseller sees only its own data; events to externals are layer-filtered projections, never raw (doc 05 §3). |
| DoS | Per-token rate limit + quota (§B.4); reseller tier degrades before core (§A.3). |
| Elevation | Service token grants are a narrow preset (no admin/finance actions); compromise is bounded by scope. |
| Compromise detection | `429`/anomaly alarm on token saturation (§B.4, §C.3). |

**Flow 5 — Auth / identity** (Keycloak OIDC, doc 05)

| Threat | Mitigation |
|---|---|
| Spoofing | OIDC; JWT signature + issuer + audience + expiry validated (CLAUDE.md §2). |
| Tampering | JWT integrity by signature; JWKS pinned to the Keycloak realm. |
| Repudiation | All access grants/revokes audited (`access.permission.granted/revoked`, doc 03). |
| Info disclosure | Tokens carry `sub` only; authorisation data resolves server-side (doc 05), not from token claims the client could forge. |
| DoS | Keycloak brute-force protection + login rate limit (§B.4). |
| Elevation | Stale-token risk bounded: revocation effective on next request (no cached allow, doc 05 §6); short token TTL. |

### B.6 SOX / ICFR controls documentation index

This is the index that ties the operational/security controls to the SOX `control` register (doc 14 §4, `control` table). Each control names the **assertion** it supports and **how it is evidenced** (`evidence_query`) so operating-effectiveness is a query, not a memo. The financial controls are owned by doc 14; the rows below are the **NFR/security/ops controls** that join the same register.

| Control | Type | Frequency | Assertion(s) | Evidence (how re-performed) | Spec ref |
|---|---|---|---|---|---|
| Segregation of duties / maker-checker | preventive | continuous | rights & obligations | system-enforced requester ≠ approver; audit of every approval | doc 14 §4.1, doc 05 §4 |
| Immutable audit trail | detective | continuous | completeness, rights | append-only `audit_log` + TB + event log; Admin cannot edit | doc 14 §4.2, doc 05 §5 |
| Access control / least privilege | preventive | continuous | rights & obligations | deny-by-default policy layer; authz tests per endpoint | doc 05 §6, §B.5 |
| **Secrets not in source** | preventive | per-commit | rights | `secretScan` CI gate green on every build | §B.1 |
| **Encryption at rest/in transit** | preventive | continuous | rights, presentation | KMS/TLS config evidenced from infra state | §B.2 |
| **GDPR erasure honoured** | detective | per-request | rights | `dsar_request` closed + decrypt-fails verification | §B.3 |
| Change management (migrations/schema) | preventive | per-change | completeness, valuation | Flyway forward-only + `schemaCheck` BACKWARD + no-float gates green | §C.6, CLAUDE.md §6 |
| Reconciliation controls | detective | daily/monthly | completeness, valuation | `reconciliation` ties (TB↔GL↔Xero, inventory, AR) | doc 14 §5.2 |
| Period-close & lock | preventive | monthly | cutoff | posting to `locked` rejected; close checklist sign-offs | doc 14 §2.4, §5.4 |
| **Completeness — gapless sequence** | detective | continuous | completeness | gapless event sequence per stream; missing seq → alarm | doc 14 §4.7, §C.3 |
| **Backup/restore tested** | detective | quarterly | completeness | DR rehearsal log; restore RTO/RPO met (§A.4, §C.5) | §C.5 |
| **Tested integrity (property suite)** | detective | per-build | valuation, accuracy | ScalaCheck conservation/no-float/balance/period suite green in CI | doc 14 §5.4 |

---

## PART C — OPS / OBSERVABILITY / DR

### C.1 Metrics, logs, traces (the house pattern)

> **✅ Mechanisms implemented (M-NFR.3).** The exporter, the operational gauges, JVM runtime metrics, the
> per-endpoint HTTP metrics interceptor, the log-level counter appender, and the Vector JSON log encoder are
> built and proven end-to-end (`MetricsSuite` scrapes `:PORT/metrics` and asserts the gauges carry live DB
> values). Code: `metrics/{GlobalMetrics,MetricsBuilder,ConduitMetrics}`, `logging/{OtelAppender,VectorLogEncoder}`,
> `api/ApiMetrics`, wired in both `Main`s. The per-instrument DB/business timers (`metricsBuilder.time(...)`) and the
> latency/lag *histograms* below are the remaining increment (the gauge + HTTP-metrics spine they hang off is done).

Conduit reuses the **hypervolt-backend** observability shape (`libs/utils/{metrics,logging}` — the estate's richest
Scala-service standard; Athena is the same pattern, leaner). **Metrics-only, no distributed tracing** — there is no
span/tracer/context-propagation anywhere in hypervolt-backend or Athena, so building it would be a parallel mechanism
(against the golden rule). Cross-process correlation is via the log `correlation_id`, not a trace tree.

- **Metrics — Prometheus.** OpenTelemetry SDK (`opentelemetry-sdk` 1.40.0 + `exporter-prometheus`) whose meter
  provider carries the `service.name` resource attribute (`conduit` / `conduit_consumer`) and is read by a
  `PrometheusHttpServer` on `PROMETHEUS_PORT` (**API 9464, consumer 9465** — both already registered as scrape
  sources in Conduit's Terraform). A non-global SDK (not `buildAndRegisterGlobal`) so suites can stand up exporters
  without the JVM-singleton conflict. Instruments:
  - **JVM runtime metrics** — heap/GC/threads via `opentelemetry-runtime-telemetry-java17` (`RuntimeMetrics`), as in hypervolt-backend.
  - **Per-endpoint HTTP metrics** — tapir's `OpenTelemetryMetrics` interceptor on the `tapir` meter (request count / duration / active), wired through `ApiMetrics` into every route interpreter. Serves `http_server_request_duration_seconds` (the §A.1 / §C.2 budget).
  - `conduit_outbox_unpublished_count`, `conduit_dlq_depth`, `conduit_reconciliation_exception_count` (gauges) — the operational signals the alarms defend (§C.3), read live from Postgres on each scrape via the dispatcher.
  - **Log-level counters** — `logs_count_conduit{level=…}` / `logs_count_conduit_consumer{level=…}` via `OtelAppender` on the logback root (levels seeded at 0 so the series always exists); the WARN/ERROR-rate alarms fire on these.
  - *(remaining)* `conduit_order_capture_duration_seconds`, `conduit_outbox_relay_lag_seconds`, `conduit_ledger_post_lag_seconds`, `conduit_consumer_lag`, `conduit_event_sequence_gap_total` — the latency/lag histograms (doc 14 §4.7).
- **Logs — log4cats / logback** (pinned). Production selects `logback-prod.xml` → `VectorLogEncoder` (one structured
  JSON line per event to stdout, copied from hypervolt-backend), tailed by the estate's Vector sidecar; dev keeps the
  human console pattern. SLF4J key-value pairs carry `correlation_id` (doc 01 §6); **no PII, no secrets** (allowlisted
  fields — §B.1). 30-day hot retention (§A.5).
- **Health/admin** on **:9990** (`GET /health` → `OK`, CLAUDE.md §2), Consul health check target (doc 01 §6.1).

### C.2 SLOs (the targets the alarms defend)

| SLO | Target | Source metric |
|---|---|---|
| Order-capture p95 | < 300 ms | `conduit_order_capture_duration_seconds` |
| Order-capture availability | 99.9%/mo | success ratio + synthetic probe |
| Outbox relay lag p95 | < 1 s | `conduit_outbox_relay_lag_seconds` |
| Ledger post lag p95 | < 2 s | `conduit_ledger_post_lag_seconds` |
| Projection lag p95 | < 3 s | `conduit_consumer_lag` |
| DLQ depth | 0 (any > 0 is an incident) | `conduit_dlq_depth` |
| Event sequence gaps | 0 | `conduit_event_sequence_gap_total` |
| Recon exceptions at close | 0 before lock | `conduit_reconciliation_exception_count` |

### C.3 Alerting strategy

Two-tier: **page** (wake someone) vs **ticket** (work next business hour). Alarms are on SLOs, not raw resources, so they fire on user-visible harm.

| Alarm | Condition | Severity |
|---|---|---|
| Order-capture latency breach | p95 > 300 ms for 5 min | **page** |
| Order-capture error rate | 5xx ratio > 1% for 5 min | **page** |
| Order-capture down | synthetic probe fails 2× | **page** |
| Postgres unavailable / failover | RDS event / connection failures | **page** |
| TigerBeetle quorum lost | < quorum replicas | **page** |
| **DLQ depth > 0** | any message in any `<topic>.dlq` | **page** (poison message; doc 03 §3) |
| Outbox relay stalled | `unpublished_count` rising > 5 min OR relay lag p95 > 5 s | **page** (downstream going stale) |
| **Event sequence gap** | `event_sequence_gap_total` increments | **page** (completeness control, doc 14 §4.7) |
| Consumer lag high | projection lag p95 > 30 s for 10 min | ticket → page if sustained |
| Ledger post lag high | > 10 s for 10 min | ticket |
| Reseller token saturation | single `sub` hitting `429`s | ticket (possible compromise, §B.5) |
| Recon exception open at close | `reconciliation_exception_count` > 0 near period lock | page (blocks close, doc 14 §5.2) |
| Backup failure | nightly backup/snapshot job failed | page |
| Cert/secret rotation due/failed | rotation job failed | ticket |

### C.4 Runbooks

> **✅ Mechanisms implemented (M-NFR.2).** The replay/rebuild/DLQ machinery these runbooks operate is built and
> tested (`ReplayDlqSuite`): `outbox_dlq` (V1_0_47) parks poison messages; `IdempotentConsumer.processOrDlq` routes a
> failed handler to the DLQ and releases the dedupe claim; `ReplayService.replayDlq` drains it on a fix-then-replay;
> `ReplayService.rebuild` resets a group's dedupe and replays the immutable `outbox_event` log through the **same
> consumer handler** (no second write path) to reconstruct a projection identically; `DedupeStore` makes both safe
> under at-least-once. Completeness controls **CTRL-DLQ-EMPTY** + **CTRL-OUTBOX-DRAINED** are re-performable
> (`ControlRunner`), and `CompletenessRepo` exposes the DLQ-depth / stuck-unpublished SLO reads. The prose below is
> the operator procedure over that machinery.

#### C.4.1 DLQ-replay runbook

Poison messages land on `<topic>.dlq` after N retries with `MultiplierRedeliveryBackoff` (10s→1h, CLAUDE.md §3; doc 03 §3). DLQ depth > 0 pages (§C.3). Goal: diagnose, fix, replay from a checkpoint — **without** re-processing already-acked good messages (consumers are idempotent on `event_id`, so replay is safe even if some overlap).

1. **Acknowledge the page**; identify which `<topic>.dlq` has depth (`conduit_dlq_depth`).
2. **Inspect** the dead message(s): read the envelope (`event_id`, `event_type`, `schema_version`, `aggregate_id`, `correlation_id`) and the consumer's nack reason from logs (filter by `correlation_id`).
3. **Classify the cause:**
   - **(a) Bad data / unhandled case** — the event is valid but the consumer has a bug or an unmodelled case.
   - **(b) Schema mismatch** — consumer pinned a min `schema_version` it can't read (should be caught by the `schemaCheck` gate; if it reaches DLQ, the gate was bypassed or a producer shipped ahead).
   - **(c) Transient dependency** — TB/Postgres/Xero was down when processed; the event is fine.
4. **Fix:**
   - (a) deploy the consumer fix (normal release, §C.5);
   - (b) deploy the consumer that understands the schema, or (if a producer error) produce a corrected event as a **new version** (doc 03 §2) — never edit the dead message in place;
   - (c) no code change; the dependency is back.
5. **Replay:** re-emit the DLQ messages onto the live topic using the replay tool (doc 03 §3 — "replay tooling re-emits from a checkpoint or time window"). Scope the replay to the affected `aggregate_id`/time window, not the whole topic.
6. **Verify:** confirm the consumer processes them (lag drains, no re-nack), `conduit_dlq_depth` returns to 0, and the downstream projection/ledger effect appears (trace by `correlation_id`). Because consumers dedupe on `event_id`, any message that was actually fine and already processed is a no-op on replay.
7. **Drain the DLQ** for the replayed messages (ack them off `<topic>.dlq`); confirm depth 0.
8. **Record** the incident: cause class, fix, count replayed; if it was a completeness gap, confirm `event_sequence_gap_total` is back to flat. File a follow-up if a new consumer test/case is needed (it usually is — add the case that would have caught (a)).

#### C.4.2 Projection-rebuild runbook

Projections (read models / materialised views: H6Q coverage, account history, stock summaries, GL projection) are **derived and rebuildable** — never the source of truth (doc 01 §3, §3a). Rebuild when a projection is found corrupted/stale, after a consumer bug fix that changes how a projection is built, or to materialise a brand-new consumer by backfill. The mechanism is the same replay path used in migration (doc 18) — only the source differs.

1. **Confirm it's a projection, not truth.** Truth is Postgres aggregates + TigerBeetle + the event log. If the *log* is wrong, this is not the runbook (escalate — that's a producer/outbox issue). Projections can always be thrown away.
2. **Identify scope:** which projection(s), and the rebuild key (per-`aggregate_type` topic; optionally bounded by time window or `entity_id`).
3. **Quiesce the consumer:** stop the projection's subscription so live events queue (Pulsar retains them; the log is durable). The projection going stale briefly is expected and within async tolerance (§A.3).
4. **Reset the read model:** truncate/drop the projection tables/materialised views for the scope, and reset the consumer's idempotency/dedupe state and subscription cursor to **`Earliest`** (or to the chosen checkpoint/time window). Crucially: reset the dedupe table so the replay is *not* deduped away as already-seen.
5. **Replay:** restart the subscription from `Earliest`/checkpoint; it consumes from the **hot Pulsar log** and, for history older than 30 days, from the **S3 tiered offload** (doc 01 §6, §3a) — transparently, same topic. The consumer rebuilds the read model row-by-row exactly as it was first built (no second write path — same code).
6. **Catch up to live:** the rebuild processes history then naturally reaches the queued live events; `conduit_consumer_lag` drains to ~0.
7. **Reconcile:** verify the rebuilt projection ties out — for the GL projection, the TB↔GL reconciliation (doc 14 §5.2) must tie to the penny; for coverage/stock, spot-check known aggregates. A rebuilt projection that doesn't reconcile means a consumer bug, not a data-loss event (the log is intact).
8. **Unquiesce / resume normal**; confirm lag flat, no DLQ growth. Record the rebuild (scope, duration, reconcile result) — duration is the projection RTO data point (§A.4).

> The defining property (doc 01 §3a): because the log is **complete and retained indefinitely**, any projection — including one that doesn't exist yet — is rebuildable from history by replay. This is what lets a future ERP attach with no core change, and what makes a corrupted read model a non-event.

### C.5 Backup / restore

| Store | Backup | Restore | RPO/RTO (§A.4) |
|---|---|---|---|
| **PostgreSQL** | RDS automated backups + WAL archiving (PITR) + Multi-AZ standby; encrypted snapshots, cross-region copy for DR | restore snapshot or PITR to a point in time; promote standby on AZ loss; **re-apply the crypto-shred shred-list** (§B.3.3) on any restore so erased subjects stay erased | ≤ 5 min / ≤ 30 min |
| **TigerBeetle** | quorum durability (no external backup needed for AZ failure) + periodic offsite snapshot for region DR | replace a lost replica → rejoins from peers; region DR → restore offsite snapshot + replay any gap from the event log | 0 / ≤ 15 min |
| **Pulsar** | BookKeeper quorum + S3 tiered offload (the log itself is the backup) | brokers recover from BookKeeper; region DR → restore from S3 offload; projections rebuilt by replay (§C.4.2) | ≤ 1 min / ≤ 30 min |
| **S3 archive/WORM** | versioning + Object-Lock + cross-region replication | restore object versions; WORM cannot be deleted within the lock window | 0 / n/a |
| **Projections** | **none — rebuildable** | the projection-rebuild runbook (§C.4.2) | n/a / rebuild time |

**DR rehearsal (a SOX control, §B.6):** quarterly, restore Postgres PITR + rebuild a projection from S3-offloaded events in a scratch environment and confirm RTO/RPO are met and a sample figure re-performs (transfer→event→source). Log the run as `control_run` evidence.

### C.6 Environments, release, feature flags, CI migration-safety

**Environments:** `dev` → `staging` → `prod`, each provisioned by the **same Terraform** (doc 01 §6.1; CLAUDE.md §6) so environments don't drift; each with its own Secrets Manager prefix (§B.1), Consul, Pulsar tenant/namespace, RDS, and TB cluster. Local dev = the `docker-compose.yml` stack (pg/pulsar/tigerbeetle/consul/keycloak, CLAUDE.md §6). No hand-provisioned infra.

**Release process (GitLab CI, CLAUDE.md §6):**
- **lint** — `scalafmtCheck` + **`schemaCheck`** (Avro BACKWARD gate) + **no-float** money lint + **`secretScan`** (§B.1).
- **compile/test** — unit (weaver) + integration (`api-it`, testcontainers: pg/pulsar/consul) + the **ScalaCheck financial property suite** (doc 14 §5.4).
- **package** — `Universal/packageXzTarball`; Docker on `eclipse-temurin:21-jre` (21 LTS — 19 was EOL).
- **publish/deploy** — S3 + deploy-versions on **protected branches** only; deploy via Terraform/house deploy path; Flyway runs migrations on startup (CLAUDE.md §2).
- Deploys are **branch-first / no direct prod**; promotion dev→staging→prod is gated on green pipelines + (for prod) sign-off.

**Feature flags:** runtime flags in Consul KV (non-secret config, §B.1), read by the app; used to dark-launch consumers/modules and to gate market rollout (UK-first, then the 23-market roadmap, CLAUDE.md §8.7) without redeploy. Flags default off; flipping a flag is config, audited where it affects a controlled surface (e.g. enabling a new pricing market).

**CI migration-safety (the three gates that protect financial integrity):**
1. **Flyway forward-only.** Migrations are `V{maj}_{min}_{patch}__desc.sql` (CLAUDE.md §2), **forward-only** — no destructive down-migrations in prod; a mistake is corrected by a new forward migration, never a rollback that could drop financial data. Migrations run on startup; an out-of-order or checksum-mismatched migration fails the boot (Flyway validate). **✅ Enforced in CI** by the `sbt migrationCheck` task (in `financial-gates`): it rejects data-destroying DDL — `DROP TABLE`/`DROP COLUMN`/`TRUNCATE`/`DROP SCHEMA`/`DROP DATABASE`/`DELETE FROM` — in any migration, while permitting safe object drops (`DROP INDEX`/`CONSTRAINT`/`TRIGGER`/`VIEW`/`TYPE`).
2. **Avro `schemaCheck` BACKWARD gate.** `sbt schemaCheck` validates every changed Avro schema against the registry's latest under **BACKWARD** (doc 03 §2); a breaking change fails the build. Breaking changes ship as a new `event_type`/version in parallel, never an in-place break — this is what keeps the event spine and every consumer safe across deploys.
3. **No-float gate.** The money lint rejects `Double`/`Float` in financial paths (doc 14 §1.1, CLAUDE.md §6) — a binary-float representation error can never reach a stored money value.

These three gates are themselves SOX change-management evidence (§B.6, doc 14 §4.4): a passing pipeline is the control artifact.

---

## Acceptance / verification

- **Latency:** a compliant ≤3-line order captures at **p95 < 300 ms** under steady load (doc 05 §6); pricing-quote p95 < 250 ms; scoped reads p95 < 200 ms; budgets are asserted from `conduit_order_capture_duration_seconds` in a load test, not aspirationally.
- **Scale/peak:** the system sustains the design-ceiling mean and absorbs **×5 daily / ×10 promotional / ×20 migration** bursts with bounded, draining backlog — no DLQ accumulation, projection lag recovers to p95 within 10 min of burst end.
- **Availability/RPO/RTO:** order-capture meets 99.9%/mo; a DR rehearsal restores Postgres (PITR ≤ 5 min RPO / ≤ 30 min RTO), confirms TB 0-loss replica rejoin, and **rebuilds a projection from S3-offloaded events** to prove the projection RTO — and re-performs a sample figure transfer→event→source.
- **Degradation:** with Pulsar / TigerBeetle / Xero / HubSpot deliberately down, **order capture still succeeds** (commits Postgres tx + outbox); downstream effects drain on recovery with no lost events.
- **Secrets:** no credential exists in the repo, image, `application.conf`, or Consul KV plaintext; the `secretScan` CI gate is green and fails a planted test secret.
- **Encryption:** RDS/EBS/S3 encrypted at rest (KMS); all transport TLS 1.2+; PII columns are ciphertext under a per-subject DEK.
- **GDPR erasure:** a verified erasure request crypto-shreds the subject's DEK, after which **a decrypt of every PII store fails** while the financial skeleton (orders, transfers, amounts) is intact and **still re-performs**; projections and HubSpot show the `«erased»` tombstone; the whole DSAR is maker-checker and audited; a restored backup re-applies the shred-list so erased stays erased.
- **Rate limiting:** a reseller token over quota gets `429 + Retry-After`; saturation alarms; the core tier is unaffected when the reseller tier throttles.
- **Threat model:** each of the five flows (order capture, pricing/ADLP, ledger posting, reseller API, auth) has its STRIDE mitigations realised by the cited controls and covered by tests (authz allow/deny/layer-strip per endpoint, doc 05 §6; idempotent-redelivery and balance tests for the ledger, doc 14 §5.4).
- **Observability:** every order is followable API → event → ledger → projection by its `correlation_id` across the structured JSON logs (the estate is metrics-only — no trace tree, §C.1); the operational metrics (`outbox_unpublished_count`, `dlq_depth`, `reconciliation_exception_count`, `logs_count{level}`) plus JVM and per-endpoint HTTP metrics are exported on :9464 / :9465 and alarmed per §C.3; logs carry no PII/secrets.
- **DLQ-replay:** a deliberately poisoned message lands on `<topic>.dlq`, pages on depth > 0; after the fix, scoped replay drains it to 0 with no double-effect (idempotent on `event_id`).
- **Projection-rebuild:** a projection truncated and rebuilt from `Earliest` (hot log + S3 offload) reconstructs **identically** (GL projection ties TB↔GL to the penny) via the same consumer code — no second write path.
- **CI migration-safety:** Flyway is forward-only and validates on boot; `schemaCheck` fails a BACKWARD-breaking Avro change; the no-float lint fails a `Double` in a money path; the ScalaCheck financial property suite is green — and each is recorded as a SOX change-management control artifact (doc 14 §4.4, §B.6).

> These three areas (NFR, Security, Ops/DR) are **P1 launch-blockers** and **cross-cutting** — they constrain every milestone in doc 07 rather than owning one, and discharge the three rows in doc 10 §D; Conduit does not go live until this document is green end-to-end.
