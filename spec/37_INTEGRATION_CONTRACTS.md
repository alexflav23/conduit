# 37 — Integration contracts (per-source field maps)

**Purpose.** The enterprise-grade, field-level contract for every inbound source Conduit ingests in shadow
mode. [`33_INGEST_SYNC`](./33_INGEST_SYNC.md) defines the *framework* (cursors, idempotency, authority order,
drift); [`36_SHADOW_MODE_PLAN`](./36_SHADOW_MODE_PLAN.md) is the execution plan; **this doc is the exact
source-field → Conduit-column map** each connector + handler must honour, so live API implementations (S2) are
built to spec, not guessed. Without it the inbox receives unpredictable payloads and the mapping handlers
quarantine on missing fields.

**Authoritative source of truth.** Live pull and the boot ndjson snapshot **share one mapping path**
(`SnapshotLoader.handlers`, reused by `InboundMappingConsumer` via `mapInbound` — 36 §S1.6). So these contracts
**document what the handlers already do** — they are extracted from the live code (`SnapshotLoader.scala`), not
invented. When a handler changes, update the matching table here in the same PR. Cite: `domain/.../ingest/SnapshotLoader.scala`.

**Conventions.**
- **Idempotency key** = the natural key the handler upserts on (`ON CONFLICT`). Re-ingest of the same payload is a no-op; a changed payload re-applies (drift, 33 §4.3).
- **Cursor** = the watermark field the connector pages on (`sync_state.cursor`); cold = full backfill, warm = delta.
- **Required** fields gate the row (a missing required field → the handler skips/quarantines, never a half-row). **Optional** fields enrich; null is tolerated and stored null.
- **Staging vs materialised.** HubSpot/placement rows land in `*_raw` **staging** tables; a separate MDM
  correlation step (already built) materialises them into `party`/`contact`/`serial_unit.owner_party_id` once
  cross-source identity resolves. MRPeasy rows land **directly** as `order`/`order_line`/`dispatch`/`serial_unit`.
- **Authority order** (33 §0 / 18 §0): when two sources assert the same fact, the **authority wins** and the
  other becomes a reconciliation cross-check, never a silent overwrite. Per-fact authority is stated per source below.

---

## 1. HubSpot  ·  source `hubspot`  ·  connector `HubSpotConnector` + `HubSpotApi`

- **API:** CRM v3 object lists — `GET /crm/v3/objects/{type}` → `{results:[{id, properties:{…}}], paging:{next:{after}}}`. Page 100; page via `paging.next.after`.
- **Cursor:** `hs_lastmodifieddate` (epoch-ms); warm pull filters `hs_lastmodifieddate > cursor` (search API). Cold = full scan.
- **Datasets:** `companies`, `contacts`, `deals` (→ `deals_attributed` shape), `line_items`, **`support_tickets`** (new, S2.1), `rma_tickets`.
- **Authority:** HubSpot is the authority for **CRM identity + pipeline** (companies, contacts, deals, support/RMA tickets). It is **not** the authority for orders/financials (MRPeasy/Xero are).
- **Credential:** private-app token, SSM `/prod/athena/hubspot` (per [[hubspot-api-access]]); `EnvironmentConfig.hubspot.token`.

### 1.1 `companies` → `hubspot_company_raw` (staging) → `party` (org, via MDM correlation)
| Source property | Req | Conduit column (`hubspot_company_raw`) | Notes |
|---|---|---|---|
| `company_id` (`id`) | ✓ | `company_id` (PK, idempotency key) | the HubSpot record id |
| `name` | ✓ | `name` | becomes the master `party.display_name` on correlation |
| `domain` | | `domain` | business-domain match key (MDM) |
| `industry` | | `industry` | |
| `country` | | `country` | |
**Correlation:** MDM step binds `company_id` → a master `party` (deterministic exact + fuzzy queue, see [`11_CRM`](./11_CRM.md) §F); records an `account_source_link(system='hubspot_company')`. Never auto-merges below the confidence gate.

### 1.2 `contacts` → `hubspot_contact_raw` (staging) → `contact` (via MDM correlation)
| Source property | Req | `hubspot_contact_raw` col | Notes |
|---|---|---|---|
| `contact_id` (`id`) | ✓ | `contact_id` (PK) | idempotency key |
| `email` | | `email` | the strongest cross-source identity key (MDM email-unify) |
| `first_name`, `last_name` | | `first_name`, `last_name` | |
| `phone` | | `phone` | drives phone pre-association (consumer↔installer bridge) |
| `company` (name) | | `company` | |
| `company_id` | | `company_id` | FK to a company; materialises into `contact` once the company is bound |
| `job_title` | | `job_title` | |
| `lifecycle` | | `lifecycle` | `customer`/`lead`/… → `end_customer` vs `contact` entity-type |
| `created` (date) | | `created` | parsed `LocalDate` |

### 1.3 `deals` → `deal_snapshot` (the company-attributed deal register)
*Live `deals` map to the `deals_attributed` shape (company-attributed). Supersedes the old `deals_lifecycle`/`deals_won` scrapes.*
| Source field | Req | `deal_snapshot` col | Notes |
|---|---|---|---|
| `deal_id` (`id`) | ✓ | `deal_id` (PK) | idempotency key |
| `created` (date) | ✓ | `created_at` | gates the row |
| `pipeline` | ✓ | `pipeline` | dynamic label (e.g. "UK Installers") |
| `closed` (date) | | `closed_at` | |
| `won` (bool) | | `is_won` | |
| `is_closed` (bool) | | `is_closed` | `won` implies closed |
| `amount` (string→decimal) | | `amount` | default 0 |
| `company_id`, `company_name` | | `company_id`, `company_name` | the deal's attributed customer |
| `segment` | | `segment` | installer/wholesaler/retail/energy |
**S4 link:** matching a deal to a Conduit order (so deals appear in the order topology lineage) is fuzzy, like account matching — tracked in 36 §S4.

### 1.4 `line_items` → (deal line attribution) `deal_line` *(new dataset; handler S2.1)*
Required: `id` (PK), `hs_deal_id` (FK→`deal_snapshot`), `quantity`, `price`. Optional: `hs_product_id`, `name`, `sku`. Maps a deal's product breakdown; null sku tolerated.

### 1.5 `support_tickets` → `support_ticket` *(new dataset; handler S2.1)*
| Source | Req | Conduit | Notes |
|---|---|---|---|
| `ticket_id` (`id`) | ✓ | `ticket_ref` (PK) | |
| `subject` | | `subject` | |
| `content` | | `body` | |
| `hs_pipeline_stage` | | `status` | |
| `createdate` | | `opened_at` | |
| associated `contact_id`/`company_id` | | `party_id` (via MDM) | links the ticket to the master account |
*Distinct from `rma_tickets` (§1.6) — support tickets are the general service queue; RMA is the replacement pipeline.*

### 1.6 `rma_tickets` → `rma_ticket`
*RMA Pipeline `2732387` (per [[hubspot-api-access]]). Uses HubSpot's exact replacement serial — no inference.*
| Source field (fallbacks) | Req | `rma_ticket` col | Notes |
|---|---|---|---|
| `ticket_ref` ∥ `ticket_id` | ✓ | `ticket_ref` (PK) | |
| `original_serial` ∥ `faulty_serial` | | `original_serial` | the faulty unit |
| `replacement_serial` ∥ `new_serial` | | `replacement_serial` | the exact replacement (HubSpot `rma_serial_number__s_n_`) |
| `ticket_type` ∥ `type` | | `ticket_type` | rma / replacement |
| `reason` | | `reason` | |
| `opened_at`, `closed_at` (ISO) | | `opened_at`, `closed_at` | |
| `status` | | `status` | |
| *(whole row)* | | `payload` (jsonb) | raw retained |
**Downstream:** materialises units the MRPeasy ledger never had (`source='hubspot_rma'`), links faulty→replacement genealogy, inherits root `warranty_end` down the chain (M8).

---

## 2. MRPeasy  ·  source `mrpeasy`  ·  connector `MrpeasyConnector` + `MrpeasyApi`

- **API:** REST v1, base `https://app.mrpeasy.com/rest/v1`; raw headers `access_key` + `api_key`. Returns a top-level JSON array (or `{items|data:[…]}`).
- **Cursor:** `modified` (unix seconds, numeric or numeric-string — compared numerically). Cold = full; warm = `modified > cursor`.
- **⚠️ Pagination quirk (decided):** `/items` is capped at 100 and **ignores `start`** — do **not** page it. Strategy: the **purpose-built endpoints** (`customer-orders`, `shipments`, `purchase-orders`) paginate normally on `modified`; for **`articles`/items**, harvest **by code** (`/items?code=X`, exact-match works) on demand, plus a periodic full-catalogue refresh. The cursor field for drift on a line-bearing record is the parent's `modified`.
- **Datasets:** `customer_orders`, `shipments`, `stock_lots`, `purchase_orders`, `articles` (items).
- **Authority:** MRPeasy is the authority for **orders, inventory, landed cost, serials** (18 §0). It wins those facts over HubSpot/Xero.
- **Credential:** `access_key` + `api_key`, SSM `/prod/athena/mrpeasy/*`; `EnvironmentConfig.mrpeasy.{access_key,api_key,base_url}`.

### 2.1 `customer_orders` → `order` + `order_line`
**Idempotency:** `order_no = 'MRP-' || code` (`ON CONFLICT (order_no) DO NOTHING`). **Party:** `customer_name` → `mrpParty` (`display_name='MRP: '||name`, segment derived by name heuristic — energy/wholesale/online_retail/installers).
| Source field | Req | Conduit | Notes |
|---|---|---|---|
| `code` | ✓ | `order.order_no` (`MRP-`+) | idempotency key |
| `customer_name` | ✓ | → `order.sold_to_party_id`/`bill_to_party_id` | via `mrpParty` |
| `created` (epoch s) | ✓ | `order.order_date`, `created_at` | |
| `status` | | `order.status` | `*cancel*` → `cancelled`, else `placed` |
| `total_price_cur` ∥ `total_price` | | `order.total_inc_vat`, `subtotal_ex_vat` | `vat_total=0` (VAT backfilled separately); `txn_currency='GBP'` |
| `lines[].item_code` | ✓(line) | `order_line.product_variant_id` via `mrpVariant` | SKUs containing `DELIVERY`/`DONOTUSE` are skipped |
| `lines[].qty` | ✓(line) | `order_line.qty` | must be `> 0` |
| `lines[].price` | | `order_line.unit_price_ex_vat` | default 0 |
| `lines[].total` | | `order_line.line_total_inc_vat` | default 0 |
**`mrpVariant` SKU classification** (serialization-derived, from the 0301 serial log): `HV-PR-(1070|117[2-9]|1180|137)*` = serialized finished-goods; other `HV-PR*` = `part`; `HYPV-HOLS*`/`GD1*` = `accessory`; else `charger`.

### 2.2 `shipments` → `dispatch` (+ serials)
**Idempotency:** dispatch keyed by `code`; linked to its order by `order_code`. `rma_order_id` present ⇒ an RMA shipment.
| Source field | Req | Conduit | Notes |
|---|---|---|---|
| `code` | ✓ | `dispatch` natural key | |
| `order_code` | ✓ | → `dispatch.order_id` (join `MRP-`+order_code) | |
| `created` (epoch s) | ✓ | `dispatch.date` | |
| `delivery_date` (epoch s) | | `dispatch.delivered_at` | drives recognition timing |
| `status` | | `dispatch.status` | |
| `rma_order_id` | | flags RMA shipment | |
| `lines[].item_code`, `qty` | | dispatch lines | |
| `lines[].serials[]` | | `serial_unit.serial_no` (minted/linked) | the serial → genealogy spine |

### 2.3 `stock_lots` → `lot_batch` *(handler S2.2 — confirm field names live)*
Target (specific-id landed cost, doc 02 §G): `lot_batch(supplier, sku→product_variant_id, qty, landed_unit_cost, currency, fx_rate, fx_basis, received_date)`. Idempotent on the MRPeasy lot id. **Cost authority** — these set the COGS basis.

### 2.4 `purchase_orders` → `po` + `po_line` *(handler S2.2)*
Target (M9): `po(po_no, supplier_party_id, status, currency, ordered_at)` + `po_line(po_id, product_variant_id, qty, unit_cost)`. Idempotent on PO code.

### 2.5 `articles` (items) → `mrpeasy_item_raw` (staging)
| Source | Req | `mrpeasy_item_raw` | Notes |
|---|---|---|---|
| `code` | ✓ | `code` (PK) | |
| `title` | ✓ | `title` | ignition's `backfillProductNames` maps `product_variant.sku`→`code` for the human name |
| `group` | | `grp` | |

---

## 3. Placement / activation  ·  source `placements` / `ghostbusters`

A **push** source (Pulsar), normalised to the same `IngestRecord` (33 §2 — a stream is a latency optimisation over a poll, never a second path). Two complementary streams:

### 3.1 Placement registry → `placement_owner_raw` (staging) → serial→owner (MDM)
*Live: `consumer/PlacementInboundConsumer` subscribes `athena-placement-versioned` (`AthenaPlacementVersionedRecord(device, placementId, version)`, sub `conduit-placement-versioned-subscription-1`) → `IngestSink` (source `placements`). The committed ndjson `ingest/placements/serial_owner.ndjson` is the backfill of the same shape.*
| Source field | Req | `placement_owner_raw` | Notes |
|---|---|---|---|
| `serial` | ✓ | `serial` (PK) | the unit |
| `device_id` | | `device_id` | |
| `placement_id` | | `placement_id` | |
| `keycloak_user_id` | | `keycloak_user_id` | resolves owner via Keycloak `retail-customers` |
| `owner_email` | | `owner_email` | MDM materialises an individual master `party` per owner |
| `owner_name` | | `owner_name` | |
| `placement_name` | | `placement_name` | |
| `country` | | `country` | |
**Downstream:** `ActivationService` opens the warranty clock at activation; stamps `serial_unit.owner_party_id`.

### 3.2 Activation feed → `serial_unit.activated_at` + `status='activated'`
*The sell-through half (prod Athena `charger_activation`, first activation per V3 serial), source `ghostbusters` dataset `activations`.*
| Source field | Req | Effect | Guard |
|---|---|---|---|
| `serial` | ✓ | match `serial_unit.serial_no` | first-write-wins (`activated_at IS NULL`) |
| `activated_at` (ISO) | ✓ | set `activated_at` + `status='activated'` | refuses activations materially (>7d) before dispatch (refurb/RMA re-entry) |
**Authority:** the placement registry is the authority for **field activation + owner**; idempotent, re-placement no-op.

---

## 4. Xero  ·  source `xero`  ·  connector `XeroConnector` + `XeroApi`  ·  READ-ONLY in shadow

- **API:** Xero Accounting API; OAuth2 client-credentials (the `XeroAccountingConsumer` token flow already exists). `If-Modified-Since` on `UpdatedDateUTC`.
- **Cursor:** `UpdatedDateUTC`. Page 100.
- **Datasets:** `invoices` (`Invoices`/`InvoiceID`), `contacts` (`Contacts`/`ContactID`), `payments` (`Payments`/`PaymentID`).
- **Purpose in shadow:** **reconciliation cross-check only** (AR↔Xero, GL↔Xero — S3.1). Xero is downstream; **no write leaves Conduit in shadow** (`ShadowGuard` mutes the outbound invoice push).
- **Authority:** Xero is the authority for **externally-booked AR/AP** during the parallel window (the books we are tying *to*), so its figures are the reconciliation target, not overwritten by Conduit.

### 4.1 `invoices` → `xero_invoice_raw` (staging, for reconciliation)
Required: `InvoiceID` (PK), `Type` (ACCREC/ACCPAY), `Total`, `Status`, `Date`. Optional: `Contact.ContactID`, `InvoiceNumber`, `AmountDue`, `AmountPaid`, `CurrencyCode`, `LineItems[]`. Feeds the AR↔Xero reconciliation; matched to a Conduit `order_invoice` by amount + party + date.

### 4.2 `payments` → `xero_payment_raw` · 4.3 `contacts` → `xero_contact_raw`
Payments: `PaymentID` (PK), `Invoice.InvoiceID`, `Amount`, `Date`. Contacts: `ContactID` (PK), `Name`, `EmailAddress` — a cross-check on the MDM party set (Xero contact ↔ master party).

---

## 5. Carrier (Rhenus)  ·  source `carrier`  ·  PROPOSED contract (confirm with the carrier feed)

*No live schema exists yet (today the carrier is a stored-field stub). This is the **proposed** inbound contract to confirm against Rhenus's actual API/webhook (decision 10 §E "Rhenus webhook schema").*
- **Mode:** webhook push preferred (→ `IngestSink`, source `carrier`), polling fallback on a tracking endpoint.
- **Cursor (poll fallback):** event timestamp.
| Proposed field | Req | Conduit effect | Notes |
|---|---|---|---|
| `tracking_ref` | ✓ | match `dispatch` by carrier ref | idempotency on (`tracking_ref`, `event_type`, `event_ts`) |
| `dispatch_ref` ∥ `order_ref` | ✓ | link to the dispatch | |
| `event_type` | ✓ | dispatch state: `in_transit`/`out_for_delivery`/`delivered`/`exception` | a `delivered` event advances the dispatch → triggers ASC-606 recognition |
| `event_ts` (ISO) | ✓ | `dispatch.delivered_at` on delivery | real OTD timestamps |
| `pod` (proof-of-delivery) | | `dispatch.pod_ref` | |
**Open:** confirm Rhenus's exact field names, auth (webhook signature?), and whether multi-parcel shipments split.

---

## 6. Per-source summary

| Source | Mode | Cursor | Idempotency key | Authority for | Status |
|---|---|---|---|---|---|
| `hubspot` | poll (v3) | `hs_lastmodifieddate` | per-object `id` | CRM identity, pipeline, RMA/support | 🟡 contract done; live API S2.1 |
| `mrpeasy` | poll (REST) | `modified` | order/shipment/lot/po code | orders, inventory, landed cost, serials | 🟡 contract done; live API S2.2 |
| `placements` | push (Pulsar) | version | `serial` | field activation, owner | 🟡 contract done; consumer S2.3 |
| `xero` | poll (OAuth2) | `UpdatedDateUTC` | per-record id | external AR/AP (reconcile target) | 🟡 read-only S2.4 |
| `carrier` | webhook/poll | event ts | tracking_ref+event | delivery state / OTD | ⬜ proposed; confirm S2.5 |

Every contract above maps through `SnapshotLoader.handlers` (live + boot share it). Adding/extending a dataset =
extend the handler **and** this doc in the same PR; the connector framework, durability, and quarantine are unchanged.
