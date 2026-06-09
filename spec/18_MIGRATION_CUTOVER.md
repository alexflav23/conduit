# 18 — Migration & Cutover (MRPeasy / Ghost Busters / Athena → Conduit)

This document specifies the **field-level source→target mapping**, **opening-balance derivation** into TigerBeetle, the **idempotent backfill** mechanism, **dual-run reconciliation**, **cutover validation** and the **rollback** plan for bringing Conduit live as Hypervolt's system of record. It supports milestone **M10** (doc 07) and discharges the backlog row "Migration & cutover runbook" (doc 10 §B) — flagged there as *the biggest practical go-live risk*.

Design stance carries over from the pack: **the ledger is the truth, the truth is exact integers, and every migrated figure traces back to its source row** (doc 14). Migration is therefore not a one-off ETL script — it is a **first-class, replayable, audited subsystem** that uses the same event/outbox/projection spine as live operation (doc 01 §2, doc 03), so that the same code path that rebuilds projections in production rebuilds them from history during backfill. Nothing about the migration architecture differs from the runtime architecture; only the *source* of the events differs (a backfill emitter vs. live API writes).

Cross-refs: opening balances post per doc 04 §Ledger; costing is strict specific-identification at batch landed cost (doc 02 §G, doc 04 §Inventory); provenance is captured in `migration_record` (doc 02 §L); controls that must be green are the SOX/ICFR `control`/`reconciliation` register (doc 14 §4–5, doc 02 §N); events follow the envelope and `BACKWARD` rules in doc 03.

---

## 0. Source systems (what each one owns today)

The three legacy systems each hold a *partial* truth. Conduit unifies them; the migration must resolve them against each other, not import them in isolation.

| Source | Stack | What it is the SoR for today | Access for migration | Known data hazards |
|---|---|---|---|---|
| **MRPeasy** | External manufacturing/inventory SaaS | Manufactured **articles** (BOM SKUs), **stock lots & quantities**, **lot/landed cost** (`total_cost`/`avg_cost`), **customer orders + shipments**, supplier POs | **Read-only export/API** — JSON article export (`mrp_articles.json` shape) + REST for orders/shipments/stock-lots/POs; **no write-back** | MRPeasy carries **`avg_cost` (weighted-average)** — Conduit is **strict specific-identification** (doc 02 §G); WIP rows (`wip_quantity`/`wip_cost`) are **not** finished-goods inventory and must be excluded; component SKUs (`HYPV-INV-01` group) are not sellable variants |
| **Ghost Busters** | Scala/Postgres + Pulsar consumer | **Charger activations** (`charger_activation`: serial, placement_id/version, installer, owner keycloak, model/MAC/country), a **mirror** of Athena/MRP `serial_number` shipment data, HubSpot sync logs | **Read-only** Postgres dump + the live `athena-placement-versioned` Pulsar stream | The `serial_number` mirror is a **stale copy** (the documented Ghost Busters pain, doc 01 §3); treat it as a **cross-check**, not a source of record. Activations may pre-date our serial→batch knowledge |
| **Athena** | Scala/http4s/Postgres | **Retail web orders** (`orderoonie`, `mrp_order_id`), **catalogue/pricing** (`product` sku/mrp_sku/trade_sku, `price`), `customer`, `serial_number` (with `source`, MRP + Rhenus shipment fields), `order_invoice` (Xero `invoice_id`), `tax_regime`, installer events | **Read-only** Postgres dump + replay of its SQS/event history if needed | Money is `DECIMAL(12,2/4)` (Conduit is `NUMERIC(18,4)`); `serial_number` is partly a **copy of MRPeasy data**; retail post-cutover continues to **feed** Conduit (doc 01 §4), so Athena is both a *migration source* and a *live upstream* |

> **Authority order (conflict resolution).** When two sources disagree on the same fact, the **authoritative source wins** per the matrix below; the loser becomes a reconciliation cross-check, never a silent overwrite.
> - **Inventory quantity & lot cost** → **MRPeasy** (manufacturing SoR).
> - **Serial → batch genealogy** → **MRPeasy shipment** (`mrp_shipment_id`); Athena/GB `serial_number` cross-check only.
> - **Activations (installer/owner/clock)** → **Ghost Busters `charger_activation`** (it is the materialised first-write-wins record) reconciled against the live `athena-placement-versioned` stream.
> - **Retail orders & invoices (Xero ids)** → **Athena**.
> - **Parties (retail consumers)** → **Athena `customer`**; **trade/installer parties** → MRPeasy customer + UFE installer identity, merged.

---

## 1. Field-level source→target mapping

Each subsection is a **mapping table** (source field → Conduit target column) plus the **transform** and the **`migration_record` provenance** written for every row. Conventions: `migration_record.source ∈ {mrpeasy, ghostbusters, athena}`; `entity_type` = the Conduit table; `source_id` = the natural key in the source; `conduit_id` = the created UUID. All money is parsed into typed `Money` (doc 14 §1) — **no float ever touches a cost** — and FX is provenanced (doc 14 §1.4).

### 1.1 Parties (Athena `customer` + MRPeasy customer + UFE installer → `party`)

| Source | Source field | → Conduit `party` / sub-table | Transform |
|---|---|---|---|
| Athena | `customer.id` (retail) | `external_refs.athena_customer_id` | retail buyer → `party_type='individual'` + `individual_details` |
| Athena | `customer.email/name/country` | `individual_details.email/…`, `address` | normalise email (CITEXT); country → `market` lookup |
| MRPeasy | `customer_id`/`customer_code`/`customer_name` | `party` (org), `external_refs.mrp_customer_id/code` | trade/wholesaler → `party_type='wholesaler'`/`distributor`; branches via `parent_party_id` if MRP encodes a hierarchy |
| Ghost Busters | `charger_activation.installer_user_id/email/name` | `party` (`party_type='installer'`), `external_refs.ufe_installer_id` | dedupe installers by UFE id then email; an installer that also *buys* gets a `billing_profile` attached (doc 02 §C) |
| Athena | `tax_regime` rows | `tax_regime` (already generalised, doc 02 §A) | map Athena codes → Conduit `tax_regime.code` |

**Dedupe / merge.** Run a deterministic merge pass *before* loading: a party may exist in all three sources (an installer who buys via MRPeasy and activates via GB). Match priority: `external_refs` exact id → tax-registration number → normalised email → fuzzy name+postcode (manual review queue, never auto-merged when only fuzzy). The merge decision is itself audited (`migration_record` per source id, all pointing at the surviving `conduit_id`). Billing/credit profiles are **not** invented — a party only becomes billable when a complete `billing_profile` is derivable (doc 02 §C); otherwise it lands as a non-billing party (activation/referral role) for later promotion.

### 1.2 Catalogue / variants / `mrp_sku` (Athena `product` + MRPeasy article → `product_variant`)

| Source | Source field | → `product_variant` | Transform |
|---|---|---|---|
| Athena | `product.sku` | `sku` (canonical retail) | UNIQUE; the join key across systems |
| Athena | `product.trade_sku` | `trade_sku` | |
| Athena/MRP | `product.mrp_sku` ↔ MRPeasy `product_code` | `mrp_sku` | **the spine of the mapping** — links a sellable variant to its MRPeasy article; one variant ↔ one `mrp_sku` |
| Athena | `product.retail_charger` | `is_serialised`, `generation` | charger → `is_serialised=true`; generation inferred from family/SKU pattern (`HYPV-`→v2, `0301…`→v3) |
| derive | family from SKU/title | `family_id → product_family` | seed `product_family` (`Home 2.2`/`Home 3.0`/`Home 3 Pro`) and bucket variants |
| MRPeasy | `group_code` (`HYPV-INV-01` etc.) | **exclusion filter** | `Product Components`/`Consumables` groups are **not** sellable variants — load as components only if kits are modelled (doc 10 §C), else skip |
| MRPeasy | `avg_cost` | `std_cost` (reference only) | **NOT** a costing source — `std_cost` is reference; real cost is per-lot (§1.3) |

> **`mrp_sku` mapping is the single most error-prone artifact.** It is produced as a **reviewed CSV** (`variant_sku, mrp_sku, family, generation, is_serialised`), checked into the repo, validated in CI (every Athena `product.mrp_sku` resolves to exactly one MRPeasy `product_code`; no orphans either way), and is the input to the catalogue load. Unmapped SKUs block go/no-go (gate G1).

### 1.3 Lot/batch + landed cost (MRPeasy stock lots → `lot_batch`)

MRPeasy holds quantity and a (weighted-average) cost; Conduit needs **per-lot landed cost in USD with provenanced FX** (doc 02 §G, doc 04 §FX). Where MRPeasy exposes discrete receipt lots, map one lot → one `lot_batch`. Where it only exposes an aggregate article balance (`mrp_articles.json`), create a **single synthetic opening lot per variant per location** carrying the article's cost basis, flagged `migration_record` and `attributes.synthetic_opening=true`.

| Source | Source field | → `lot_batch` | Transform |
|---|---|---|---|
| MRPeasy | lot/receipt id (or `article_id` for synthetic) | `batch_no` | scheme `MIG-<MRP_SKU>-<YYYYMM>-<seq>` (distinct prefix `MIG-` marks migrated lots vs the live `LUX-…` scheme, doc 07) |
| MRPeasy | supplier (Luxshare) | `supplier_id → supplier` | seed Luxshare supplier; `billing_currency='USD'` |
| map | `product_code` → variant | `product_variant_id` | via the §1.2 `mrp_sku` map |
| MRPeasy | `quantity` (finished only) | `qty` | **exclude `wip_quantity`** |
| MRPeasy | `total_cost`/`quantity` (per-unit) **or** discrete lot unit cost | `unit_cost_usd` | parse to `Money(USD)`; if MRPeasy holds GBP/local, convert at the **provenanced historical rate** and record source — never silently |
| derive | historical USD→functional rate at receipt date | `fx_rate`, `fx_basis='spot'`, `hedge_ref=NULL` | from `exchange_rate` (load the historical rates first); migrated lots are **not** retro-designated to hedges |
| MRPeasy | freight/duty if available, else 0 | `shipping_alloc`, `duty_alloc` | if landed components are unknown historically, set 0 and record `attributes.landed_cost_partial=true` (valuation caveat surfaced to audit) |
| compute | | `landed_unit_cost` | `(unit_cost_usd × fx_rate) + perUnit(freight) + perUnit(duty)` (doc 04 §FX) — the value every serial inherits |

**Specific-identification reconciliation of weighted-average source.** MRPeasy's `avg_cost` is a *weighted-average* artifact incompatible with Conduit's costing. We do **not** import the average as a cost. Instead: where discrete lots exist, each carries its own cost; where only an aggregate exists, the synthetic opening lot's `unit_cost_usd` is set so that `Σ(qty × landed_unit_cost)` across opening lots equals MRPeasy's reported inventory value **to the penny** (the largest-remainder allocation, doc 14 §1.3, distributes any rounding residue). This makes the opening **inventory asset** tie exactly while honouring specific-ID going forward.

### 1.4 Serials + genealogy (MRPeasy shipment + Athena `serial_number` → `serial_unit` + `unit_lifecycle_event`)

| Source | Source field | → `serial_unit` | Transform |
|---|---|---|---|
| MRP/Athena | `serial_number.id` | `serial_no` (UNIQUE) | normalise hex/device id |
| derive | prefix (`HYPV-`/`0301`) | `generation` | `v2`/`v3` (drives activation maths, doc 04 §H6Q) |
| map | shipment `product_code` → variant | `product_variant_id` | §1.2 map |
| map | shipment lot → `lot_batch` | `lot_batch_id` | **specific-ID link**; if the historical serial→lot link is missing, assign to the variant's synthetic opening lot and flag `attributes.lot_inferred=true` |
| Athena | `serial_number.mrp_status` / source / Rhenus fields | `status`, `entity_id`, `location_id` | shipped-to-customer serials → `dispatched`; on-hand → `in_stock`; activated (cross-ref §1.5) → `activated` |
| Athena | `serial_number.mrp_customer_*` | `company_id` | resolve via §1.1 party map (the account we shipped to) |
| Athena | Rhenus `rhenus_shipped_date` | `unit_lifecycle_event('dispatched')` | build the genealogy chain (manufactured→received→stocked→dispatched→[delivered]→[activated]) |

Each migrated serial emits the appropriate `unit_lifecycle_event` rows so the **genealogy projection is rebuilt by the same consumer that runs live** (doc 02 §G `unit_lifecycle_event`, doc 03 `serial.lifecycle`). The GB `serial_number` mirror is **only** used to detect discrepancies (a serial GB knows about that MRP doesn't, or vice-versa) → reconciliation exception, not an import.

### 1.5 Activations (Ghost Busters `charger_activation` → `activation` + warranty)

| Source | `charger_activation` field | → `activation` | Transform |
|---|---|---|---|
| GB | `serial` | `serial` (PK) | first-write-wins per serial (doc 04 §Activation) |
| GB | `placement_id`/`placement_version` | `placement_id`/`placement_version` | version 1 = installer placement |
| GB | `installer_user_id/email/name` | `installer_user_id/email/name` | link installer party (§1.1) |
| GB | `placement_name/country` | `placement_name/country` | country → jurisdiction for legal warranty |
| GB | `charger_model/mac/keycloak_id` | `charger_model/charger_mac/charger_keycloak_id` (owner) | |
| GB | `placement_created_at` / `created_at` | `placement_created_at` / `activated_at` | **the activation date = warranty clock start** (doc 04 §Warranty) |

For every migrated activation, run the **same `onActivation` logic** (doc 04 §Activation) so it binds the serial, flips it off-shelf, and **opens a `warranty_provision`** using the `warranty_rate` effective at the *historical* activation date and the serial's *specific* `lot_batch.landed_unit_cost` (doc 04 §Warranty). Then **roll `releaseSchedule` forward to cutover date** so the migrated consolidated exposure equals what a live system would show today. v2 (`HYPV-`) activations are recorded but excluded from on-shelf/sell-through maths (doc 04 §H6Q). This is precisely the "retroactive backfill" the warranty spec already requires (doc 02 §G, doc 04 §Warranty) — migration is its first execution.

### 1.6 Shipments & retail orders (Athena `orderoonie` + `order_invoice` → `order` / `dispatch` / `order_invoice`)

Historical orders are migrated **as closed history** (status reflecting reality), not re-driven through the live state machine — we do not re-allocate or re-dispatch the past.

| Source | Source field | → Conduit | Transform |
|---|---|---|---|
| Athena | `orderoonie.id` | `order.order_no`, `external_refs.athena_order_id` | |
| Athena | `orderoonie.sales_channel`/`country_code`/`currency` | `channel_id`/`market_id`/`txn_currency` | map to seeded channel/market |
| Athena | `customer_id` | `sold_to_party_id`/`bill_to_party_id` | via §1.1 |
| Athena | `total_amount` (DECIMAL 12,4) | `total_inc_vat` + derived `subtotal_ex_vat`/`vat_total` | parse to `Money`; back-out VAT at the line's `tax_regime` |
| Athena | `state`/`paid`/`payment_method`/`payment_intent` | `order.status`, `payment_method`, `stripe_payment_intent` | closed orders → `closed`; open orders (§1.7) handled separately |
| Athena | `mrp_order_id` | `external_refs.mrp_order_id` | cross-link to MRPeasy shipment for serial genealogy |
| Athena | `order_invoice.invoice_id` | `order_invoice.xero_invoice_id` | **preserve the Xero id** — opening AR must not double-invoice into Xero post-cutover |
| Athena | Rhenus shipment → serials | `dispatch` + `dispatch_line` + serial links | rebuild dispatch genealogy; `delivered_at` from Rhenus shipped date |

### 1.7 Open orders (the live carry-over)

Open/unfulfilled orders at cutover are the one class that **must** enter the live state machine (they will be allocated/dispatched/invoiced *after* cutover by Conduit). Mapping is as §1.6 but:
- status mapped to the matching live state (`placed`/`allocated`/`partially_dispatched`), **not** `closed`;
- **no ledger revenue/COGS** is posted for the unfulfilled remainder (revenue recognises on the *future* delivery, ASC 606 — doc 04 §Ledger); only the **order commitment** is recorded;
- already-dispatched-but-uninvoiced lines (if any) get their AR/COGS opening transfer (§2) so the future invoice is not double-counted;
- scheduled/call-off remainder maps to `delivery_tranche` rows (doc 02 §F) for forward fulfilment & supply planning.

Open-order count and value is a **named go/no-go input** (gate G3): finance signs off the open-order schedule before cutover.

---

## 2. Opening-balance derivation into TigerBeetle

Opening balances are posted as **audited opening transfers** — real TigerBeetle transfers (doc 04 §Ledger), not a special "balance" type — against an `OPENING_BALANCE_EQUITY:<entity>` contra account, so that **debits == credits per currency** and the migrated books balance by construction (doc 14 §1.5). Every opening transfer's `id` is **deterministic from the `migration_record`** (`hash(source, entity_type, source_id, leg)`), so re-running the backfill is a no-op (§3, idempotency). Each is emitted via the outbox as `ledger.posted` with `causation_id` = the migration batch id, so downstream (Xero, GL projections) sees provenanced opening entries.

| Opening balance | Posting (per currency ledger) | Source of the amount |
|---|---|---|
| **Inventory asset** | DR `INV:<entity>`, CR `OPENING_BALANCE_EQUITY:<entity>` at **Σ(qty × `landed_unit_cost`)** per `lot_batch` | §1.3 lots (specific-ID; ties to MRPeasy inventory value to the penny) |
| **Accounts receivable** | DR `AR:<bill_to>`, CR `OPENING_BALANCE_EQUITY` for each **open invoice** | Athena `order_invoice` + open balances; preserves `xero_invoice_id` so Xero is not re-billed |
| **Accounts payable** | DR `OPENING_BALANCE_EQUITY`, CR `AP:<supplier>` for open supplier balances | MRPeasy open POs / supplier statements |
| **Warranty provision** | recorded in `warranty_provision` register; **balance-sheet posting is downstream** (doc 02 §G) — Conduit emits `warranty.provision.accrued` with the migrated exposure, the P&L/GL consumer books the liability | §1.5 rebuilt provisions rolled forward to cutover |
| **In-transit / VAT control** | DR/CR `IC`/`VAT` clearing as applicable | open stock transfers, unremitted VAT if carried |

**Costing rule (load-bearing):** the inventory opening transfer uses the serial's / lot's **specific landed cost** — there is no weighted-average anywhere, including at migration (doc 02 §G, doc 04 §Ledger). The synthetic-opening-lot construction in §1.3 is what reconciles MRPeasy's average-valued balance to a specific-ID ledger **to the penny** via largest-remainder allocation (doc 14 §1.3).

**Period & FX.** Opening transfers carry `occurred_at` = the **cutover instant** (a single UTC instant), so period assignment is a clean projection (doc 14 §2). FX on historical lot cost uses the **provenanced historical rate** loaded into `exchange_rate` first; the opening *balance-sheet* translation to USD presentation uses the cutover-date closing rate (ASC 830, doc 14 §3). Both rates are recorded; neither is implicit.

**Opening-balance trial balance.** After posting, an automated check asserts `Σ debits == Σ credits == 0 net against OPENING_BALANCE_EQUITY` per currency (doc 14 §5.4 ledger-balance property). A non-zero residual blocks cutover (gate G4).

---

## 3. Idempotent backfill (re-runnable, dedupe on source id, via the event replay path)

The backfill is **not** a bespoke importer that writes tables directly. It is a **backfill emitter** that produces the *same domain events* the live API would produce (`crm.company.created`, `inventory.received`, `serial.lifecycle`, `activation.recorded`, `order.placed`, `ledger.posted`, …, doc 03), pushed through the **outbox → Pulsar → the normal consumers**. Projections, the genealogy, H6Q coverage, sell-through, the warranty register and the ledger are therefore **rebuilt by exactly the production code** (doc 01 §3a — "any new consumer can be backfilled by replay"). This is the single biggest de-risking decision: there is no second, divergent write path to test.

### 3.1 Mechanism

```
backfill(source, entityType, sourceRows):
  for row in sourceRows (ordered by natural occurred_at):
     srcId = naturalKey(row)
     # dedupe: have we already migrated this exact source row?
     existing = migration_record WHERE source=:source AND entity_type=:entityType AND source_id=:srcId
     if existing: continue                          # idempotent — re-run is a no-op
     conduitId = deterministicUuid(source, entityType, srcId)   # stable across re-runs
     event = mapToEvent(row, conduitId)             # §1 mapping; envelope occurred_at = historical instant
     event.event_id = deterministicUuid(source, entityType, srcId, "evt")   # idempotent on event_id (doc 03 §3)
     event.actor = "system:migration"
     event.correlation_id = :batchId
     persistBusinessRow(conduitId, row) + outbox(event) + migration_record(source, entityType, srcId, conduitId, reconciled=false)
     # ↑ all in ONE Postgres transaction (doc 01 §2) — business row, outbox, and provenance commit atomically
  # the relay publishes; the normal consumers build projections & post the ledger, deduping on event_id
```

Idempotency holds at **three** layers, any one of which makes a re-run safe:
1. **`migration_record` dedupe** on `(source, entity_type, source_id)` — the row is skipped if already migrated.
2. **Deterministic `event_id`** — even if an event is re-emitted, every consumer dedupes on `event_id` (doc 03 §3); the ledger's deterministic `tb_transfer_id` (doc 04 §Ledger) makes the opening transfer a no-op on replay.
3. **Deterministic `conduit_id`** — the same source row always maps to the same Conduit UUID, so re-runs never fork identities.

This means the backfill is **restartable after a crash**, **incremental** (new source rows only), and **replayable** (drop a projection, re-emit from the retained log, doc 01 §3a). A failed batch is resumed, not restarted from zero.

### 3.2 Ordering & dependencies

Backfill runs in dependency order (each phase's events must be consumed before the next so FKs/genealogy resolve):

```
1 reference data: entity, market, channel, currency, exchange_rate (historical), tax_regime, supplier, warranty_rate, legal_warranty
2 catalogue:      product_family, product_variant (+ mrp_sku map), kit_component
3 parties:        party (merged), individual_details, billing_profile, credit_profile, address, contact
4 inventory:      lot_batch (+ landed cost) → serial_unit → unit_lifecycle_event   (emits inventory.received → opening INV ledger)
5 activations:    activation → warranty_provision (roll release forward)            (emits activation.recorded, warranty.provision.accrued)
6 orders:         closed orders+invoices (history) ; open orders (live state)       (emits order.placed/…; opening AR/AP ledger)
7 reconcile:      run all reconciliations (§4–5), set migration_record.reconciled
```

Within a phase, ordering is per `partition_key` only (doc 03 §3); cross-phase ordering is enforced by **phase gates** (a phase completes — all events consumed, checkpoint caught up — before the next starts), observed via `consumer_checkpoint` (doc 02 §L).

### 3.3 What `migration_record` records (extension)

`migration_record` (doc 02 §L) is the provenance spine. The base columns (`source`, `entity_type`, `source_id`, `conduit_id`, `migrated_at`, `reconciled`) are **extended** here so the cutover is auditable end-to-end and partial loads are visible:

```sql
ALTER TABLE migration_record
  ADD COLUMN batch_id        UUID         NOT NULL,                       -- the backfill run (correlation_id)
  ADD COLUMN source_payload  JSONB        NOT NULL,                       -- the raw source row, retained for re-performance (doc 14 §5)
  ADD COLUMN source_hash     TEXT         NOT NULL,                       -- hash of source_payload; detects source drift on re-run
  ADD COLUMN event_id        UUID         NULL,                           -- the emitted event (deterministic), for replay
  ADD COLUMN tb_transfer_id  NUMERIC(39,0) NULL,                          -- the opening transfer, where this row posted money
  ADD COLUMN phase           INTEGER      NOT NULL,                       -- §3.2 dependency phase
  ADD COLUMN status          TEXT         NOT NULL DEFAULT 'loaded',      -- loaded/reconciled/exception/superseded
  ADD COLUMN caveats         TEXT[]       NOT NULL DEFAULT '{}',          -- e.g. {landed_cost_partial, lot_inferred, synthetic_opening, fuzzy_merge}
  ADD COLUMN reconciled_at   TIMESTAMPTZ  NULL,
  ADD COLUMN reconciled_by   UUID         NULL;                           -- → app_user (sign-off, maker≠checker)

CREATE UNIQUE INDEX uq_migration_source ON migration_record(source, entity_type, source_id);  -- the dedupe key
CREATE INDEX ix_migration_batch   ON migration_record(batch_id, phase);
CREATE INDEX ix_migration_status  ON migration_record(status) WHERE status <> 'reconciled';
CREATE INDEX ix_migration_caveats ON migration_record USING GIN(caveats);
```

`source_payload` + `source_hash` make every migrated figure **re-performable** (doc 14 §5.1) — an auditor can trace a ledger figure → transfer → event → `migration_record.source_payload` → the legacy row. `source_hash` also detects if a source row *changed* between a dry run and the real run (a hazard with a live MRPeasy) and forces re-review rather than silently re-importing.

### 3.4 CLI surface (the runbook's hands)

A migration CLI (same Scala deployable, an admin-gated subcommand; **`admin` + `migration:run` permission**, doc 05) drives it:

```
conduit-migrate plan        --source <mrpeasy|ghostbusters|athena|all> --phase <n|all>   # dry-run: counts, mappings, unmapped SKUs, FX gaps — writes NOTHING
conduit-migrate validate    --source all                                                 # pre-flight: mrp_sku map complete, FX rates present, parties merged
conduit-migrate run         --source all --phase <n> --batch <id>                        # idempotent backfill of a phase
conduit-migrate status      --batch <id>                                                 # per-phase counts loaded/reconciled/exception
conduit-migrate reconcile   --type <inventory|ar|ap|serials|activations|all>             # §4–5 reconciliations → reconciliation rows
conduit-migrate freeze      --confirm                                                    # source freeze marker (read-only window begins)
conduit-migrate cutover     --confirm --gates G1..G6                                     # flips Conduit to system-of-record (requires all gates green)
conduit-migrate rollback    --batch <id> --confirm                                       # §7 — reverse via compensating events
```

`plan`/`validate` are read-only and run as often as wanted in lower environments; `run`/`cutover`/`rollback` are maker-checker, audited (`audit_log`, doc 02 §L), and require the gate evidence (§6) attached.

---

## 4. Dual-run reconciliation (Conduit in parallel with legacy)

Before cutover, Conduit runs **in parallel** with the legacy systems for a bounded window (default **2–4 weeks**, finance-set). Both systems process the *same* live inputs; a reconciliation engine compares them continuously and surfaces every divergence. **Conduit is shadow** during this window — it is not the SoR yet, it makes no outbound writes that the business depends on (Xero feed muted, HubSpot replication muted), it only computes and compares.

### 4.1 What runs in parallel

- **Activations** — the live `athena-placement-versioned` stream fans out to *both* Ghost Busters (legacy) and Conduit's activation consumer; Conduit dedupes first-write-wins (doc 04 §Activation). Reconcile daily: every serial GB activates, Conduit activates identically (installer, owner, clock start).
- **Retail orders** — Athena continues to be the retail processor and **feeds Conduit the completed sale** (doc 01 §4); Conduit records the sale (stock, ledger, genealogy, sell-through) shadow. Reconcile: order count, value, VAT, the resulting inventory relief.
- **Inventory** — Conduit's inventory ledger is reconciled nightly against the **live MRPeasy stock balance** (`reconciliation.type='inventory_vs_count'`, doc 02 §N).
- **AR/AP** — Conduit's AR/AP sub-ledger reconciled against Athena `order_invoice`/Xero and MRPeasy supplier balances (`tb_vs_gl`, `gl_vs_xero`, `ar_vs_invoices`).

### 4.2 The comparison

```
dualRunReconcile(domain, asOf):
  legacy  = legacySnapshot(domain, asOf)     # MRPeasy stock / Athena orders+invoices / GB activations
  conduit = conduitProjection(domain, asOf)  # the live projection / ledger balance
  for key in union(legacy.keys, conduit.keys):
     variance = conduit[key] - legacy[key]
     status   = (abs(variance) <= tolerance(domain)) ? 'matched' : 'exception'
     upsert reconciliation(type=mapType(domain), period_id, scope={key}, expected=legacy, actual=conduit,
                           variance, currency, status)
  # tolerance(inventory)=0 (must tie to the penny / unit); tolerance(activations)=0 units;
  # tolerance(ar)=0 to the penny. There is no "close enough" on money or units.
```

Reconciliations write the existing `reconciliation` table (doc 02 §N, doc 14 §5.2) so they show up in the **Auditability Center reconciliation dashboard** (doc 14 §6) — the same surface finance uses at every period close. Exceptions are worked to zero (root-caused: a mapping gap, a stale GB mirror row, an FX rounding, a genuine legacy error). **No-go if any money/unit reconciliation is in `exception` at the cutover gate.**

### 4.3 Drift detection

A daily job re-hashes the migrated source rows (`migration_record.source_hash`) against live MRPeasy/Athena to catch **post-migration source drift** during the dual-run window (a record edited in MRPeasy after we migrated it). Drift → an exception + a targeted re-run of that row (idempotent, §3.1).

---

## 5. Cutover validation — a physical stock count that ties to the penny

The decisive validation (doc 07 M10 acceptance: *"validated by a stock count at cutover"*) is a **full physical count** at the freeze instant, reconciled both in **units** and in **value** against the migrated inventory ledger.

```
cutoverStockValidation(location, countedLines, asOf=freezeInstant):
  # 1. units tie
  for variant in countedLines:
     systemQty = Σ stock_movement(variant, location) up to asOf        # Conduit on-hand (doc 04 §Stock)
     variance  = counted_qty - systemQty
     reconciliation(type='inventory_vs_count', scope={variant,location}, expected=counted_qty,
                    actual=systemQty, variance, status= variance==0 ? 'matched':'exception')
  require all variant variances == 0                                    # GATE: units tie exactly

  # 2. value ties to the penny (specific-ID)
  countedValue  = Σ over counted serials/lots: serial.lot_batch.landed_unit_cost   # specific-ID, doc 02 §G
  ledgerINV     = TigerBeetle balance INV:<entity> at asOf                          # doc 04 §Ledger
  reconciliation(type='tb_vs_gl', scope={INV, entity}, expected=countedValue, actual=ledgerINV,
                 variance=ledgerINV-countedValue, status= variance==0 ? 'matched':'exception')
  require variance == 0                                                  # GATE: inventory ledger ties to the count to the penny
```

Serialised stock is counted by **scanning serials** (`stock_count_line.serials_scanned`, doc 02 §G), so the count validates **genealogy** too — every scanned serial must resolve to a Conduit `serial_unit` with the right `lot_batch`, status and location. A scanned serial unknown to Conduit, or a Conduit `in_stock` serial not on the floor, is an exception that must clear. Variances that are *real* (genuine legacy shrinkage) post as maker-checker `count_correction` movements **before** cutover so the opening books reflect physical reality (doc 04 §Stock ops), and the corrected ledger is what becomes the opening balance.

---

## 6. Phased runbook with go/no-go gates

Each phase has an owner, an exit gate, and the **controls (doc 14 §4)** that must be green to pass. A gate is **green only with attached evidence** (a `control_run` pass, a `reconciliation` matched, or a signed `period_close_task`-style checklist item) — never a verbal "looks fine".

| # | Phase | Actions | Exit gate | Controls that must be green |
|---|---|---|---|---|
| **P0** | **Prep** | Stand up Conduit prod (spine M1–M9 green, doc 07); load reference data (entities, markets, FX history, tax regimes, suppliers, warranty rates); build & CI-validate the **`mrp_sku` map** (§1.2); merge parties (§1.1, manual-review queue cleared) | **G1: catalogue & mapping complete** — every Athena `product.mrp_sku` resolves to one MRPeasy article; zero orphans; party merge queue empty | `CTRL-MIG-MAP` (mapping completeness), `CTRL-FX-HIST` (historical rates present for all lot receipt dates) |
| **P1** | **Backfill (dry run)** | `conduit-migrate plan/validate` against a **prod-data snapshot** in staging; full phases 1–6; run all reconciliations; review caveats (`landed_cost_partial`, `lot_inferred`, `synthetic_opening`) | **G2: dry-run reconciles** — staging inventory/AR/AP/serial/activation reconciliations all `matched`; opening trial balance nets to zero per currency | `CTRL-LEDGER-BAL` (Σdebits=Σcredits), `CTRL-RECON-INV`, `CTRL-RECON-AR`, `CTRL-RECON-AP` |
| **P2** | **Backfill (prod) + Dual-run** | Run prod backfill (§3); start the **parallel dual-run window** (§4) — Conduit shadow, Xero/HubSpot muted; reconcile daily; work exceptions to zero; drift detection on | **G3: dual-run clean** — N consecutive days (default 5) with all money/unit reconciliations `matched`; **open-order schedule signed off** by finance (§1.7) | `CTRL-RECON-*` daily pass, `CTRL-MIG-DRIFT` (no un-reviewed source drift), `CTRL-ACTIVATION-PARITY` (GB↔Conduit activations identical) |
| **P3** | **Reconcile & freeze** | Announce freeze; `conduit-migrate freeze` — legacy systems go **read-only** (no new MRPeasy shipments/Athena orders processed as SoR); take the **freeze-instant snapshot**; final incremental backfill of the freeze delta | **G4: opening balances exact** — opening trial balance nets to zero; AR opening = open invoices to the penny; AP opening = supplier balances | `CTRL-LEDGER-BAL`, `CTRL-PERIOD` (opening period open, not locked) |
| **P4** | **Cutover** | **Physical stock count** (§5) at the freeze instant; tie units + value to the penny; post any real variances as maker-checker corrections; `conduit-migrate cutover --gates G1..G6` flips Conduit to **system-of-record**; un-mute Xero feed + HubSpot replication; route live inputs (activations stream, Athena retail feed, MRP→Conduit purchasing) to Conduit as SoR | **G5: stock count ties** — every variant variance 0; `INV` ledger == counted value to the penny (§5); all serials resolve genealogy | `CTRL-RECON-INV` (zero tolerance), `CTRL-COUNT-SERIAL` (every scanned serial resolves), maker≠checker on corrections |
| **P5** | **Verify (hypercare)** | Smoke the golden path live (place→allocate→dispatch→deliver→invoice→activate→commission, doc 07 test strategy); confirm first live invoice reaches Xero **without** re-billing a migrated invoice; first period-close reconciliations run green; keep legacy read-only for the rollback window (default 2 weeks) | **G6: live & reconciled** — first live transactions post correctly; first daily flash & period reconciliations `matched`; no migrated invoice double-fed to Xero | `CTRL-RECON-*`, `CTRL-XERO-NODUP` (no duplicate Xero invoice), `CTRL-PERIOD` (close board green) |
| **P6** | **Decommission** | After the rollback window with green reconciliations and finance sign-off: retire MRPeasy/GB/Athena-as-SoR (Athena stays as retail upstream feed, doc 01 §4); archive legacy dumps to WORM storage for audit retention (doc 14 §5.3) | — | retention/evidence export (doc 14 §6) |

**Master go/no-go.** Cutover proceeds **only if G1–G6 are all green** and the CFO + Head of Ops sign the go decision (maker-checker, audited). Any red gate halts; the documented action is **rollback** (§7), not "push through".

---

## 7. Rollback plan

Rollback is possible because **legacy systems are kept live and read-only**, not deleted, through the hypercare window, and because Conduit's changes are **reversible by compensating events** (the ledger is immutable — corrections are reversing transfers, never edits, doc 01 §3b, doc 14 §1.5).

### 7.1 Decision

Rollback is triggered if, within the hypercare window, a gate that *was* green goes red (a reconciliation breaks materially, the ledger fails to balance, the genealogy is found corrupted) and cannot be hot-fixed within the agreed RTO. The decision is CFO + Head of Ops (maker-checker, audited).

### 7.2 Mechanism

```
rollback(batchId):
  1. STOP Conduit as SoR: re-mute the Xero feed + HubSpot replication; stop routing live inputs to Conduit as authority.
  2. RE-OPEN legacy: lift the read-only freeze on MRPeasy/Athena/GB; they resume as SoR from the freeze instant.
  3. REVERSE the ledger: for each opening/transacted transfer in batchId, post a deterministic REVERSING transfer
     (TigerBeetle: no edits; a reversal nets the opening to zero) — idempotent on a deterministic reversal id.
  4. REPLAY the freeze-window delta into legacy: any live transactions Conduit accepted during P4/P5 are re-applied
     to legacy from Conduit's retained event log (doc 01 §3a) so legacy is not missing the window's activity.
  5. MARK migration_record.status='superseded' for batchId; retain everything (source_payload, events, reversals)
     as audit evidence — rollback is itself fully audited, nothing is hard-deleted (doc 14 §5.3).
```

Because Conduit never destroyed legacy data and every Conduit effect is a reversible, deterministic, logged transfer/event, rollback returns the business to the legacy SoR **without data loss in either direction**. The freeze-window delta replay (step 4) is the one piece that needs care: it is bounded (the freeze window is short) and driven off the retained event log, so it is replayable rather than reconstructed by hand.

### 7.3 Point of no return

After the hypercare window closes with green reconciliations and decommission (P6), rollback by this mechanism is no longer offered — legacy is archived read-only and the path forward is normal correction (maker-checker adjustments, prior-period adjustments, doc 14 §2.4), not a system swap-back. The runbook makes this boundary explicit so no one assumes an indefinite rollback.

---

## Acceptance

- **Mapping:** every Athena `product.mrp_sku` resolves to exactly one MRPeasy article and one Conduit `product_variant`; the `mrp_sku` map is CI-validated with zero orphans either direction; unmapped SKUs block go-live (G1).
- **Provenance:** every migrated row has a `migration_record` with `source`, `source_id`, `conduit_id`, `source_payload`, `phase` and `status`; a reported ledger figure drills back through transfer → event → `migration_record.source_payload` → the legacy row (re-performable, doc 14 §5).
- **Idempotent backfill:** re-running `conduit-migrate run` over already-migrated rows is a **no-op** — dedupe on `(source, entity_type, source_id)`, deterministic `event_id`, deterministic `conduit_id` and deterministic `tb_transfer_id` each independently make replay safe; a crashed batch resumes without duplication.
- **Replay path:** backfill emits the same domain events as live writes through the outbox → Pulsar → production consumers; dropping a projection and re-emitting from the retained log rebuilds it identically (no second write path).
- **Specific-ID costing:** no weighted-average is imported; MRPeasy's `avg_cost`/aggregate inventory value reconciles to a specific-identification ledger **to the penny** via per-lot landed cost + largest-remainder allocation.
- **Opening balances:** opening `INV`/`AR`/`AP` post as audited opening transfers against `OPENING_BALANCE_EQUITY`; the opening trial balance nets to zero per currency (Σ debits == Σ credits); preserved `xero_invoice_id` means no migrated invoice is re-billed to Xero post-cutover (G4, G6).
- **Warranty backfill:** every migrated activation opens a `warranty_provision` at the serial's specific batch cost using the `warranty_rate` effective at the historical activation date, rolled forward to cutover, reproducing today's consolidated exposure.
- **Dual-run:** Conduit runs shadow in parallel; daily money/unit reconciliations have **zero tolerance**; N consecutive clean days + signed open-order schedule are required to pass G3; source-drift is detected and re-reconciled.
- **Cutover validation:** a full physical stock count (serials scanned) ties to the migrated inventory ledger in **units (exactly)** and in **value (to the penny)**; every scanned serial resolves its genealogy; real variances post as maker-checker corrections before the opening balance is fixed (G5).
- **Gates & controls:** cutover proceeds only with G1–G6 green, each backed by a passing `control_run`/matched `reconciliation`, and a CFO + Head of Ops maker-checker sign-off; any red gate halts and triggers rollback, not override.
- **Rollback:** within hypercare, rollback re-opens read-only legacy, reverses Conduit's opening/transacted transfers with deterministic reversing transfers (no edits), and replays the freeze-window delta into legacy from the retained log — no data loss either direction; everything is audited and retained; the point-of-no-return after decommission is explicit.

> Supports **M10** (doc 07). This is the **biggest practical go-live risk** (doc 10 §B) — Conduit cannot go live until this runbook executes green end-to-end.

---

## Real-system ground truth (validated by `precision`)

The COO's read-only tool **`precision`** (`gitlab.com/hypervolt/gtm-eng/precision`) reverse-engineered and *validated*
the live data flows across **Rhenus** (3PL), **HubSpot** (CRM/deals), **MRPeasy** (the ERP we migrate from) and
**Volex** (the contract manufacturer). It is a **ground-truth source + the dual-run reconciliation partner** for this
migration (it already three-way-reconciles shipped units). Concrete facts the backfill/mapping must honour:

- **Id schemes & the join cascade.** A HubSpot deal → Rhenus order via a 6-path cascade (priority): `erp_link`→CO,
  `tracking_link`→CO, `order_id` (CO **or** 18-digit Athena), `jumptech_link` (bare CO), **HubSpot `deal_id` used as
  the Rhenus `ORDER_ID`** (11-digit, ~25% since 2026-05-17 — adding it took matching 73.8%→99.1%), and the **MRPeasy
  bridge** (deal_id pulled from MRP order *notes* via `/deal/N`, `/record/0-3/N`, or a bare 7–13-digit line). Rhenus
  `ORDER_ID` formats: `CO-XXXXX`, `CO-XXXXX-N` (split suffix), 18-digit (Athena retail), 11-digit (HS deal id). The
  source→target mapping must accept all of these.
- **Cross-system lag is real; Rhenus is physical truth.** Order Placed (HS `first_won_date`) → Confirmed (MRP) →
  loaded Rhenus → Ready → **Shipped (Rhenus — physical, FIRST)** → Shipped (MRP, +1–3 business days) → Shipped (HS
  `shipped_date`, same lag). The dispatch/recognition event is the **Rhenus** ship; MRP/HS reflect it 0–3 days later.
  Dual-run reconciliation must tolerate a 0–3d skew (not flag it as a break).
- **Split deals → Conduit tranches.** A single HubSpot deal can be a bulk PO shipped in **multiple tranches on
  different dates** (e.g. Octopus `HK00502`, £833k → 728u/19-Mar + 48u/30-Mar + 552u/16-Apr), but HS pins all units to
  one `shipped_date` → distortion they patch by splitting the deal. **Conduit already models this correctly** (order
  tranches/call-off + per-dispatch recognition, doc 04 M4); migrating a split deal = **one Conduit order with
  per-tranche dispatches**, each its own ship date + recognition. precision's split-QA rules (a child with its own
  `first_won_date` double-counts revenue; children must sum to the parent ±£1) are exactly what Conduit's per-tranche
  model avoids by construction.
- **MRP covers only ~60% of shipped units** — Athena direct-checkout (retail) bypasses MRPeasy. Source retail orders
  from **Athena/checkout**, not MRP; Conduit's unified order model *closes* this structural gap.
- **Failure modes → dual-run exception classes:** missing CO/tracking cross-refs on shipped deals; orders stuck in a
  stage >72h; split-shipment mismatches; Volex→Rhenus pre-advice gaps; orphan Rhenus orders; Amazon-FBA on a separate
  pipeline.
- **Adapter data-quality gotchas:** Rhenus CSVs are **latin-1** with an Excel leading-`'` prefix + a few malformed
  rows; HubSpot dates are **epoch-ms** (not ISO) and batch reads return **HTTP 207**; a line item's SKU lives on the
  **Product** (`hs_product_id`), not the line item; MRPeasy rate-limits hard (filter by CO/date/status) and
  `actual_delivery_date` is per-product-line.
- **Charger classification is real & SKU-pattern-driven today** — chargers = SKU containing **`hv3`** (real:
  `HV3PROAA…`), `-DEMO` excluded, no-line-item deals kept. This is the fragile version of Conduit's governed
  **`product_class`** (doc 24 §4.5), and it confirms **no system computes the volume rebates today** (precision does
  the *classification*; rebate tracking "belongs in a separate accounting system" → that's Conduit, doc 24).
