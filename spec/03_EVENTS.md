# 03 — Events

## 1. Envelope (every event)

Avro on the wire; the envelope wraps a typed `payload`.

```
record EventEnvelope {
  string  event_id;        // UUID; idempotency key
  string  event_type;      // e.g. "order.placed"
  int     schema_version;  // payload schema version
  string  aggregate_type;  // "order" | "serial" | ...
  string  aggregate_id;    // UUID
  string  partition_key;   // ordering key (e.g. order_id, serial)
  Scope   scope;           // { entity_id, market_id?, channel_id? }
  string  correlation_id;  // UUID; trace
  union { null, string } causation_id;  // UUID of the event that caused this
  string  actor;           // user keycloak_id | "system:<module>"
  long    occurred_at;     // epoch millis (UTC)
  bytes   payload;         // Avro-encoded, typed per event_type+version
}
```

Topics: one per `aggregate_type` (`conduit.orders`, `conduit.inventory`, `conduit.activations`, `conduit.pricing`, `conduit.crm`, `conduit.commission`, `conduit.ledger`, `conduit.forecast`, `conduit.purchasing`). Partition by `partition_key`.

## 2. Registry & evolution rules

- Schemas registered per `(event_type, version)`; compatibility **`BACKWARD`** (new schema can read old data).
- Allowed without major-version bump: **add optional fields with defaults**, add enum symbols (consumers must tolerate unknowns), widen numeric types.
- Forbidden in-place: remove/rename required fields, change field type incompatibly, change semantics. These ship as a **new `event_type` or version**, produced in parallel until consumers migrate, then the old one is retired.
- CI gate: `sbt schemaCheck` validates every changed schema against the registry's latest under `BACKWARD`; fails the build otherwise.
- Consumers **ignore unknown fields** and pin a **minimum** `schema_version` they understand.
- **Custom attributes ride outside the Avro schema.** Events for extensible objects (party, contact, deal, product_variant, order header, activity) carry an `attributes` field typed as Avro `map<string,string>` (or a JSON-encoded string) that mirrors the object's governed `attributes` JSONB (doc 02 §M). Adding/removing a custom property is a **`property_definition` registry change only** — never an Avro schema bump, never a coordinated deploy. The typed spine stays `BACKWARD`-governed; the flexible edge evolves freely inside the `map`.

## 3. Delivery semantics

At-least-once. Every consumer is **idempotent on `event_id`** (dedupe table or natural idempotency, e.g. activation first-write-wins). Ordering guaranteed per `partition_key` only. Poison messages → DLQ topic `<topic>.dlq` after N retries; replay tooling re-emits from a checkpoint or time window.

## 4. Catalog (initial)

Each entry: type · partition key · key payload fields · canonical consumers.

### CRM
- `crm.company.created/updated` · key `company_id` · {company snapshot} · → projections, HubSpot-marketing, audit
- `crm.deal.created/stage_changed/won/lost` · key `deal_id` · {deal_id, pipeline, stage, probability_pct, value, currency, volume_p20/50/80, company_id, owner} · → H6Q pipeline projection, audit. `won` carries `order_id`.

### Pricing / ADLP
- `pricing.rule.changed` · key `price_rule_id` · {surface, scope, before, after, version, approved_by} · → pricing read-model, audit, Xero-tax-map
- `adlp.exception.requested/approved/rejected` · key `exception_id` · {order_id, line_id, requested_price, volume_denomination, status, approved_by, memo_ref} · → order release, commission gate, audit

### Orders
- `order.placed` · key `order_id` · {order header + lines (variant, qty, unit_price_ex_vat, discount_pct, tax_regime, adlp_category), **delivery_schedule (tranches: seq, qty, requested_date)**, entity_id, company_id, channel_id, market_id, agent_id, deal_id, currency, totals, payment_method} · → **allocation (per tranche)**, **commission accrual**, **ledger commitment**, H6Q pipeline→actual + scheduled-demand, notifications, audit
- `order.amended` · key `order_id` · {before, after, actor, reason} · pre-dispatch, permission-gated · → re-price/re-ADLP, re-allocation, commission recompute, audit (`order_amendment`)
- `order.cancelled` · key `order_id` · → release allocations, commission void, ledger void
- `order.allocated` · key `order_id` · {line_id, tranche_id?, allocations[]} · → fulfilment
- `dispatch.created` · key `order_id` · {dispatch_no, tranche_id?, carrier, tracking, serials[]} · → stock decrement, serial lifecycle, OTD
- `dispatch.delivered` · key `order_id` · {dispatch_no, tranche_id?, delivered_at, lines:[{serial, batch_landed_cost, revenue_amount}], currency} · → inventory relief to `COS_CLEARING`, **auto-invoice + matched revenue+COGS recognised downstream per tranche (ASC 606)**, warranty/OTD
- `order.invoiced` · key `order_id` · {invoice_no, tranche_id?, totals, tax} · **auto-triggered by `dispatch.delivered`** · → **ledger (AR + revenue)**, **Xero**, email
- `return.raised/assessed/approved/restocked/refunded/replaced` · key `rma_id` · {order_id, type (full_unit/part_only/…), scope, serials/component, disposition, refund_amount, replacement_order_id} · → inventory (restock/refurb/scrap), serial lifecycle, ledger reversal, commission claw, audit *(lifecycle deep-dived in doc 09)*

### Inventory / Traceability
- `inventory.received` · key `po_id` · {grn, lines, lot_batch, landed_unit_cost, serials[]} · → stock increment, backorder auto-allocate (per tranche by requested_date), ledger (inventory asset)
- `inventory.count.posted` · key `location_id` · {count_id, corrections[{variant, variance}], approved_by} · → stock corrections, ledger, audit (maker≠checker)
- `inventory.transfer.requested/dispatched/received` · key `transfer_id` · {from_loc, to_loc, variant, qty, serials} · → stock out/in-transit/in, serial location, (cross-entity → intercompany)
- `inventory.adjusted` / `inventory.write_off` · key `location_id` · {kind, variant/serials, qty, reason, evidence, approved_by} · → stock, serial status (quarantined/scrapped), **ledger write-down at batch landed cost**, audit (maker≠checker)
- `dispatch.created` / `dispatch.delivered` — see Orders.
- `serial.lifecycle` · key `serial` · {event_type, refs} · → genealogy projection
- `activation.recorded` · key `serial` · {placement_id, version, installer, owner, model, country, activated_at, is_first} · → serial bind/off-shelf, warranty start, **sell-through projection**, H6Q, notifications

### Commission
- `commission.accrued/posted/clawed` · key `agent_id` · {order_id, line_id, basis_amount, rate, amount, currency, status} · → ledger two-phase, statements

### Purchasing / Supply
- `po.created/sent/received/closed` · key `po_id` · → supply projections, ledger (AP)
- `replenishment.suggested` · key `product_variant_id` · {entity, location, net_req, suggested_qty, by_date} · → supply board

### Warranty
- `warranty.provision.accrued` · key `serial` · {entity, warranty_start, warranty_end, estimated_provision, currency} · → exposure register, downstream balance-sheet, audit
- `warranty.provision.released` · key `serial` · {amount, period, outstanding} · → release cycle, downstream P&L/BS, consolidated exposure
- `warranty.claim.raised` · key `serial` · {cost, resolution, rma_id?} · → draw down provision, RMA, audit

### Intercompany / FX
- `intercompany.movement.posted` · key `intercompany_link_id` · {sell_order, buy_po, transfer_price, basis, from/to entity, currencies} · → both ledgers (linked transfers), tax/customs, elimination tag
- `fx.rate.set` · key `ccy_pair` · → revaluation, conversions
- `fx.hedge.created/updated/closed` · key `hedge_id` · {pair, instrument, contracted_rate, notional, notional_used, valid_from, valid_to, entity, status} · → cost FX designation, **consolidated reporting/translation**, audit (treasury permission)

### Forecast
- `forecast.cycle.opened/closed` · key `cycle_id` · {code, period_start, period_end, cadence} · → create outstanding submissions, notify owners
- `forecast.submitted` · key `cycle+company` · {cycle_id, forecaster, company/branch, lines:[{variant, period, scenario, qty}], device} · → **bottom-up coverage rollup** (account→branch→customer→segment→sub-channel→channel→market, and by agent), accuracy projection, audit
- `forecast.updated` · key `channel+market+period` · {scenario, qty, before, after, actor} · → coverage projection, audit

### Ledger (emitted by ledger poster, for downstream/reporting)
- `ledger.posted` · key `tb_transfer_id` · {accounts, amount, currency, code, links} · → GL projection, Xero, reporting

### Access / Audit
- `access.permission.granted/revoked` · key `user_id` · → audit
- All of the above also feed the **audit projection**; the staff-action subset (orders, pricing, exceptions, adjustments, permission changes) is the action-audit feed.
