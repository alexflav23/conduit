# 21 — Platform Services (Notifications · Search · Reporting/Exports + Horizons · Localization/i18n)

Build-grade deep-dive for the **cross-cutting platform services** that sit beside every domain module rather than inside one: **notifications**, **search**, **reporting & exports + the Horizons feed**, and **localization/i18n**. Same template as 02–05 / 09 / 13 / 16 / 17: field-level schemas, outbox events, pseudocode, REST contracts, permission/data-layer mappings, an Acceptance block. This document **references and extends** the spine; it does **not** redefine tables already in doc 02.

Tables/algorithms it builds on: the **event envelope + spine** (doc 03), the **outbox + idempotency-on-`event_id`** discipline (doc 01 §2, doc 03 §3), the **policy layer — scope filtering + data-layer projection** (doc 05 §2/§3), **typed money + locale formatting + period model + retention** (doc 14 §1/§2/§5.3), the **document template registry + `DocumentRenderer` port + locale formatter** (doc 17 §2/§4.3/§4.4), the **ASC 606 recognition point `dispatch.delivered` → matched revenue + COGS off batch landed cost** (doc 04 §Ledger, doc 03 Orders), and the reference data **`locale`/`market`/`currency`/`channel` + `party.preferred_locale` + `billing_profile.invoice_locale` + `product_translation`** (doc 02 §A/§C/§D).

Design stance (consistent with the pack):
- **Every cross-cutting service is a consumer/projection, never a second source of truth.** Notifications, the search index, the reporting read-models and the Horizons feed are all **idempotent projections of the event spine** (doc 03), rebuildable by replay (doc 01 §3a). None of them holds authoritative state; all of them **inherit the access wall** — scope filtering (doc 05 §2) and data-layer projection (doc 05 §3) apply to what a user can search, see in a report, export, or be notified about.
- **The access wall is enforced at the service, not the UI.** A search result, a report row, a notification body and an export cell are all **layer-projected and scope-filtered** exactly as a REST read is — a `volume`-only principal never sees a price in a search hit, a report cell, or a digest line.
- **The Horizons feed is a contract, not a dump.** It is a typed, versioned, gapless units→revenue→COGS→GP feed derived from `dispatch.delivered` + `ledger.posted`, reconcilable back to the ledger (doc 14 §5.2), in the group presentation currency (USD) **and** transaction currency.
- **Localization is data + a fallback chain, not code.** One fallback chain — **`party.preferred_locale` → `market.default_locale` → `en`** (doc 02 §D, doc 17 §2.3) — governs app strings, product names, documents, and notification bodies. No hard-coded user-facing strings; CI pseudo-localizes to catch truncation and missing keys.

---

# Section 1 — NOTIFICATIONS

A single **notification service** consumes the domain event spine (doc 03) and turns business events into **multi-channel notifications** (push / email / in-app), governed by a **template registry**, **per-user preferences**, and **digesting**. It is **idempotent on `event_id`** (doc 03 §3) — a redelivered event never double-notifies. It owns no business truth; it is a consumer.

## 1.1 What it consumes (the trigger set)

The service subscribes to the domain topics (doc 03 §4) and maps a curated set of event types to **notification kinds**. Initial map (data-driven — adding a kind is a `notification_rule` row, not code):

| Domain event (doc 03) | Notification kind | Default audience (resolved §1.5) | Default channels |
|---|---|---|---|
| `forecast.cycle.opened` | `h6q.cycle_open` | each forecaster with outstanding submissions | push + in-app |
| `forecast.cycle.closed` | `h6q.cycle_closed` | forecasters + finance | in-app |
| `adlp.exception.requested` | `adlp.exception_pending` | CEO/CFO (sole approver, doc 04 §ADLP) | push + email + in-app |
| `adlp.exception.approved` / `rejected` | `adlp.exception_decided` | requesting agent + deal desk | push + in-app |
| `order.placed` | `order.placed` | sold-to account manager + agent | in-app |
| `dispatch.created` | `dispatch.shipped` | sold-to contact (customer-facing) + agent | email + in-app |
| `dispatch.delivered` | `dispatch.delivered` | sold-to contact + agent | email + in-app |
| `order.invoiced` | `invoice.issued` (delegates to doc 17 `document.issued`) | bill-to/payer billing contact | email |
| `inventory.received` (backorder filled) | `backorder.filled` | agents on orders whose tranche auto-allocated | push + in-app |
| `commission.posted` / true-up delta | `commission.trued_up` | the agent (own commission, real-time) | push + in-app |
| `inventory.adjustment` / `stock.count` pending | `approval.pending` | the maker-checker approver (≠ requester, doc 05 §5) | push + in-app |
| `return.raised` / `return.approved` | `rma.update` | CS agent + sold-to contact | email + in-app |
| `warranty.claim.raised` | `warranty.claim` | warranty/CS queue | in-app |
| `document.issued` (doc 17 §6) | `document.ready` | the document's bill-to/payer contact | email |
| credit-limit / period-close approvals (doc 05 §5) | `approval.pending` | the designated approver (CFO/CEO) | push + email |

> **Note on `order.invoiced` / `document.ready`.** Document **generation** is doc 17 (it emits `document.issued`); this service is the **delivery channel** (doc 17 §1.2 explicitly defers the channel to here). The notification carries the document reference; the PDF bytes are served from WORM storage (doc 17 §4.5) behind an authenticated, layer-checked link — never attached raw to a push.

## 1.2 Schema

All tables carry the common columns (doc 00: `id`, `created_at`, `updated_at`, `deleted_at?`). Domain columns only:

### `notification_template` — the template registry (data, versioned, governed)
Keyed by `(kind, channel, locale)` with the locale fallback chain (§4, doc 17 §2.3). Bodies reference **typed render-model fields only** and format via the locale formatter (doc 17 §4.3) — no hard-coded numbers or dates.

| Column | Type | Notes |
|---|---|---|
| kind | TEXT NOT NULL | e.g. `dispatch.delivered`, `commission.trued_up` |
| channel | TEXT NOT NULL | `push` / `email` / `in_app` |
| locale | TEXT → locale.code NULL | BCP-47; **NULL = locale-agnostic fallback** |
| subject | TEXT NULL | email subject / push title (ICU MessageFormat, §4.4) |
| body | TEXT NOT NULL | template source (ICU MessageFormat / Handlebars-style); references render-model fields only |
| deep_link | TEXT NULL | in-app / push route (e.g. `conduit://orders/{order_id}`) |
| layer_required | TEXT → data_layer NULL | if the body renders a layered figure (e.g. commission amount → `commission`), the layer the recipient must hold (§1.6) |
| version | INTEGER NOT NULL | reproducibility |
| status | TEXT NOT NULL | `draft`/`active`/`retired` |
| effective_from | TIMESTAMPTZ NOT NULL | |

UNIQUE(kind, channel, locale, version). Resolution index `(kind, channel, locale, status, effective_from DESC)`. A template change is **governed, maker-checker, audited** (doc 05 §4, doc 14 §4) and emits `notification_template.changed`.

### `notification_rule` — event→kind→audience mapping (data)
| Column | Type | Notes |
|---|---|---|
| event_type | TEXT NOT NULL | doc 03 event type (e.g. `adlp.exception.approved`) |
| kind | TEXT NOT NULL | → notification_template.kind |
| audience_resolver | TEXT NOT NULL | named resolver (§1.5): `account_manager`/`order_agent`/`bill_to_contact`/`ceo_approver`/`maker_checker_approver`/`forecaster_outstanding`/`role:<role_code>` |
| default_channels | TEXT[] NOT NULL | overridable per-user (§1.4) |
| priority | TEXT NOT NULL | `immediate`/`digestible` (drives §1.7 digest vs send-now) |
| enabled | BOOLEAN NOT NULL DEFAULT true | |

UNIQUE(event_type, kind, audience_resolver).

### `notification_preference` — per-user, per-kind, per-channel
| Column | Type | Notes |
|---|---|---|
| user_id | UUID → app_user NOT NULL | |
| kind | TEXT NULL | NULL = applies to all kinds (the user's baseline) |
| channel | TEXT NOT NULL | `push`/`email`/`in_app` |
| enabled | BOOLEAN NOT NULL DEFAULT true | |
| digest | TEXT NOT NULL DEFAULT 'off' | `off`/`daily`/`weekly` — fold `digestible` items into a digest |
| quiet_hours | JSONB NULL | `{ "tz":"Europe/London", "from":"21:00", "to":"07:00" }` — defer non-`immediate` push |

UNIQUE(user_id, kind, channel). Resolution merges (most specific `kind` wins; baseline `kind IS NULL` is the fallback). Defaults come from the user's **role preset** (doc 05 §4) so a `retail_sales_agent` arrives with sensible defaults.

### `device_token` — push registration
`user_id → app_user`; `platform TEXT` (`ios`/`android`/`web`); `token TEXT NOT NULL`; `last_seen_at TIMESTAMPTZ`; `revoked_at TIMESTAMPTZ NULL`. UNIQUE(token). The Flutter app registers on sign-in (doc 08); a revoked/stale token is pruned on delivery failure.

### `notification` — the materialised notification (one row per recipient × kind × triggering event)
| Column | Type | Notes |
|---|---|---|
| recipient_user_id | UUID → app_user NULL | NULL when sent to an external contact (customer email) |
| recipient_contact_id | UUID → contact NULL | external customer recipient |
| kind | TEXT NOT NULL | |
| source_event_id | UUID NOT NULL | the triggering envelope `event_id` (doc 03 §1) — idempotency anchor |
| aggregate_type / aggregate_id | TEXT / UUID | from the source envelope (deep-link target) |
| scope | JSONB NOT NULL | `{entity_id, market_id?, channel_id?}` from the envelope (doc 03 §1) — for audience-time scope check (§1.6) |
| locale | TEXT → locale.code NOT NULL | resolved render locale (§4) |
| render_model | JSONB NOT NULL | typed, frozen data the body rendered from (re-render input; doc 17 §4.2 pattern) |
| status | TEXT NOT NULL | `pending`/`queued`/`sent`/`delivered`/`read`/`failed`/`suppressed` |
| channels | TEXT[] NOT NULL | channels this notification fanned to |
| digest_id | UUID → notification_digest NULL | set when folded into a digest |
| read_at | TIMESTAMPTZ NULL | in-app read receipt |

**UNIQUE(recipient_user_id, recipient_contact_id, kind, source_event_id)** — the idempotency guard: a redelivered `event_id` collides and is a no-op (doc 03 §3). Indexes: `(recipient_user_id, status, created_at DESC)` (in-app inbox), `(source_event_id)`.

### `notification_delivery` — per-channel attempt log
`notification_id → notification`; `channel TEXT`; `provider TEXT` (`apns`/`fcm`/`ses`/`in_app`); `attempt INT`; `status TEXT` (`sent`/`bounced`/`failed`); `provider_ref TEXT NULL`; `error TEXT NULL`; `attempted_at TIMESTAMPTZ`. Bounces (email) / unregistered tokens (push) feed back to prune `device_token` / flag the contact.

### `notification_digest` — rolled-up batch
`recipient_user_id → app_user`; `cadence TEXT` (`daily`/`weekly`); `period_start/period_end TIMESTAMPTZ`; `status TEXT` (`accumulating`/`sent`); `item_count INT`. The digest body renders the member `notification` rows grouped by kind.

## 1.3 The consumer (idempotent, scope-aware)

```
on event e:                                              // one subscription per domain topic; dedupe on e.event_id
  rules = notification_rule[event_type = e.event_type, enabled]
  if rules empty: ack; return                            // not a notifying event
  for rule in rules:
    recipients = resolveAudience(rule.audience_resolver, e)      // §1.5 — set of users and/or contacts
    for r in recipients:
      // IDEMPOTENCY: UNIQUE(recipient, kind, source_event_id) makes the insert a no-op on redelivery
      if exists notification(recipient=r, kind=rule.kind, source_event_id=e.event_id): continue
      // ACCESS WALL at audience time (§1.6): can this recipient even see this scope/layer?
      if not recipientInScope(r, e.scope): continue              // doc 05 §2 — never notify out-of-scope
      loc   = resolveLocale(r)                                   // §4 fallback chain
      model = buildRenderModel(e, r, loc)                        // typed, layer-projected (§1.6)
      chans = effectiveChannels(r, rule)                         // §1.4 prefs ∩ rule.default_channels
      n = insert notification(recipient=r, kind, source_event_id=e.event_id, scope=e.scope,
                              locale=loc, render_model=model, channels=chans, status='pending')
      if rule.priority == 'digestible' and userDigest(r, rule.kind) != 'off':
        foldIntoDigest(n, r)                                     // §1.7 — accumulate, don't send now
      else:
        enqueue(n)                                               // §1.8 — fan to channels, honouring quiet hours
  ack(e)                                                  // at-least-once; the UNIQUE guard makes reprocessing safe
```

`buildRenderModel` reads typed truth (order totals, commission amounts, delivery dates) and **projects per the recipient's layers** before freezing — so a layered figure never lands in a body the recipient can't see (§1.6). The model is frozen (doc 17 §4.2 pattern) so a re-render/replay reproduces the same body.

## 1.4 Preferences resolution
```
effectiveChannels(user, rule):
  base = rule.default_channels
  for ch in base:
    pref = mostSpecific(notification_preference[user, kind ∈ {rule.kind, NULL}, channel=ch])
    if pref.enabled == false: drop ch
  // a user can opt a kind down to in_app-only, but cannot opt out of in_app for `approval.pending`
  // (mandatory kinds — maker-checker approvals, ADLP — keep at least in_app, enforced server-side)
  return mandatoryFloor(rule.kind, channels)
```
Mandatory kinds (`approval.pending`, `adlp.exception_pending`) cannot be silenced entirely — segregation-of-duties depends on the approver being notified (doc 05 §5).

## 1.5 Audience resolvers (named, scope-producing)
Each resolver returns recipients **with** the originating scope so §1.6 can re-check:
- `account_manager` → `party.account_manager_user_id` of the order's **sold-to** (doc 02 §C/§F).
- `order_agent` → the order's `agent_id`.
- `bill_to_contact` → the **bill-to/payer** party's primary billing `contact` (doc 02 §C) — external recipient (email).
- `ceo_approver` → holders of `approve:adlp_exception` (doc 05 §4) — the sole-approver set.
- `maker_checker_approver` → holders of the approval permission **excluding the requester** (`actor`), enforcing maker≠checker (doc 05 §5).
- `forecaster_outstanding` → forecasters with an open submission in the cycle (doc 03 `forecast.cycle.opened` → "create outstanding submissions, notify owners", doc 12).
- `role:<code>` → all users holding a role, intersected with the event scope.

## 1.6 Access wall on notifications (no leakage via a notification)
A notification is a read surface, so the **same wall as doc 05** applies:
- **Scope (doc 05 §2):** `recipientInScope(r, e.scope)` — a user only gets notified about an event whose `{entity_id, market_id, channel_id}` falls within at least one of their `view` grants on that object. An out-of-scope user is silently skipped (no row, no leakage).
- **Data-layer (doc 05 §3):** the `render_model` is **layer-projected for the recipient** before freeze. If `notification_template.layer_required` is set (e.g. `commission` for `commission.trued_up`) and the recipient lacks it, the figure is **omitted/redacted** in the body, or — if the figure is the whole point — the channel is downgraded (no email body with a stripped number; in-app deep-link only, gated at open time). Commission notifications are `own`-breadth: an agent is only ever the recipient for **their own** commission (doc 05 §4 `retail_sales_agent: own`).
- **PII:** customer-contact notifications (email to a `contact`) carry only the contact's own data; staff notifications never embed `pii`-layer fields a staff recipient can't see.

## 1.7 Digests
`digestible`-priority items for a user whose preference is `daily`/`weekly` are **folded** into an accumulating `notification_digest` instead of sent immediately. A scheduled job (daily 07:00 / weekly Monday, in the user's `quiet_hours.tz`) renders the digest (grouped by kind, localized) and sends it on the user's chosen channels, marking members `digest_id`. `immediate` items (ADLP pending, approvals, delivery to a customer) **never** digest.

## 1.8 Delivery, retries, idempotency at the edge
- Each channel send writes a `notification_delivery` attempt. **Push/email providers are behind a port** (`NotificationChannel[F[_]]`) — APNs/FCM and SES are implementations, swappable (same stance as the swappable `DocumentRenderer`, doc 17 §4.4, and accounting consumer, doc 07 M13).
- Provider failures retry with backoff; permanent failures (`bounced`, `unregistered`) prune the `device_token`/flag the contact and set `notification.status='failed'`.
- **Quiet hours** defer non-`immediate` push to the window end.
- The whole pipeline is **replay-safe**: re-running the consumer over the event log rebuilds `notification` rows without duplicate sends (the UNIQUE guard makes re-inserts no-ops; already-`sent` rows are not re-enqueued).

## 1.9 REST
```
GET    /notifications?status=&kind=&cursor=          → [Notification]   (recipient = caller; in-app inbox; layer-projected body)
POST   /notifications/{id}/read                       → Notification    (sets read_at)
POST   /notifications/read-all                        → { count }
GET    /notifications/preferences                     → [NotificationPreference]  (caller's, merged with role defaults)
PUT    /notifications/preferences  { kind?, channel, enabled, digest?, quiet_hours? }   → NotificationPreference
POST   /notifications/devices      { platform, token } → DeviceToken     (push registration; app sign-in)
DELETE /notifications/devices/{token}                 → 204              (sign-out / revoke)
// admin (governed, audited):
GET    /admin/notification-templates?kind=&locale=    → [NotificationTemplate]
POST   /admin/notification-templates                  → NotificationTemplate (draft)
POST   /admin/notification-templates/{id}/activate    → NotificationTemplate (maker≠checker; emits notification_template.changed)
GET    /admin/notification-rules ; PUT /admin/notification-rules/{id}
```

## 1.10 Events (this service produces)
- `notification.created` · key `notification_id` · {recipient, kind, source_event_id, channels} · → analytics, audit
- `notification.delivered` / `notification.failed` · key `notification_id` · {channel, provider_ref} · → delivery analytics, token pruning
- `notification_template.changed` · key `template_id` · {kind, channel, locale, version, approved_by} · → audit (maker-checker, doc 05 §4)

---

# Section 2 — SEARCH

A unified **search service** lets a principal find **orders, parties/accounts, serials, deals, and invoices** from one query box, returning **scope-filtered, layer-projected** hits. It is a **projection consumer** of the event spine (doc 03), rebuildable by replay (doc 01 §3a).

## 2.1 What is searchable (the search corpus)

| Object type | Source events (projected from) | Display + match fields | Direct-hit fields |
|---|---|---|---|
| `order` | `order.placed/amended/cancelled`, `dispatch.*` | `order_no`, sold-to name, `customer_po_number`, status, agent | `order_no`, `customer_po_number` (exact) |
| `party` (account) | `crm.party.created/updated` (doc 03 CRM) | `display_name`, segment, `party_type`, parent, channel/market, contacts' names/emails | exact name, contact email |
| `serial` | `inventory.received`, `serial.lifecycle`, `activation.recorded` | `serial`, model/variant, batch, current owner, status | `serial` (exact — recall/warranty lookup, doc 06 `/serials/{serial}`) |
| `deal` | `crm.deal.created/stage_changed/won/lost` | name, pipeline/stage, company, owner, value | — |
| `invoice` (`order_invoice`/`document`) | `order.invoiced`, `document.issued` (doc 17) | `invoice_no`, bill-to name, order ref, status, total | `invoice_no`, formatted document number (exact) |

> Out of scope for the unified index (kept queryable by their own filtered list endpoints, doc 06): the ledger (queried by projection, doc 01 §5), audit log (`/audit`, doc 05 §5), and forecast figures (H6Q board, doc 12). These have purpose-built read paths and don't belong in a free-text box.

## 2.2 Indexing strategy — **PostgreSQL FTS** (and why, not OpenSearch)

**Decision: PostgreSQL full-text search (`tsvector` + GIN), plus `pg_trgm` for fuzzy/typo and prefix matching — not a separate OpenSearch/Elasticsearch cluster.** *(Resolves the "FTS vs index" open item in doc 10 §B.)*

Rationale:
1. **The access wall is non-negotiable and hard to externalise.** Scope filtering (doc 05 §2) and data-layer projection (doc 05 §3) must apply to **every hit**. In Postgres the search predicate **ANDs with the same `scopePredicate` (doc 05 §2)** the repositories already build, indexed on `(entity_id, market_id, channel_id)` — one enforcement path, no risk of an external index leaking out-of-scope rows. Replicating the policy layer into OpenSearch (or post-filtering its hits) is a second enforcement surface and a leakage risk we refuse.
2. **Scale fits.** The corpus is operational (orders/parties/serials/deals/invoices — millions, not billions of documents), well within Postgres FTS + GIN at the stated latency budget (<300ms p95, doc 05 §6). We are not doing log search or analytics-over-text.
3. **Fewer moving parts / replayability.** The index is a Postgres **materialised projection** (`search_document`), rebuilt by **replaying the event log** (doc 01 §3a) — same operational story as every other projection. No second datastore to back up, secure, version-skew, or keep consistent.
4. **CJK + Thai:** Postgres default parsers don't segment CJK/Thai word boundaries. We use the **`pgroonga`** extension (or ICU-tokenised n-gram `tsvector`) for the CJK/Thai locales so Japanese/Thai names and product translations are searchable (doc 02 §A scripts). Approach is pluggable per the `lang` of the indexed text.

If, later, full-text analytics or cross-language relevance tuning outgrows Postgres, the **same projection consumer can additionally feed an external index** with **no contract change** — but the access wall stays enforced in Conduit, never delegated.

### `search_document` — the index projection
One row per searchable object, maintained by the projection consumer (§2.3).

| Column | Type | Notes |
|---|---|---|
| object_type | TEXT NOT NULL | `order`/`party`/`serial`/`deal`/`invoice` |
| object_id | UUID NOT NULL | the aggregate id |
| entity_id | UUID NOT NULL | scope axis (doc 05 §2) |
| market_id | UUID NULL | scope axis |
| channel_id | UUID NULL | scope axis |
| owner_user_id | UUID NULL | for `own`/`team` breadth (doc 05 §1) |
| title | TEXT NOT NULL | primary display (order_no, party name, serial…) |
| subtitle | TEXT NULL | secondary display (status, account, total — **layer-tagged, §2.4**) |
| exact_keys | TEXT[] NOT NULL | exact-match keys (order_no, serial, invoice_no, po_number, email) |
| body | TEXT NOT NULL | concatenated searchable text (names, refs, contact names) — **no layered figures** |
| lang | TEXT NOT NULL DEFAULT 'simple' | parser/dictionary for `tsv` (e.g. `english`, or pgroonga for ja/th) |
| tsv | tsvector | GENERATED from `body`+`title` per `lang`; **GIN-indexed** |
| field_layers | JSONB NOT NULL DEFAULT '{}' | per-subtitle-field → `data_layer` map (doc 05 §3 projection of the hit) |
| updated_at | TIMESTAMPTZ NOT NULL | last projection write |

Indexes: **GIN(`tsv`)**, **GIN(`exact_keys`)**, GIN(`body` `gin_trgm_ops`) for fuzzy/prefix, and B-tree `(entity_id, market_id, channel_id)` so the scope predicate stays cheap. UNIQUE(object_type, object_id).

**The index holds no layered figure as searchable text.** `body`/`tsv` carry only non-sensitive match material (names, refs, statuses). Money/margin/commission appear only as **`subtitle` fields tagged in `field_layers`**, projected away at query time for principals lacking the layer (§2.4) — so the index can never leak a price via a snippet.

## 2.3 The projection consumer
```
on event e:                                       // subscribes to orders, crm, inventory, activations, invoices topics
  (objType, objId) = targetOf(e)
  if not searchable(objType): ack; return
  doc = projectSearchDoc(objType, objId)           // read current aggregate state (Postgres SoR, doc 01 §3a)
  upsert search_document(object_type=objType, object_id=objId, ...doc, updated_at=now())  // idempotent on (type,id)
  ack(e)
// cancelled/deleted aggregates → soft-remove from index (deleted_at) so they stop matching
// full rebuild = truncate search_document + replay the log (doc 01 §3a) — the standard projection-rebuild runbook
```
Idempotent: re-processing an event re-upserts the same row. Eventual-consistency lag is operational-search-acceptable (sub-second typical).

## 2.4 Query: scope filter + layer projection on every hit
```
search(principal, q, types?, scope_filters?, limit, cursor):
  scopeP = scopePredicate(principal, types)           // doc 05 §2 — entity/market/channel + own/team
  matchP = buildMatch(q):                              // exact_keys[] exact ⊕ tsv @@ websearch_to_tsquery ⊕ trgm prefix/fuzzy
  rows = SELECT * FROM search_document
         WHERE deleted_at IS NULL AND scopeP AND matchP
         ORDER BY rank(exactHit DESC, ts_rank(tsv,query) DESC, updated_at DESC)
         LIMIT limit AFTER cursor
  for r in rows:
    allowedLayers = viewableLayers(principal, r.object_type)        // doc 05 §3
    r.subtitle = projectSubtitle(r.subtitle, r.field_layers, allowedLayers)  // strip layered fields
  return rows + next_cursor
```
- **No leakage via the index:** an out-of-scope row never passes `scopeP` — it is absent from results **and** from any count/aggregate (doc 05 §2). An in-scope hit whose `subtitle` carries a `commercial`/`profitability`/`commission` figure has that field **stripped** for a principal lacking the layer (doc 05 §3) — exact-match on `serial`/`order_no` still works (those are not layered), but the price/margin/commission never renders.
- **Exact direct hits** (`serial`, `order_no`, `invoice_no`, `customer_po_number`) rank first — the recall/warranty and order-lookup paths (doc 06 `/serials/{serial}`, `/orders/{id}`) resolve a single hit and deep-link.

## 2.5 REST
```
GET /search?q=&types=order,party,serial,deal,invoice&entity_id=&market_id=&channel_id=&limit=&cursor=
            → { hits:[{ object_type, object_id, title, subtitle, deep_link, score }], next_cursor }
            // scope-filtered + layer-projected; subtitle omits fields the caller's layers don't grant
GET /search/suggest?q=&types=        → [{ object_type, object_id, title }]   // prefix/typeahead (trgm), same wall
```

## 2.6 Events
- `search.reindex.requested` · key `object_type` · {object_type, since?} · → triggers a scoped rebuild (admin/ops; doc 01 projection-rebuild runbook). The index itself emits nothing to the spine (it is a pure consumer).

---

# Section 3 — REPORTING & EXPORTS + THE HORIZONS FEED

Two related but distinct surfaces: **(a) standard reports + exports** for humans (layer-respecting xlsx/csv), and **(b) the Horizons feed** — the machine contract that streams Conduit's **units→revenue→COGS→GP** into Horizons (the downstream P&L/analytics consumer). Both are **projections of the event spine + ledger events**; neither computes financial truth (doc 04 §Ledger owns recognition).

## 3.1 Standard reports (read-models)

Reports are **projection read-models** (materialised views / projection tables) built by consumers, queried through filtered, layer-projected endpoints. Initial catalogue:

| Report | Built from | Default layers | Notes |
|---|---|---|---|
| Sales by period (units / revenue / GP) | `dispatch.delivered` + `ledger.posted` | volume / commercial / profitability | the same source as the Horizons feed (§3.3) — one truth |
| Order book / open orders | `order.placed/amended/cancelled`, `order.allocated`, `dispatch.*` | volume / commercial | scheduled-demand by tranche |
| Inventory on-hand / valuation | `inventory.received/adjusted/transfer`, `dispatch.*` | volume / profitability(cost) | specific-identification batch cost (doc 04 §Ledger) |
| Commission by agent/period | `commission.accrued/posted/clawed` | commission | `own`-breadth for agents (doc 05 §4) |
| Sell-through / coverage | `activation.recorded`, H6Q projection (doc 12) | volume / commercial | reuses the H6Q export path (doc 06 `/h6q/export`) |
| AR aging / invoice register | `order.invoiced`, `ledger.posted` | commercial | bill-to/payer attribution (doc 04 §Ledger) |
| Returns / RMA summary | `return.*` (doc 09) | volume / commercial | disposition + reversal totals |

All reports honour the **period model** (doc 14 §2): a figure's fiscal period is `occurred_at AT TIME ZONE :reporting_tz` over the fiscal calendar — **re-sliceable by re-projection**, never baked in. Consolidated/group presentation is **USD** (doc 02 §A, doc 14 §2.4); a report can present in transaction currency, functional currency, or USD via the provenanced FX register (doc 14 §1.4).

### `report_definition` — registered report (data)
`code TEXT`, `name TEXT`, `source_view TEXT`, `default_layers TEXT[]`, `group_by_axes TEXT[]` (`period`/`market`/`channel`/`agent`/`variant`/`entity`), `currency_modes TEXT[]` (`txn`/`functional`/`usd`). New report = a registered definition + its read-model, not bespoke code per consumer.

## 3.2 Exports (xlsx / csv — layer-respecting)

Any report renders to **xlsx or csv**. The export goes through the **same policy layer** as the on-screen report — **a cell is layer-projected exactly as a field is** (doc 05 §3): a `volume`-only principal's export has **no price/cost/margin columns at all** (they are omitted, not blanked — no header, no leakage via column structure).

### `export_job` — async export
| Column | Type | Notes |
|---|---|---|
| requested_by | UUID → app_user NOT NULL | the principal whose **scope + layers** the export is rendered under |
| report_code | TEXT → report_definition NOT NULL | |
| params | JSONB NOT NULL | filters (period, market, channel, currency_mode, group_by) |
| format | TEXT NOT NULL | `xlsx`/`csv` |
| locale | TEXT → locale.code NOT NULL | number/date/currency formatting (doc 17 §4.3, ICU/CLDR) |
| applied_layers | TEXT[] NOT NULL | **frozen at request time** = the requester's viewable layers (doc 05 §3) — recorded for audit |
| scope_snapshot | JSONB NOT NULL | the requester's scope grants at request time (doc 05 §2) — recorded for audit |
| status | TEXT NOT NULL | `queued`/`running`/`ready`/`failed`/`expired` |
| storage_uri | TEXT NULL | object-store location of the rendered file |
| content_sha256 | TEXT NULL | integrity / re-performability (doc 14 §5.1) |
| row_count | INTEGER NULL | |
| expires_at | TIMESTAMPTZ NULL | export link TTL (PII/commercial hygiene) |

```
runExport(job):
  rows = queryReport(job.report_code, job.params, principal=job.requested_by)   // scope-filtered (doc 05 §2)
  cols = reportColumns(job.report_code) filter (c => c.layer is None || c.layer in job.applied_layers)  // doc 05 §3
  for row in rows: project each cell by col.layer; format money/date via ICU(job.locale)   // doc 17 §4.3 — no float
  file = write(job.format, cols, rows); sha = sha256(file); store(file)
  emit export.completed
```
- **Layer truth at render, not request UI:** `applied_layers`/`scope_snapshot` are frozen from the requester's grants server-side — a user cannot widen an export beyond what they can read on screen.
- Money formats per `locale` via the **same ICU/CLDR formatter** as documents (doc 17 §4.3) — `de-DE` `1.234,50 €`, JPY 0-minor-units — and **no float touches a figure** (doc 14 §1).
- The file is stored in the object store with a `content_sha256` and a TTL; the download link is authenticated and re-checks the principal.

### REST
```
GET  /reports                                  → [ReportDefinition]   (only those the caller can run)
GET  /reports/{code}?period=&market=&channel=&group_by=&currency_mode=&cursor=
                                               → { columns, rows, totals, next_cursor }   (scope + layer projected)
POST /reports/{code}/export  { format, params, locale }   → ExportJob   (async; 202)
GET  /exports/{job_id}                         → ExportJob   (status; storage link when ready)
```

## 3.3 The Horizons feed — units→revenue→COGS→GP contract

**Horizons** is the downstream **P&L / analytics consumer** (doc 04 §Ledger: "P&L construction… is a downstream consumer (future ERP / Horizons)"). Conduit owns AR + inventory sub-ledgers and the **recognition trigger** (`dispatch.delivered`, ASC 606) — Horizons constructs the P&L from this feed. The feed is **derived, not entered**: it is a typed, versioned projection of `dispatch.delivered` (revenue + matched COGS, doc 03/04) reconciled against `ledger.posted` (doc 03 Ledger), at the **fact-grain of one delivered order/tranche line**.

### Grain & derivation
On each `dispatch.delivered` (the single recognition point, doc 04 §Ledger), per delivered line:
- **units** = delivered qty (Squants count; doc 14 §1).
- **revenue** = `revenue_amount` (line revenue ex-VAT, doc 03 `dispatch.delivered.lines[].revenue_amount`) — recognised at delivery (ASC 606).
- **COGS** = `batch_landed_cost` × qty — the **specific batch landed cost** of the delivered serials (strict specific-identification, **no weighted-average**; doc 04 §Ledger). This is the `COS_CLEARING` amount Conduit relieves on delivery and Horizons reclassifies into COGS (doc 07 M13).
- **GP** = revenue − COGS; **GP% ** = GP / revenue (presentation only — the typed figures are revenue & COGS, doc 14 §1.3 conservation).
- All three currencies are carried: **txn**, **functional** (entity, doc 02 §A), and **USD** (group presentation) using the **provenanced FX row** that the ledger used (doc 14 §1.4) — so Horizons re-derives the exact same USD.

Reconciliation: the sum of feed revenue/COGS for a period must tie to the `ledger.posted` AR/revenue and `COS_CLEARING` totals (doc 14 §5.2 reconciliation engine) — a divergence is a control exception, not a silent re-sum (doc 17 §4.2 stance).

### `horizons_feed_fact` — the feed row (gapless, versioned)
| Column | Type | Notes |
|---|---|---|
| fact_seq | BIGINT NOT NULL | **gapless sequence** per feed stream (completeness control, like doc 17 §3 numbering) |
| source_event_id | UUID NOT NULL | the `dispatch.delivered` envelope `event_id` (idempotency + lineage) |
| order_id / tranche_id / order_line_id | UUID | grain refs |
| entity_id / market_id / channel_id | UUID | scope/analytic axes |
| sold_to_party_id / bill_to_party_id | UUID | sell-in vs stats attribution (doc 02 §F: AR→bill-to, stats→sold-to) |
| product_variant_id | UUID | + denormalised family/category for analytics |
| serials | TEXT[] | delivered serials (specific-identification lineage) |
| occurred_at | TIMESTAMPTZ NOT NULL | delivery instant (period = re-projection, doc 14 §2) |
| units | NUMERIC(18,4) NOT NULL | delivered qty |
| revenue_txn / cogs_txn | NUMERIC(18,4) | + `txn_currency CHAR(3)` |
| revenue_func / cogs_func | NUMERIC(18,4) | + `functional_currency CHAR(3)` (doc 02 §A) |
| revenue_usd / cogs_usd | NUMERIC(18,4) | group presentation (doc 02 §A) |
| fx_rate_ref | UUID → exchange_rate NULL | the provenanced row used (doc 14 §1.4) — Horizons re-derives identically |
| recognition_type | TEXT NOT NULL | `sale` / `return_reversal` (doc 09) / `intercompany` (doc 13, elimination-tagged) |
| reverses_fact_seq | BIGINT NULL | for return/credit-note reversals (doc 09) — never edits the original fact |
| schema_version | INTEGER NOT NULL | feed contract version |

Indexes: UNIQUE(source_event_id, order_line_id) (idempotency), `(occurred_at)`, `(entity_id, market_id, channel_id, occurred_at)`. **Append-only**: a return/credit (doc 09) emits a **reversing fact** (`recognition_type='return_reversal'`, `reverses_fact_seq`), it never mutates the original — same discipline as the immutable ledger (doc 04 §Ledger) and credit-note model (doc 17).

### The feed event (what Horizons subscribes to)
```
record HorizonsFeedFact {            // Avro on conduit.reporting topic; BACKWARD-compatible evolution (doc 03 §2)
  long    fact_seq;
  string  source_event_id;
  string  order_id; union{null,string} tranche_id; string order_line_id;
  string  entity_id; union{null,string} market_id; union{null,string} channel_id;
  string  sold_to_party_id; string bill_to_party_id; string product_variant_id;
  array<string> serials;
  long    occurred_at;
  string  units;                     // decimal-as-string (no float, doc 14 §1)
  Money   revenue_txn; Money cogs_txn;
  Money   revenue_func; Money cogs_func;
  Money   revenue_usd; Money cogs_usd;          // GP derived downstream = revenue − COGS
  union{null,string} fx_rate_ref;
  string  recognition_type; union{null,long} reverses_fact_seq;
  int     schema_version;
}
```
GP is **not** carried as a stored figure — it is `revenue − COGS` (Horizons derives it), keeping the two conserving typed figures (doc 14 §1.3) authoritative and GP a pure function (avoids a third reconcilable number).

### Cadence & delivery
- **Primary: streaming.** The feed consumer reacts to each `dispatch.delivered`, derives the fact(s), writes `horizons_feed_fact`, and emits `horizons.feed.fact` to `conduit.reporting` — **near-real-time**, idempotent on `source_event_id`. Returns/credits emit reversing facts off `return.*` (doc 09).
- **Secondary: period batch.** At **monthly close** (doc 14 §2.4) a **period snapshot** (`horizons_feed_period`) is emitted for the closed, **locked** period — the GAAP-final cut Horizons books against (revenue/COGS/FX-translated, doc 14 §2.4). A locked period's facts are frozen; late items are controlled prior-period adjustments (doc 02 §A `accounting_period`, doc 05 §5).
- **Backfill/rebuild:** because the feed is a projection, Horizons (or a re-keyed Horizons) is **backfilled by replaying** `dispatch.delivered`/`return.*` from the log (doc 01 §3a) — `fact_seq` is re-derived deterministically and idempotency on `source_event_id` prevents double-counting.

### Access-layer note on the feed
The feed carries `commercial` (revenue) + `profitability` (cost/GP) + `volume` (units) content. As an **external/downstream consumer** it subscribes to a **layer-filtered projection** per doc 05 §3 ("Projection applies to events too… external adapters subscribe to layer-filtered projections, never raw events") — Horizons is granted the full P&L layers by contract; any narrower analytics consumer gets a volume-only or commercial-only projection. Intercompany facts are **elimination-tagged** (doc 13) and carry `inter_entity`-layer transfer-price basis only to entitled consumers.

### Horizons feed events
- `horizons.feed.fact` · key `order_id` · {the `HorizonsFeedFact` above} · → Horizons P&L/analytics
- `horizons.feed.period` · key `period_key` · {entity, period_key, reporting_tz, revenue_usd, cogs_usd, gp_usd, units, fx_basis, locked_at} · → Horizons GAAP-final period book
- `export.completed` · key `export_job_id` · {report_code, format, row_count, content_sha256} · → notify requester (§1), audit

---

# Section 4 — LOCALIZATION / i18n

The full localization story across the **15 supported languages** (en, es, fr, de, nl, ga, it, pt, pl, no, sv, da, fi, **ja**, **th** — scripts incl. **CJK** and **Thai**; doc 02 §A `locale`). One fallback chain, one formatter, no hard-coded strings, CI-enforced. **No RTL languages are in scope** (doc 08 §i18n).

## 4.1 The locale fallback chain (single, canonical)

The **same chain** governs every localized surface — app strings, product names, documents (doc 17 §2.3), and notification bodies (§1.6):

```
resolveLocale(context):
  // customer-facing content (documents, customer notifications, quotes):
  loc = payer.billing_profile.invoice_locale          // doc 02 §C — most specific, for the billed party
     ?? party.preferred_locale                         // doc 02 §C — the account's language
     ?? market.default_locale(jurisdiction)            // doc 02 §A — the market's default language
     ?? 'en'                                            // global fallback
  // staff-facing surfaces (the app UI, the desk): user's signed-in preferred locale → 'en'
  return loc
```
- **Customer-facing** (`product_translation` doc 02 §D, documents doc 17 §2.3, customer notifications §1.6) → `invoice_locale` → `preferred_locale` → `market.default_locale` → `en`.
- **Staff-facing** (Flutter app + React desk chrome) → the signed-in user's locale → `en`.
- A locale resolves to its **language** for string lookup (BCP-47 `de-DE` → `de`) and to its **full BCP-47 tag** for ICU number/date/currency formatting (region matters: `en-GB` vs `en-US`, `fr-FR` vs `fr-CA`).

## 4.2 App strings — Flutter ARB + the React desk

Two front-ends (doc 01 §6), one source-of-truth string discipline:

- **Flutter companion app (doc 08):** `flutter_localizations` + the `intl` package with **ARB** (`.arb`) message files per locale (`app_en.arb`, `app_de.arb`, …, `app_ja.arb`, `app_th.arb`). Messages use **ICU MessageFormat** (plurals, gender, select, nested args) so plural/gender rules are correct per language (e.g. `pl`/`ru`-family plural categories). `gen_l10n` generates the typed `AppLocalizations`. **No hard-coded user-facing strings** (lint-enforced) — every label, button, and message is a keyed ARB entry.
- **React desk (doc 01 §6, the back-office, doc 10 §B):** **`react-intl` (FormatJS)** with the **same ICU MessageFormat** message catalogues (`messages.<locale>.json`), extracted from the source by the FormatJS CLI. Same key namespace conventions as the app where strings overlap (status labels, domain terms) so a glossary term translates once.
- **Shared message keys + glossary:** domain terms (ADLP, coverage, tranche, sell-in/sell-through — doc 10 §E glossary) have **canonical keys** and a **translator glossary** so they render consistently across app + desk + documents in each language.
- **Fonts:** the app and desk bundle font stacks covering **Latin + diacritics, CJK (Japanese), and Thai** (doc 08 §i18n, doc 02 §A) — the same `font_stack` discipline as documents (doc 17 §4.4). Document fonts are embedded PDF/A (doc 17 §4.4); app/desk fonts are bundled web/app fonts with the CJK/Thai subsets.

## 4.3 `product_translation` — localized catalogue (extends doc 02 §D)

Customer-facing surfaces (app, documents, quotes) render a variant's name/description in the resolved locale via `product_translation` (doc 02 §D: `(product_variant_id, locale) → display_name, description`), under the **§4.1 fallback chain** (variant translation in locale → market default locale → English). This is the **operational** name/description (marketing copy stays on the website, doc 02 §D). The localized name flows into:
- the **document render model** (doc 17 §4.2: `description = product_translation(line.variant, loc).display_name`),
- **notification bodies** (§1.6 render model),
- **search** display (`search_document.title`/`body` indexed per the translation's `lang`, §2.2, so a Japanese product name is findable in Japanese),
- **reports/exports** product columns (rendered in the export `locale`, §3.2).

A missing translation falls back (never blanks) and is **surfaced to catalogue ops** (a "missing translations" report) so the gap is filled as data.

## 4.4 Localized document templates (tie to doc 17)

Documents resolve their template by **`(document_type, locale, jurisdiction)`** with the fallback chain (doc 17 §2.3) — **language varies by locale, legal content varies by jurisdiction**, all as data. This doc does not redefine that registry; it is the **i18n contract** doc 17 implements:
- **locale** chosen by the §4.1 chain (doc 17 §2.3 is identical: `invoice_locale → preferred_locale → market.default_locale → en`).
- **numbers/dates/currency** rendered by the single ICU/CLDR locale formatter at the last render step (doc 17 §4.3) — never to stored typed values; presentation rounding mode/boundary recorded (doc 14 §1.2).
- **font_stack** per template covers the locale's script incl. CJK + Thai; output is PDF/A (doc 17 §4.4).
- **Notification templates (§1.2) follow the same registry shape** keyed `(kind, channel, locale)` with the same fallback — so an invoice email and the invoice PDF render in the same resolved language.

## 4.5 Per-locale number / currency / date formatting (one formatter)

**A single locale-aware formatter** (CLDR/ICU) is the only place presentation formatting happens — shared by the app (`intl`), the desk (`react-intl`), documents (doc 17 §4.3), exports (§3.2), and notifications (§1.6). Properties (doc 17 §4.3, doc 14 §1):
- **Grouping/decimal separators, currency-symbol position, date order** come from **CLDR per BCP-47 locale** — `de-DE` `1.234,50 €`, `en-GB` `£1,234.50`, `fr-FR` `1 234,50 €`, `ja-JP` `￥1,235`. No hand-rolled formatting.
- **`minor_units` per currency** (doc 02 §A `currency.minor_units`): **JPY = 0** → presentation rounds to whole yen; the stored figure stays `NUMERIC(18,4)` (doc 14 §1.1). Presentation rounding is applied at render only, mode recorded (doc 14 §1.2) — the typed truth re-derives exactly.
- **Dates**: an instant is formatted `AT TIME ZONE entity.reporting_tz` then locale-formatted (doc 17 §4.3, doc 14 §2) — the period model (doc 14 §2) is unchanged; localization is presentation, not storage.
- **Currency vs locale are independent**: a `de-DE` user viewing a GBP order sees GBP formatted in German conventions (`1.234,50 £` grouping) — the **currency** is the money's, the **format conventions** are the locale's.

## 4.6 CI pseudo-localization

Pseudo-localization runs in **CI** to catch i18n defects before they ship (doc 08 §i18n: "pseudo-localization in CI to catch truncation"):
- A generated **pseudo-locale** (`en-XA`-style) transforms every string: **accent/expand** (`Edit` → `[É_ðîţ_one_two]`) to expose **truncation** and **clipping** (CJK/Thai and German compounds run long), and **bracket** every string so any **hard-coded (un-keyed) string** is visibly un-bracketed in screenshots/snapshots.
- **Key-coverage gate:** CI fails if any locale's ARB/`messages.<locale>.json` is **missing keys** present in the source (`en`) catalogue, or carries **stale keys** — every supported locale stays complete.
- **ICU validation:** CI validates ICU MessageFormat syntax and **argument/plural-category coverage** per language (a `pl` plural with missing `few`/`many` fails).
- **Format snapshot tests:** golden tests assert the ICU/CLDR output for representative `(locale, currency)` pairs (incl. JPY 0-minor-units, `de-DE` grouping, Thai/Japanese rendering) so a CLDR/library bump can't silently change a customer-facing figure.
- **Font-coverage check:** CI asserts the bundled/embedded font stacks cover the codepoints of every locale's catalogue (no tofu for CJK/Thai), mirroring the document `font_stack` requirement (doc 17 §4.4).

## 4.7 Adding a language / market (data, not code)

Switching a market on (doc 02 §A — year-1 is **UK-only**, the rest is the configured roadmap) is **data**: seed the `locale`/`market`/`currency` rows (doc 02 §A), add the ARB/`messages` catalogue + glossary for the language, add `document_template` rows for the jurisdiction (doc 17 §2), add `notification_template` rows per kind (§1.2), and populate `product_translation` (doc 02 §D). CI's coverage gate (§4.6) then enforces completeness. **No code change, no migration.**

---

## Acceptance

A platform-services implementation is **done** when:

1. **Notifications are idempotent projections of the spine.** Every notifying domain event (`forecast.cycle.opened`, `adlp.exception.approved`, `dispatch.delivered`, backorder-filled `inventory.received`, `commission` true-up, etc.) produces notifications via `notification_rule` data; a **redelivered `event_id` notifies no one twice** (UNIQUE(recipient, kind, source_event_id), doc 03 §3); a full replay over the log rebuilds the inbox without duplicate sends.
2. **Notifications obey the access wall.** A recipient out of an event's scope is silently skipped (doc 05 §2 — no row, no leakage); a body's layered figure (commission/price/margin) is stripped or channel-downgraded for a recipient lacking the layer (doc 05 §3); commission notifications are `own`-breadth; mandatory kinds (approvals, ADLP) cannot be fully silenced (segregation of duties, doc 05 §5). Channels honour per-user preferences, quiet hours, and digesting; providers are behind a swappable port.
3. **Search is one box, scope-filtered and layer-projected.** Orders, parties, serials, deals, invoices are searchable from `/search`; **PostgreSQL FTS (`tsvector`+GIN) + `pg_trgm`** (+ CJK/Thai segmentation) backs it — justified over OpenSearch by one-enforcement-path access control, fit-for-scale, and replayability; an out-of-scope row never appears in hits **or** counts (doc 05 §2), and a hit's layered subtitle field is stripped for principals lacking the layer (doc 05 §3); the index holds no layered figure as searchable text; it rebuilds by replay.
4. **Reports & exports respect data layers.** A report/export renders only the columns whose layer the requester holds — a `volume`-only user's xlsx has **no price/cost/margin columns** (omitted, not blanked); money/dates format per locale via ICU/CLDR (JPY 0-minor-units), no float touches a figure; the export freezes `applied_layers`/`scope_snapshot` server-side, stores a `content_sha256`, and is reconstructable.
5. **The Horizons feed is a derived, reconcilable, gapless contract.** Each `dispatch.delivered` emits `horizons.feed.fact` carrying **units, revenue (ASC 606), COGS (specific-identification batch landed cost — no weighted-average), in txn/functional/USD** with the provenanced FX row; **GP = revenue − COGS** (derived, not stored); facts are append-only and gapless (`fact_seq`), idempotent on `source_event_id`, with returns/credits as **reversing facts**; the monthly **locked-period** snapshot (`horizons.feed.period`) is the GAAP-final cut; the feed **ties to `ledger.posted` AR/revenue/`COS_CLEARING`** (doc 14 §5.2) and backfills by replay.
6. **One fallback chain, everywhere.** `party.preferred_locale → market.default_locale → en` (customer-facing prefers `billing_profile.invoice_locale`) governs app strings, `product_translation`, documents (doc 17 §2.3), and notification bodies identically; a missing `product_translation`/template falls back, never blanks, and the gap is surfaced.
7. **15 locales render correctly, incl. CJK + Thai.** Flutter ARB (`intl`) + React `react-intl` carry ICU MessageFormat catalogues for all 15 languages with shared domain-term keys; the single ICU/CLDR formatter renders numbers/currency/dates per locale (`de-DE 1.234,50 €`, `en-GB £1,234.50`, JPY whole-yen); fonts cover CJK + Thai (no tofu); **no hard-coded user-facing strings**.
8. **CI enforces i18n quality.** Pseudo-localization catches truncation and un-keyed strings; the key-coverage gate fails on any locale missing/stale keys; ICU plural-category and format-snapshot golden tests pass; font-coverage is asserted — all before merge.
9. **Adding a market/language is data, not code.** Seeding `locale`/`market`/`currency` + ARB/`messages` + `document_template` + `notification_template` + `product_translation` brings a new market online with **no code change, no migration**; year-1 ships **UK-only** (doc 02 §A), the rest switches on as data.

> Supports **M14** (doc 07: companion app, Horizons feed, reporting/exports, HubSpot replication — "units→revenue→COGS→GP feed reaches Horizons; reports respect data layers") and the **cross-cutting Notifications / Search / Reporting+Horizons / Localization** rows in doc 10 §B/§D. Every service here is an idempotent, replayable consumer/projection of the event spine (doc 03) that inherits the access wall (doc 05) — none is a second source of truth.
