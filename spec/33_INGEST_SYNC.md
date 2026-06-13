# 33 — Continuous ingest & shadow dual-run (M-Ingest)

**Requested 2026-06-13 (CEO):** run Conduit *silently in parallel* for weeks-to-months — continuously synced
from every live source (Xero, HubSpot, MRPeasy, Athena, Stripe, …) — so we can polish the algorithms and
migrate all historical data against a moving target, then cut over when the books tie. The owner can stand up
webhooks where that helps.

This is the operational engine for the **shadow dual-run** that spec/18 §4 describes. spec/18 covers the
*one-time migration* + the *cutover gates* + the *authority order*; **this doc is the always-on sync** that
keeps Conduit in step with reality for the whole parallel window, and the framework that unifies "backfill the
history" and "keep it current" under one idempotent path. Read spec/18 §0 (source authority) and §4 (dual-run)
first — they are load-bearing here and not repeated.

## 1. Principles (non-negotiable)
1. **One write path.** An ingested record flows through the *same* domain write path a live action would —
   never a parallel shadow path. Backfill and incremental sync differ only in *where the records come from*
   (history vs delta), not in how they land. (This is why spec/18's `MigrationService` is reused, not forked.)
2. **Idempotent on `(source, source_id)`.** Every ingested record is recorded in `migration_record`
   (V1_0_14 — already the universal source-dedupe ledger: `source`, `entity_type`, `source_id`, `conduit_id`,
   `source_payload`, `source_hash`). Re-ingesting a record is a no-op; an *edited* source record is detected by
   `source_hash` drift (spec/18 §4.3) and re-applied idempotently. At-least-once everywhere; exactly-once effect.
3. **Cursors, not full scans.** Each `(source, dataset)` carries a resumable cursor in `sync_state`
   (a timestamp / id / page-token). Incremental pulls fetch only `> cursor`; the cursor advances only after the
   batch commits. A cold cursor = full backfill (the migration); a warm cursor = the steady-state delta.
4. **Authority order resolves conflicts** (spec/18 §0): when two sources assert the same fact, the authoritative
   source wins and the loser becomes a reconciliation cross-check — never a silent overwrite.
5. **Shadow mutes outbound.** In shadow mode Conduit ingests, computes, posts to its *own* ledger, and
   reconciles — but every **outbound, business-affecting** side-effect is suppressed (no Xero push, no HubSpot
   write-back, no customer-facing invoice/email, no Stripe charge). Conduit observes; it does not yet act.
6. **Continuous reconciliation is the product.** The whole point is the *diff*: a scheduled job compares Conduit's
   derived state to each source's truth (spec/18 §4.2) and writes the `reconciliation` table, surfacing every
   divergence on the Auditability dashboard. Polishing the algorithm = working those exceptions to zero.

## 2. The connector model
A single abstraction unifies every source (`domain/.../ingest`):

```
final case class SyncCursor(value: String)                 // opaque: timestamp / id / page-token
final case class IngestRecord(dataset: String, sourceId: String, payload: Json)
final case class IngestBatch(records: List[IngestRecord], nextCursor: Option[SyncCursor], complete: Boolean)

trait IngestConnector[F[_]] {
  def source: String                                       // "xero" | "hubspot" | "mrpeasy" | "athena" | "stripe"
  def datasets: List[String]                               // logical streams within the source
  def pullSince(dataset: String, cursor: Option[SyncCursor]): F[IngestBatch]
}
```

- **Pull** sources (Xero/HubSpot/MRPeasy/Athena) implement `pullSince`; the scheduler drives them on a cadence.
- **Push** sources (Stripe today; Xero/HubSpot webhooks if stood up) deliver to an `/ingest/<source>/webhook`
  route that normalizes to the *same* `IngestRecord` and runs the *same* handler — webhook and poll converge.
- **Normalization → write:** each `IngestRecord` is routed by `(source, dataset)` to a normalizer that maps it
  to a Conduit event/command and records `migration_record(source, entity_type, source_id, hash, conduit_id)`.
  Mapping rules + the dependency phase order live in spec/18 §1–§3.2.

## 3. `sync_state` + the scheduler
- **`sync_state`** — one row per `(source, dataset)`: `cursor`, `last_run_at`, `last_status`, `records_seen`,
  `records_written`, `consecutive_failures`, `last_error`. The scheduler reads/advances it; the desk renders it
  as the **sync-health board**.
- **`IngestScheduler`** — a Supervised consumer fiber (like the outbox relay). Per source/dataset cadence
  (config), it: load cursor → `pullSince` → normalize+write each record (idempotent) → advance cursor →
  upsert `sync_state` + emit `ConduitMetrics` gauges (`ingest_lag_seconds`, `ingest_consecutive_failures`).
  Backoff on failure; never advances the cursor past an uncommitted batch.
- **Replaces `scripts/refresh-feeds.sh`** as the automation: the manual script's pulls become scheduled
  connectors. (The script stays usable for a one-shot local refresh; the scheduler is the unattended path.)

## 4. Per-source plan
| Source | Mode | Cursor | Datasets → Conduit | Auth | Webhook? |
|---|---|---|---|---|---|
| **Stripe** | webhook (live) + backfill poll | `created` | charges/refunds/payouts → `payment`, D2C revenue | signing secret ✓ | **done** (`StripeWebhookRoutes`) |
| **Xero** | REST poll + webhook | `UpdatedDateUTC` | Invoices/CreditNotes/Payments/Contacts/BankTransactions → `order_invoice`/`payment`/`party` **cross-check** of Conduit's own (authority: Conduit derives, Xero reconciles) | OAuth2 (`<env>/conduit/xero/*`) | helpful: Invoices, Contacts, Payments |
| **HubSpot** | REST poll + webhook | `hs_lastmodifieddate` | deals/companies/contacts/line-items → `deal_snapshot`/`party`/pipeline | private-app token | helpful: deal/company/contact change |
| **MRPeasy** | REST poll (move off the scraper) | record `modified` ts | customer orders/shipments/stock-lots/POs/articles → `order`/`dispatch`/`lot_batch`/`serial`/`po` (inventory + landed-cost authority) | API key | none (poll) |
| **Athena** | Postgres read (tunnel) + `athena-placement-versioned` Pulsar | `created_at` / event seq | activations/retail orders/serials/`order_invoice`(Xero id) | DB tunnel + Pulsar | n/a (stream) |
| *(beneficial)* **Rhenus 3PL** | poll/file | shipment id | dispatch confirmations / POD → `dispatch` actuals | TBD | TBD |
| *(beneficial)* **SMMT/DVLA BEV** | snapshot (live) | period | exogenous registrations → forecast | — | n/a |

Authority per fact = spec/18 §0 matrix (inventory+lot cost→MRPeasy; genealogy→MRPeasy shipment; activations→GB/Athena stream; retail orders+invoices→Athena; parties→Athena retail / MRPeasy+UFE trade). Where Conduit is the *deriver* (e.g. its own AR/VAT/ledger), the source is a **reconciliation cross-check**, not an input.

## 5. Shadow mode & continuous reconciliation
- **`hypervolt.shadow = true`** (HOCON + `HYPERVOLT_SHADOW` env). When set, the outbound effectors short-circuit:
  `XeroInvoiceConsumer`/`InvoiceDispatcher` (no push), HubSpot replication (no write-back), document delivery
  (render + store WORM, but no send), Stripe charge creation. They **log what they would have done** (a
  `shadow_action` audit row) so the suppressed side-effects are themselves reviewable. The ledger, projections,
  and reconciliations run fully — Conduit keeps a complete parallel set of books.
- **`DualRunReconciler`** — a scheduled job (extends spec/18 §4.2) that, per domain (inventory / activations /
  AR / orders), compares Conduit's projection to the source snapshot and upserts `reconciliation` rows
  (tolerance 0 on money + units). Drift detection re-hashes `migration_record.source_hash` vs live (spec/18 §4.3).
  Output lands on the existing Auditability reconciliation dashboard — the daily "are we tracking reality?" read.
- **Exit criterion** (→ spec/18 §5/§6 cutover): a sustained window with every money/unit reconciliation `matched`,
  drift worked to zero, and the algorithm's forecast/accuracy stable. Then flip `shadow=false` and cut over.

## 6. Webhooks to stand up (owner's offer)
Stripe is already wired. These cut sync lag from the poll cadence to seconds and reduce API load:
- **Xero** → `POST /api/v1/ingest/xero/webhook` — subscribe Invoice, Contact, Payment events (Xero "webhooks"
  with the signing key in `<env>/conduit/xero/webhook_key`). Conduit verifies the `x-xero-signature` HMAC.
- **HubSpot** → `POST /api/v1/ingest/hubspot/webhook` — a private-app webhook subscription on
  `deal.propertyChange`, `company.*`, `contact.*` (HubSpot signs with the app secret; Conduit verifies v3).
- MRPeasy / Athena have no usable webhooks → stay on poll / the Pulsar stream.
Each webhook route just normalizes to `IngestRecord` and runs the shared handler — identical to the poll path,
so a webhook is a latency optimization, never a second code path.

## 7. Slices (test-first)
1. **Framework** *(M-Ingest.1)* — `sync_state` migration; `IngestConnector`/`IngestBatch`/`SyncCursor`;
   `SyncStateRepo` (load/advance cursor, record run); `IngestScheduler` (Supervised, cadence, backoff, metrics);
   a `StaticConnector` test double proving cursor-advance + idempotent re-pull + failure-no-advance.
2. **Xero inbound** *(M-Ingest.2)* — `XeroConnector.pullSince` (Invoices/Contacts/Payments) + the
   `/ingest/xero/webhook` route (HMAC verify) → reconciliation cross-check of Conduit's AR.
3. **HubSpot inbound** *(M-Ingest.3)* — deals/companies/contacts → `deal_snapshot`/party; webhook.
4. **MRPeasy API** *(M-Ingest.4)* — replace the scraper with the REST connector (orders/shipments/lots/POs).
5. **Athena live** *(M-Ingest.5)* — the tunnel reader as a connector + the placement Pulsar stream, dual-fanned.
6. **Shadow + continuous reconcile** *(M-Ingest.6)* — `hypervolt.shadow` mutes the outbound effectors (+
   `shadow_action` audit); `DualRunReconciler` scheduled job writing the reconciliation dashboard.
7. **Desk sync-health view** *(M-Ingest.7)* — `sync_state` per source (cursor, lag, last status, drift count) +
   the dual-run reconciliation board, layer-aware.

**Acceptance:** with shadow on, every source syncs on its cadence/webhook, re-running an ingest changes nothing
(idempotent on `source_id`), an edited source row is detected and re-applied, no outbound side-effect fires
(only `shadow_action` rows), and the dual-run reconciliation board shows Conduit tracking each source to the
penny/unit — sustained, that is the cutover green light (spec/18 §6).
