# 13 — Intercompany & Transfer Pricing

Build-grade deep-dive for the **intercompany + transfer-pricing** subsystem. Same template as 02–05: field-level schemas, outbox events, pseudocode, state machines, REST contracts, permission/data-layer mappings, Acceptance block. This document **references and extends** the spine; it does not redefine tables already in doc 02. Tables it builds on: `entity` (`procurement_parent_id`, `entity_type`), `transfer_price_policy`, `intercompany_link`, `lot_batch`, `fx_hedge`, `fx_rate`/`exchange_rate`, `purchase_order`/`po_line`, `order`/`order_line`, `stock_transfer`, `accounting_period` (doc 02 §A/§F/§G/§H/§I). It builds on the algorithms in doc 04 (§Intercompany transfer pricing, §FX, §Ledger), the events in doc 03 (`intercompany.movement.posted`, `fx.*`), the access wall in doc 05 (`inter_entity` + `treasury` layers), and the GAAP index in doc 14 (ASC 830 translation, consolidation, elimination).

Scope: the **procurement-chain topology** (operating-market entity ← Singapore procurement hub ← Luxshare; year-1 single hop UK ← Luxshare-UK); **transfer-pricing methods** (`cost_plus` / `resale_minus` / `fixed`) computed off the **specific `lot_batch` landed cost** with reproducible **TP documentation**; **paired-leg postings** (two linked TigerBeetle transfers across the two entities' currency ledgers, `FX_CLEARING` bridge when currencies differ, `flags.linked` atomic); **elimination tagging** for consolidation; **import VAT/duty** on the buying side (HS code + destination regime — interfacing with the separate tax/customs engine); and **consolidated USD reporting/translation** (ASC 830, hedge register).

> **What this doc owns vs the tax/customs engine.** This subsystem owns the **intercompany movement** end-to-end: topology resolution, transfer-price derivation + documentation, the paired-leg ledger postings, the FX bridge, elimination tagging, and consolidated USD translation. It **does not** own VAT/duty *determination*. It **calls** the separate **tax & customs engine** (doc 10 §B — VAT place-of-supply, B2B reverse charge, EU vs ROW import VAT, US destination sales tax, CA GST/HST/PST, HS/commodity codes, Intrastat/EC sales lists — almost certainly an Avalara/TaxJar/Stripe Tax integration) with the buy-side context (HS code, ship-from/ship-to jurisdictions, destination `tax_regime`, incoterm, customs value) and **records** what it returns against the buy leg. The boundary is the `TaxQuote` request/response contract in §6. Year-1 (UK ← Luxshare-UK domestic, no cross-border hop) the engine returns standard UK VAT / reverse charge and there is no import duty leg.

---

## 1. Procurement-chain topology (configuration, not migration)

The buying chain is expressed entirely as **data** on `entity` (doc 02 §A): `entity_type ∈ {operating, holding, procurement_hub}` and `procurement_parent_id` (who this entity buys from, per hop), plus a `transfer_price_policy` per hop. Conduit must run **both** the year-1 simple setup and the future multi-tier group **without a schema change** — switching topology is adding entity rows + policies, never a migration.

### 1.1 The two configurations

```
TARGET (multi-tier, "global"):
  Luxshare (external CM, bills USD)
      │  external purchase_order (type='external')           ← real supplier
      ▼
  Singapore procurement hub   entity_type='procurement_hub'  functional_ccy=USD
      │  intercompany hop      transfer_price_policy(SG → market)
      ▼
  Operating-market entity     entity_type='operating'        e.g. UK(GBP), DE(EUR), US(USD)
      │  customer sale (doc 04 §Orders)
      ▼
  end customer

YEAR-1 ("pre-global", single hop):
  Luxshare-UK supplier entity  (Luxshare billing, USD; doc 02 §H supplier.billing_currency)
      │  external purchase_order
      ▼
  UK operating entity          entity_type='operating'  functional_ccy=GBP
      │  customer sale
      ▼
  end customer
```

Year-1 has **minimal intercompany**: the UK entity buys directly; there is at most one intercompany hop (and in the simplest seed, none — UK buys the external PO itself). The multi-tier chain inserts the **Singapore hub** entity between Luxshare and the operating markets, adds `transfer_price_policy(SG → each market)`, and points each market's `procurement_parent_id` at SG. **That is the entire switch.** No code path branches on "year-1 vs global"; the resolver (§1.2) walks `procurement_parent_id` and applies whatever hops exist.

### 1.2 Walking the chain

```
procurementChain(operatingEntity):                 // returns hops from operating up to the external CM
  hops = []
  cur = operatingEntity
  while cur.procurement_parent_id is not null:
     parent = entity[cur.procurement_parent_id]
     hops.prepend( Hop(from=parent, to=cur) )       // parent sells to cur
     cur = parent
  // cur is now the topmost intercompany node; its supply comes from an EXTERNAL supplier PO (Luxshare)
  return Chain(externalRoot=cur, hops=hops)
```

- **Year-1:** `UK.procurement_parent_id = null` → `hops = []`, external root = UK → UK raises the external Luxshare-UK PO directly. (If a `Luxshare-UK` *entity* is modelled as the seller, that becomes a single hop; both are valid configs.)
- **Multi-tier:** `UK.procurement_parent_id = SG`, `SG.procurement_parent_id = null` → `hops = [SG→UK]`, external root = SG → SG raises the external Luxshare PO; the `SG→UK` hop is an intercompany movement (§3).
- **Deeper:** any number of hops (e.g. SG → EU-regional-hub → DE) works without code change — the loop just yields more hops.

A movement is created **per hop** by `moveStockBetweenEntities` (doc 04 §Intercompany, extended in §3). The chain resolver is the only place topology lives; everything downstream consumes hops.

### 1.3 `entity_topology_edge` (derived projection, read-model)

The chain is canonical on `entity` columns; this is a **materialised projection** for fast chain queries and for the consolidation/elimination engine (§7), rebuilt from `entity` + `transfer_price_policy` on `entity.*` change. Not a new source of truth.

| column | type | notes |
|---|---|---|
| from_entity_id | UUID → entity | the seller (procurement parent) |
| to_entity_id | UUID → entity | the buyer |
| hop_seq | INTEGER NOT NULL | 1 = nearest the external root |
| policy_id | UUID → transfer_price_policy NULL | resolved policy for the hop |
| from_currency | CHAR(3) NOT NULL | seller functional currency |
| to_currency | CHAR(3) NOT NULL | buyer functional currency |
| is_cross_border | BOOLEAN NOT NULL | `from.jurisdiction != to.jurisdiction` → import VAT/duty leg |
| is_intragroup | BOOLEAN NOT NULL DEFAULT true | drives elimination tagging |

UNIQUE(from_entity_id, to_entity_id). Index(to_entity_id, hop_seq). Rebuilt by the `entity.updated` / `transfer_price_policy.changed` consumer.

---

## 2. Transfer-pricing methods, policy & documentation

### 2.1 `transfer_price_policy` (extends doc 02 §I)

Doc 02 §I defines `transfer_price_policy(from_entity_id, to_entity_id, method, markup_pct, basis 'landed_cost', product_scope JSONB, effective_from, effective_to)`. This subsystem **extends** it with the fields the three methods and TP documentation require. (Columns below are additive; existing columns unchanged.)

| column | type | notes |
|---|---|---|
| method | TEXT NOT NULL | `cost_plus` / `resale_minus` / `fixed` (existing) |
| basis | TEXT NOT NULL DEFAULT 'landed_cost' | `cost_plus`/`resale_minus` compute off the **specific `lot_batch.landed_unit_cost`** (existing) |
| markup_pct | NUMERIC(7,4) NULL | `cost_plus`: + this % on landed cost (existing) |
| resale_margin_pct | NUMERIC(7,4) NULL | `resale_minus`: downstream resale price − this % |
| fixed_price | NUMERIC(18,4) NULL | `fixed`: the agreed unit transfer price |
| fixed_currency | CHAR(3) NULL | currency of `fixed_price` (else seller functional) |
| tp_currency | CHAR(3) NULL | currency the transfer price is **struck in** (default = seller functional currency) |
| rounding_boundary | TEXT NOT NULL DEFAULT 'unit' | where rounding lands (`unit`/`line`) — RoundingPolicy, doc 14 §1.2 |
| documentation_method | TEXT NULL | OECD method label for TP docs: `CUP`/`cost_plus`/`resale_price`/`TNMM` |
| arms_length_band | JSONB NULL | optional `{min_pct, max_pct}` sanity band for governance/audit |
| status | TEXT NOT NULL DEFAULT 'active' | `draft`/`active`/`superseded` |
| owner_user_id | UUID → app_user NULL | proposer (maker) |
| approved_by | UUID → app_user NULL | checker (CFO; maker ≠ checker, doc 05 §4) |
| version | INTEGER NOT NULL DEFAULT 1 | versioned + audited |

Resolution index: `(from_entity_id, to_entity_id, status, effective_from DESC)`; `product_scope` is matched JSONB (`{"family":[…]}` / `{"variant":[…]}` / `{}`=all). A policy change is a **governed, maker-checker, audited** action (doc 05 §4) — proposer ≠ approver, emits `pricing.rule.changed`-style audit + `transfer_price_policy.changed` (§5). The policy is **versioned**: a movement records the exact `policy.version` it used, so TP documentation is reproducible at any later date.

> **Why this lives on `price_rule` *and* `transfer_price_policy`.** Doc 02 §E `price_rule(surface='inter_entity', tp_method, tp_markup_pct, from_entity_id, to_entity_id)` is the **runtime ADLP-style resolution surface** (and is `field_layer_map`'d to the `inter_entity` layer); `transfer_price_policy` is the **policy/agreement record** carrying basis, OECD method, governance and the TP-documentation fields. The resolver (§2.3) reads the policy; the `inter_entity` `price_rule` row is the projected, layer-walled view of the active policy for a hop+variant. They are kept in lock-step by the policy-change consumer (a policy activation upserts the matching `inter_entity` `price_rule`).

### 2.2 The three methods (computed off the specific lot's landed cost)

All methods compute against the **specific `lot_batch` being moved** — never a weighted average (doc 02 §G, doc 14 §3 ASC 330 specific-identification). The lot's `landed_unit_cost` (= `unit_cost_usd × fx_rate + per-unit freight + per-unit duty`, doc 02 §G / doc 04 §FX) is the cost basis.

```
transferPrice(policy, lot, downstreamResalePrice?):
  base = lot.landed_unit_cost                                  // specific-identification, doc 02 §G
  tp = switch policy.method:
     'cost_plus'    -> base × (1 + policy.markup_pct/100)
     'resale_minus' -> require downstreamResalePrice
                       downstreamResalePrice × (1 − policy.resale_margin_pct/100)
     'fixed'        -> convertIfNeeded(policy.fixed_price@policy.fixed_currency → policy.tp_currency)
  tp = round(tp, currency=policy.tp_currency ?? seller.functional_currency,
                 boundary=policy.rounding_boundary)            // explicit RoundingPolicy, doc 14 §1.2
  if policy.arms_length_band: assertWithinBand(tp, base, policy.arms_length_band)   // governance check; log on breach
  return Money(tp, policy.tp_currency ?? seller.functional_currency)
```

- **`cost_plus`** — the default for Luxshare-sourced goods through the hub: hub buys at landed cost, sells to the market at landed cost + markup. `base = lot.landed_unit_cost`.
- **`resale_minus`** — the transfer price is the **downstream customer resale price** less an agreed distributor margin; used where the operating market's resale price is the reliable arm's-length anchor. `downstreamResalePrice` resolves from the operating entity's customer `price_rule` (doc 04 §Pricing) for that variant/market.
- **`fixed`** — an agreed unit price per the intercompany agreement, struck in `fixed_currency`, converted to `tp_currency` with a provenanced rate (doc 14 §1.4).

`convertIfNeeded` / any cross-currency step uses the provenanced `exchange_rate` register (doc 14 §1.4) and records `(rate, rate_type, source, as_of)` on the result — no implicit FX.

### 2.3 Resolution (inter-entity surface)

Extends doc 04 §Pricing inter-entity resolution; resolves on `(from_entity, to_entity, variant, asOf)`:

```
resolveTransferPrice(fromEntity, toEntity, variant, lot, asOf):
  policy = transfer_price_policy
            WHERE from_entity_id=fromEntity AND to_entity_id=toEntity
              AND status='active'
              AND effective_from <= asOf AND (effective_to IS NULL OR effective_to > asOf)
              AND productScopeMatches(product_scope, variant)
            ORDER BY scopeSpecificity desc, version desc
            LIMIT 1
  if none: raise TransferPricePolicyNotFound(fromEntity, toEntity, variant)
  resale = (policy.method=='resale_minus')
             ? resolvePrice(variant, channel=null, market=toEntity.market, toEntity.currency, toEntity, qty, asOf).exVat
             : null
  tp = transferPrice(policy, lot, resale)
  return TransferPriceResolution(policyId=policy.id, version=policy.version,
                                 method=policy.method, basis='landed_cost',
                                 lotId=lot.id, lotLandedCost=lot.landed_unit_cost,
                                 transferPrice=tp)
```

### 2.4 TP documentation — `tp_document` (reproducibility)

For every intercompany movement we persist an **immutable, reproducible** TP-documentation record: *policy version + specific batch cost → recorded transfer price*. This is the artefact the auditor / tax authority sees; it re-derives from inputs by replay (doc 14 §5.1).

| column | type | notes |
|---|---|---|
| intercompany_link_id | UUID → intercompany_link | the movement documented |
| from_entity_id, to_entity_id | UUID → entity | the hop |
| product_variant_id | UUID → product_variant | |
| lot_batch_id | UUID → lot_batch NOT NULL | the **specific** lot moved (cost basis) |
| policy_id | UUID → transfer_price_policy NOT NULL | |
| policy_version | INTEGER NOT NULL | the version applied (reproducibility) |
| method | TEXT NOT NULL | `cost_plus`/`resale_minus`/`fixed` |
| documentation_method | TEXT NULL | OECD label |
| lot_landed_unit_cost | NUMERIC(18,4) NOT NULL | the cost basis snapshot |
| markup_or_margin_pct | NUMERIC(7,4) NULL | the % applied |
| resale_anchor_price | NUMERIC(18,4) NULL | for `resale_minus` |
| qty | INTEGER NOT NULL | units moved |
| transfer_unit_price | NUMERIC(18,4) NOT NULL | recorded TP per unit |
| tp_currency | CHAR(3) NOT NULL | |
| fx_rate_applied | NUMERIC(18,8) NULL | if a conversion was made |
| fx_rate_source | TEXT NULL | provenance (doc 14 §1.4) |
| computed_at | TIMESTAMPTZ NOT NULL | |
| reproducible_inputs | JSONB NOT NULL | full input snapshot to re-derive `transfer_unit_price` |

Append-only; never edited. Index(intercompany_link_id), (from_entity_id, to_entity_id, computed_at DESC), (lot_batch_id). **Reproducibility guarantee:** `transferPrice(policy@policy_version, lot@lot_landed_unit_cost, resale_anchor_price)` must re-derive `transfer_unit_price` exactly — asserted by a control (`evidence_query`, doc 14 §4) and the Acceptance block. `tp_document` is `field_layer_map`'d to the `inter_entity` layer (doc 05 §3).

---

## 3. The intercompany movement — paired legs

A movement realises **one hop** of the chain for a set of lots. It creates a **sell leg** (seller's `order type='intercompany'`) and a **buy leg** (buyer's `purchase_order type='intercompany'`), links them via `intercompany_link`, posts **two linked TigerBeetle transfers**, calls the tax engine for the buy-side import VAT/duty when cross-border, and tags everything for elimination.

### 3.1 Extends `intercompany_link` (doc 02 §I)

Doc 02 §I: `intercompany_link(sell_order_id, buy_po_id, status)`. Extended (additive):

| column | type | notes |
|---|---|---|
| sell_order_id | UUID → order | seller IC order (`type='intercompany'`) (existing) |
| buy_po_id | UUID → purchase_order | buyer IC PO (`type='intercompany'`) (existing) |
| status | TEXT NOT NULL | state machine §3.4 (existing, expanded) |
| from_entity_id, to_entity_id | UUID → entity | the hop |
| hop_seq | INTEGER | from `entity_topology_edge` |
| stock_transfer_id | UUID → stock_transfer NULL | the physical move (doc 02 §G; cross-entity transfer = intercompany) |
| transfer_price_total | NUMERIC(18,4) | Σ over lots of `transfer_unit_price × qty` |
| tp_currency | CHAR(3) | |
| fx_rate | NUMERIC(18,8) NULL | seller→buyer functional, if currencies differ |
| fx_basis | TEXT NULL | `spot`/`hedged` (which rate basis — audit; doc 02 §G semantics) |
| sell_tb_transfer_id | NUMERIC(39,0) NULL | the seller-ledger leg |
| buy_tb_transfer_id | NUMERIC(39,0) NULL | the buyer-ledger leg |
| fx_bridge_tb_transfer_id | NUMERIC(39,0) NULL | the FX_CLEARING bridge leg (cross-currency only) |
| elimination_group_id | UUID NULL | groups the paired legs for consolidation elimination (§7) |
| import_tax_status | TEXT NULL | `n/a`/`quoted`/`posted` (buy-side import VAT/duty, §6) |
| accounting_period_key | TEXT NULL | resolved at post (period-projection, doc 14 §2) |

Index(from_entity_id, to_entity_id, status), (elimination_group_id), (stock_transfer_id).

### 3.2 Movement orchestration (extends doc 04 §Intercompany)

```
moveStockBetweenEntities(fromEntity, toEntity, variant, qty, lots, actor):
  require authz(create, 'intercompany_link', scope={entity:[fromEntity,toEntity]})    // doc 05
  require all lots belong to fromEntity stock                                          // specific-ID, doc 02 §G
  link = intercompany_link(from=fromEntity, to=toEntity, status='draft',
                           hop_seq=topology(fromEntity,toEntity).hop_seq,
                           elimination_group_id=newGroup())
  sellOrder = order(type='intercompany', entity=fromEntity, sold_to=toEntity.party)    // IC sale
  buyPo     = purchase_order(type='intercompany', entity=toEntity, supplier=fromEntity.supplierProxy)
  total = 0 ; tpc = null
  for lot in lots:                                                                     // batch-specific, no averaging
     r  = resolveTransferPrice(fromEntity, toEntity, variant, lot, now)                // §2.3
     addLine(sellOrder, variant, lot, qty=lotQty(lot), unit=r.transferPrice)
     addLine(buyPo,     variant, lot, qty=lotQty(lot), unit=r.transferPrice)
     writeTpDocument(link, lot, r, qty=lotQty(lot))                                    // §2.4 reproducible doc
     total += r.transferPrice × lotQty(lot) ; tpc = r.transferPrice.currency
  link.transfer_price_total = total ; link.tp_currency = tpc
  link.fx_rate, link.fx_basis = resolveHopFx(fromEntity, toEntity, now, lots)          // §4 (hedge-aware)
  if topology(fromEntity,toEntity).is_cross_border:                                    // §6
     taxQuote = taxEngine.quote(buySideContext(toEntity, fromEntity, variant, lots, total, tpc))
     recordImportTax(buyPo, taxQuote) ; link.import_tax_status='quoted'
  else: link.import_tax_status='n/a'
  // physical stock move (in-transit; doc 02 §G / doc 04 §Stock ops) — cross-entity ⇒ this movement
  link.stock_transfer_id = createStockTransfer(fromEntity→toEntity, variant, qty, serials(lots))
  postIntercompanyLedger(link, sellOrder, buyPo)                                       // §3.3 (linked transfers)
  link.status = 'posted' ; link.accounting_period_key = periodKey(now, group_tz)       // doc 14 §2
  persist(link, sellOrder, buyPo) + outbox(intercompany.movement.posted)              // ONE transaction
```

The whole thing is **one DB transaction + one outbox row** (doc 03 §outbox); the ledger legs are deterministic from `event_id` (doc 04 §Ledger idempotency). A multi-hop replenishment (Luxshare→SG external PO, then SG→UK movement) is **N hops = N movements**, each its own `intercompany_link`; the external Luxshare PO at the root is an ordinary `purchase_order type='external'` (doc 02 §H) that lands the lots and their landed cost (doc 04 §FX) — the movements then carry those specific lots downstream.

### 3.3 Ledger postings — two linked transfers, FX_CLEARING bridge (extends doc 04 §Ledger)

Doc 04 §Ledger: `intercompany.movement.posted → two linked transfers across the two entities' currency ledgers, bridged via FX_CLEARING when currencies differ; flags.linked=true so both commit atomically.` Accounts: `IC:<entityA>:<entityB>` (intercompany clearing), `FX_CLEARING` (cross-currency bridge), `INV:<entity>` (inventory asset, specific-ID), `AP:<supplier>`/`AR:<company>` (here the counterparty is the *other group entity*).

```
postIntercompanyLedger(link, sellOrder, buyPo):
  sellCcy = fromEntity.functional_currency ; buyCcy = toEntity.functional_currency
  tpSell  = link.transfer_price_total (in tp_currency, converted to sellCcy if needed)
  // SELLER leg (fromEntity ledger): relieve inventory at SPECIFIC batch landed cost, book IC receivable at TP
  sellLeg = transfer(ledger=sellCcy,
                     DR IC:<from>:<to>  amount=tpSell,                          // intercompany receivable @ TP
                     CR INV:<from>      amount=Σ lot.landed_unit_cost×qty,       // relieve at landed cost (specific-ID)
                     CR IC_MARGIN:<from> amount=tpSell − Σ landed)              // intragroup margin (eliminated §7)
  if sellCcy == buyCcy:
     // SAME currency: buyer leg mirrors in the same ledger, flags.linked with sellLeg
     buyLeg = transfer(ledger=buyCcy, flags.linked=true (with sellLeg),
                       DR INV:<to>  amount=tpSell,                              // buyer capitalises inventory @ TP
                       CR IC:<to>:<from> amount=tpSell)                         // intercompany payable
  else:
     // CROSS-CURRENCY: bridge through FX_CLEARING; ALL legs flags.linked → atomic
     tpBuy = convert(tpSell, sellCcy→buyCcy, rate=link.fx_rate, basis=link.fx_basis)   // provenanced, doc 14 §1.4
     bridgeOut = transfer(ledger=sellCcy, flags.linked=true,
                          DR FX_CLEARING amount=tpSell, CR IC:<from>:<to> already booked → net)
     buyLeg    = transfer(ledger=buyCcy,  flags.linked=true,
                          DR INV:<to> amount=tpBuy, CR FX_CLEARING amount=tpBuy)
     // FX_CLEARING nets to a translation difference held centrally → CTA on consolidation (ASC 830, §7)
  link.sell_tb_transfer_id = sellLeg.id ; link.buy_tb_transfer_id = buyLeg.id
  if sellCcy != buyCcy: link.fx_bridge_tb_transfer_id = bridgeOut.id
  // flags.linked=true across the set ⇒ TigerBeetle commits ALL or NONE (atomic paired legs)
```

Invariants (asserted as controls, doc 14 §4): per currency `Σ debits == Σ credits`; the linked set commits atomically (no half-posted movement); the seller's inventory relief equals **Σ specific-lot landed cost** (not TP, not average); `IC:<from>:<to>` and `IC:<to>:<from>` are mirror accounts that **net to zero on elimination** (§7); `transfer.id` deterministic from `event_id + leg_index` (idempotent redelivery). Postings respect the period lock — a movement cannot post into a `locked` `accounting_period` (doc 14 §2.4).

### 3.4 Movement state machine

```
draft ──priced──> priced ──tax_quoted──> ready ──post(atomic legs)──> posted ──received──> completed
  │                  │ (domestic: skips tax_quoted)                       │
  └── cancelled ─────┴───────────────────────────────────────────────────┘ (pre-post only)
                                                                          posted ──reversed──> reversed
```

- `draft → priced`: transfer price resolved + `tp_document` written for every lot.
- `priced → ready`: cross-border → import VAT/duty quoted (§6); domestic → straight to `ready` (`import_tax_status='n/a'`).
- `ready → posted`: the linked TB transfers commit atomically; `intercompany.movement.posted` emitted; stock goes `in_transit` (doc 02 §G).
- `posted → completed`: buyer receives the `stock_transfer` (`transfer_in`, doc 04 §Stock ops); the lots' specific landed cost is now carried on the buyer entity (re-based at TP per buyer-side capitalisation; the lot retains its identity + cost lineage for genealogy and downstream specific-ID).
- `cancelled`: only pre-`posted`. `reversed`: a posted movement is reversed by a maker-checker reversing movement (new linked transfers, opposite sign; never an edit) — used if a movement was posted in error before receipt.

---

## 4. FX on the hop (hedge-aware) — extends doc 04 §FX

The hop FX (seller→buyer functional) defaults to **spot** (provenanced `exchange_rate`, `rate_type='spot'`, latest `as_of ≤ date`; doc 14 §1.4). Where the **buyer operating market hedges** the seller's currency exposure and a hedge is designated, the hop uses the hedge's `contracted_rate` and draws down its notional — exactly the lot-cost FX mechanism in doc 04 §FX, applied to the intercompany hop.

```
resolveHopFx(fromEntity, toEntity, asOf, lots):
  if fromEntity.functional_currency == toEntity.functional_currency: return (1.0, null)   // no FX
  pair = (fromEntity.functional_currency → toEntity.functional_currency)
  hedge = fx_hedge WHERE pair_from..pair_to = pair
                     AND entity_id = toEntity              // the hedging operating market
                     AND status='active'
                     AND valid_from <= asOf AND valid_to > asOf
                     AND (notional − notional_used) >= Σ lots exposure
                   ORDER BY valid_from DESC LIMIT 1
  if hedge:
     hedge.notional_used += Σ lots exposure ; emit fx.hedge.updated
     return (hedge.contracted_rate, 'hedged')             // audited fx_basis='hedged'
  else:
     rate = exchange_rate(pair, rate_type='spot', as_of<=asOf)   // provenanced
     return (rate.rate, 'spot')
```

Note the distinction (doc 04 §FX): a **lot's landed cost** USD→functional FX (`lot_batch.fx_rate`/`fx_basis`/`hedge_ref`) is fixed at goods-receipt against the *external* Luxshare purchase. The **hop FX** here is the seller-functional→buyer-functional rate for the *intercompany* leg. Both can be hedged; both are provenanced; both feed consolidated USD translation (§7). The `fx_hedge` register is administered under the dedicated **treasury** permission set and projects only to the `treasury` layer (doc 05 §4).

---

## 5. Events (extends doc 03 §Intercompany / FX)

Doc 03 §Intercompany/FX already registers `intercompany.movement.posted`, `fx.rate.set`, `fx.hedge.created/updated/closed`. Topic `conduit.ledger` (intercompany legs) / `conduit.purchasing` (buy leg) / `conduit.orders` (sell leg) per partition; envelope per doc 03 §1; `BACKWARD` compatible; idempotent on `event_id`.

### `intercompany.movement.posted` (payload — extends doc 03 entry)
key `intercompany_link_id` · partition by `intercompany_link_id`
```
{ intercompany_link_id, hop_seq,
  from_entity_id, to_entity_id, from_currency, to_currency, is_cross_border,
  sell_order_id, buy_po_id, stock_transfer_id,
  lines: [ { product_variant_id, lot_batch_id, qty,
             transfer_unit_price, tp_currency,
             policy_id, policy_version, method, lot_landed_unit_cost } ],   // reproducible inputs
  transfer_price_total, tp_currency, fx_rate, fx_basis,                     // hop FX (spot|hedged)
  sell_tb_transfer_id, buy_tb_transfer_id, fx_bridge_tb_transfer_id,
  elimination_group_id, is_intragroup,                                      // → consolidation elimination
  import_tax: { status, vat_amount?, duty_amount?, hs_code?, regime? },     // buy-side, from tax engine (§6)
  accounting_period_key, occurred_at }
```
→ **both ledgers** (linked transfers, §3.3) · **tax/customs engine** ack of posted import tax (§6) · **elimination tag** for consolidation (§7) · **TP-documentation** projection · audit. Layer note (doc 05 §3): the **external/Xero-facing** projection is `inter_entity`-stripped — `transfer_unit_price`, `lot_landed_unit_cost`, `policy_*` and margin never leave on a layer-filtered projection a principal lacks.

### New: `transfer_price_policy.changed`
key `policy_id` · `{ from_entity_id, to_entity_id, method, basis, markup_or_margin, product_scope, version, before, after, owner_user_id, approved_by }` → upsert the `inter_entity` `price_rule`, refresh `entity_topology_edge`, audit (maker-checker). Governed change (doc 05 §4).

### New: `consolidation.translated`
key `period_key` · `{ period_key, presentation_currency='USD', per_entity:[{entity_id, functional_ccy, rate_type, rate, source}], cta_amount, eliminations:[{elimination_group_id, ic_pair, eliminated_amount}] }` → consolidated reporting read-model, Auditability Center (doc 14 §6), treasury layer.

`fx.hedge.updated` is emitted on hop-FX notional draw-down (§4) — already in doc 03; carries `notional_used`.

---

## 6. Import VAT / duty — the tax-engine boundary (buy side)

This subsystem **does not determine** VAT or duty. On a **cross-border** hop (`entity_topology_edge.is_cross_border`), it assembles the buy-side context and **calls the tax/customs engine** (doc 10 §B — VAT place-of-supply, EU vs ROW import VAT, B2B reverse charge, US destination sales tax, CA GST/HST/PST, HS/commodity codes, Intrastat/EC sales lists; almost certainly an Avalara/TaxJar/Stripe Tax integration). It then **records** the engine's result against the buy leg as `landed_cost_component` rows (doc 02 §H, `type ∈ {duty, import_vat}`) and on the `intercompany_link.import_tax`. Duty is **capitalised into landed cost** (doc 02 §G `duty_alloc` / doc 04 §FX), so it flows into the buyer entity's specific-identification cost; recoverable **import VAT** posts to `VAT:<to_entity>` control (not capitalised); irrecoverable VAT capitalises.

### 6.1 `TaxQuote` contract (the boundary)
```
// REQUEST  conduit → tax/customs engine
TaxQuoteRequest {
  context: 'intercompany_import',
  ship_from: { entity_id, jurisdiction },            // seller (e.g. SG)
  ship_to:   { entity_id, jurisdiction, tax_regime },// buyer (e.g. GB) — destination regime
  incoterm: TEXT,                                     // who clears customs / customs value basis
  lines: [ { product_variant_id, hs_code,            // hs_code from product_variant (doc 02 §D)
             qty, customs_value, currency } ],        // customs value = transfer price (or agreed basis)
  movement_ref: intercompany_link_id
}
// RESPONSE engine → conduit
TaxQuoteResponse {
  lines: [ { product_variant_id, hs_code,
             duty_rate_pct, duty_amount,
             import_vat_rate_pct, import_vat_amount, import_vat_recoverable: BOOLEAN,
             regime, provider_ref } ],
  totals: { duty_amount, import_vat_amount, currency },
  determination_ref: TEXT,                            // provider audit ref (Intrastat/EC-sales lineage)
  engine: TEXT, engine_version: TEXT
}
```

### 6.2 Recording the result
```
recordImportTax(buyPo, q):
  for line in q.lines:
     landed_cost_component(po_id=buyPo.id, type='duty',
                           amount=line.duty_amount, currency=q.totals.currency,
                           allocation_basis='by_customs_value')                  // → lot.duty_alloc (doc 04 §FX)
     if line.import_vat_recoverable:
        // recoverable: posts to VAT control on receipt, NOT capitalised
        stageVatControl(buyPo, line.import_vat_amount)                           // DR VAT:<to>, CR IC/AP
     else:
        landed_cost_component(po_id=buyPo.id, type='import_vat', amount=line.import_vat_amount, ...)  // capitalise
  attach(q.determination_ref, q.engine, q.engine_version) to intercompany_link   // audit/lineage
```

Year-1 (UK ← Luxshare-UK, **domestic**, no border): `is_cross_border=false`, no duty leg; VAT on the purchase is ordinary input VAT / reverse-charge as the engine returns for a domestic supply — handled by the standard purchase path, not this import branch.

---

## 7. Elimination & consolidated USD reporting (ASC 830)

### 7.1 Elimination tagging
Every paired movement shares an `elimination_group_id` (§3.1) and books mirror intercompany accounts `IC:<from>:<to>` / `IC:<to>:<from>` plus an `IC_MARGIN:<from>` (intragroup margin). On **consolidation**, the group eliminates: the mirror IC balances net to zero, and **unrealised intragroup margin in inventory still held by the group** is eliminated (the buyer capitalised at TP > cost; until that inventory is sold externally, the markup is unrealised at group level). Realised portion (units the buyer has since sold to an external customer) stays.

```
eliminate(group, period):
  // 1. net mirror intercompany clearing to zero
  assert balance(IC:<from>:<to>) + balance(IC:<to>:<from>) == 0     // control: must tie
  eliminate both into a consolidation elimination entry
  // 2. unrealised intragroup margin in ending inventory
  endingIcInventoryUnits = unitsStillHeldByGroup(group)             // from serial/lot lineage, specific-ID
  unrealisedMargin = Σ (transfer_unit_price − lot.landed_unit_cost) × endingIcInventoryUnits
  eliminate unrealisedMargin from INV:<to> and IC_MARGIN:<from>     // restate inventory to original landed cost
  emit consolidation.translated(eliminations=[…])
```

Because costing is **strict specific-identification** (doc 02 §G, doc 14 §3), "units still held" and their exact original landed cost are known per serial/lot — the unrealised-margin elimination is exact, not estimated.

### 7.2 USD translation (ASC 830)
Group presentation currency = **USD** (doc 00, doc 02 §A). Each entity keeps its **functional currency**; on consolidation we translate to USD under **ASC 830**: P&L/flows at the **average** rate for the period, assets/liabilities at the **period-close** rate, with the **CTA** (cumulative translation adjustment) to equity. Hedged exposures translate at the `fx_hedge.contracted_rate` (the hedge register, doc 02 §A); the FX_CLEARING bridge balance (§3.3) resolves into the CTA.

```
consolidate(period, presentation='USD'):
  for entity in group:
     fcl = trialBalance(entity, period)                              // in functional currency
     for account in fcl:
        rate = isFlow(account) ? exchange_rate(entity.func→USD, 'average', period)
                               : exchange_rate(entity.func→USD, 'closing', period)   // provenanced, doc 14 §1.4
        rate = hedgedOverride(entity, account, rate)                 // fx_hedge.contracted_rate where designated
        usd[account] += convert(fcl[account], rate)                  // provenanced conversion
     cta += translationDifference(entity, period)                    // ASC 830 → equity
  applyEliminations(period)                                          // §7.1
  emit consolidation.translated(per_entity rates, cta, eliminations)
```

Every conversion is provenanced (rate + type + source + as_of; doc 14 §1.4); the consolidated figures and CTA re-derive by replay (doc 14 §5.1 lineage). Consolidated reporting + the hedge register are **treasury layer** (doc 05 §4); the `auditor` role sees them read-only.

---

## 8. REST contracts (extends doc 06)

Base `/api/v1`; Keycloak bearer; authorisation per doc 05; money as `{amount,currency}`; layer-projected (inter_entity/treasury rows absent for principals lacking the layer). Standard errors per doc 06.

```
## Intercompany — transfer-price policies (inter_entity layer; maker-checker)
GET    /intercompany/policies?from_entity_id=&to_entity_id=&variant=&status=   → [TransferPricePolicy]  (inter_entity layer)
POST   /intercompany/policies   { from_entity_id, to_entity_id, method: cost_plus|resale_minus|fixed,
                                  markup_pct?|resale_margin_pct?|fixed_price?+fixed_currency?, basis,
                                  tp_currency?, documentation_method?, arms_length_band?, product_scope,
                                  effective_from, effective_to? }
                                  → TransferPricePolicy (status=draft)
POST   /intercompany/policies/{id}/approve   { }   → TransferPricePolicy (status=active; CFO only, maker≠checker;
                                                     upserts inter_entity price_rule; emits transfer_price_policy.changed)
GET    /intercompany/transfer-price/preview  ?from_entity_id=&to_entity_id=&variant=&lot_batch_id=
                                  → { method, lot_landed_unit_cost, transfer_unit_price, tp_currency, policy_version }  (inter_entity)

## Intercompany — movements
GET    /intercompany/movements?from_entity_id=&to_entity_id=&status=&period=   → [IntercompanyLink]  (inter_entity layer)
POST   /intercompany/movements  { from_entity_id, to_entity_id, variant, qty, lot_batch_ids:[…] }
                                  → IntercompanyLink (status progresses draft→priced→ready→posted;
                                                      202 if tax quote pending; 422 if no active policy / lots not owned)
GET    /intercompany/movements/{id}   → IntercompanyLink (legs, tb_transfer_ids, import_tax, tp_documents[], elimination_group)
POST   /intercompany/movements/{id}/post      → IntercompanyLink   (commits the linked TB legs atomically; emits intercompany.movement.posted)
POST   /intercompany/movements/{id}/receive   { stock_transfer_id }   → IntercompanyLink (status=completed; buyer transfer_in)
POST   /intercompany/movements/{id}/reverse   { reason }   → IntercompanyLink (maker≠checker; reversing legs)
GET    /intercompany/movements/{id}/tp-document   → [TpDocument]   (reproducible: policy_version + lot cost → TP; inter_entity)

## Topology (admin/config — the "switch is config" surface)
GET    /intercompany/topology?operating_entity_id=   → { chain: [Hop{from,to,hop_seq,currencies,is_cross_border}] }
GET    /intercompany/topology/edges                  → [EntityTopologyEdge]   (the derived projection)

## Consolidation & translation (treasury layer)
GET    /consolidation/translate?period=&presentation=USD   → { per_entity_rates[], cta, totals }   (treasury layer; provenanced)
GET    /consolidation/eliminations?period=                 → [{ elimination_group_id, ic_pair, mirror_balance, unrealised_margin, eliminated_amount }]
GET    /consolidation/intercompany-balances?period=        → [{ from_entity, to_entity, ic_clearing_balance }]   (must net to 0 — tie control)
```

Treasury-hedge admin endpoints are in doc 06 §Treasury (`/treasury/hedges`, `/treasury/consolidated`) — unchanged; the hop-FX draw-down (§4) updates `fx_hedge.notional_used` server-side on post.

---

## 9. Permissions & data-layer mapping (extends doc 05)

| object_type | sections | layers (view/edit) | who (seed roles, doc 05 §4) |
|---|---|---|---|
| `transfer_price_policy` | `inter_entity_pricing` | `inter_entity` (view); edit gated, maker-checker | `finance` view; **CFO** approve; `tax_specialist` view; `auditor` view-only |
| `intercompany_link` | — | `inter_entity` (transfer price, margin, lot cost); `volume` (qty/units) | `finance` create/view; `fulfilment_agent` `volume` only (sees the physical move, not TP) |
| `tp_document` | `inter_entity_pricing` | `inter_entity` (view) | `finance`/`tax_specialist`/`auditor` view; never edited (append-only) |
| consolidation / translation / CTA | `consolidated_reporting` | `treasury` (view) | **Treasury**, `finance`, `auditor` (read) |
| `fx_hedge` (hop FX draw-down) | — | `treasury` (view/edit) | **Treasury** admin only (doc 05 §4) |

`field_layer_map` additions (drive doc 05 §3 projection): `transfer_price_policy.markup_pct|resale_margin_pct|fixed_price|method`, `intercompany_link.transfer_price_total|fx_rate`, `tp_document.*` → **`inter_entity`**; `entity_topology_edge.is_intragroup`, all `consolidation.*`/CTA/translation rates → **`treasury`**; `intercompany_link.qty`/`stock_transfer_id` → **`volume`**. So a `fulfilment_agent` moving stock between entities sees the **physical** movement (qty, locations, serials) but **not** the transfer price; only `inter_entity`-layer principals see the priced legs; only `treasury` principals see consolidated/CTA figures. The `inter_entity` wall on the projected `price_rule(surface='inter_entity')` (doc 05 §3 pricing-wall example) is unchanged — Deal Desk still cannot see any of this.

**Segregation of duties (maker-checker, doc 05 §4 / doc 14 §4):** `transfer_price_policy` activation, movement `reverse`, and prior-period intercompany adjustments are maker-checker (proposer ≠ approver; CFO approves policy). FX-rate / hedge entry is Treasury maker-checker per doc 05. Posting into a `locked` `accounting_period` is rejected at the ledger boundary regardless of role (doc 14 §2.4). All of the above are audited (`audit_log` + immutable events + TB; doc 05 §5): transfer-price derivation, policy change, movement post/reverse, import-tax determination ref, FX designation, consolidation run.

---

## 10. Controls (extends doc 14 §4/§5 — ICFR)

Registered `control` rows with `evidence_query` (re-performable, doc 14 §6):

| control | assertion | type | evidence (re-perform) |
|---|---|---|---|
| TP reproducibility | valuation, accuracy | detective | re-derive `tp_document.transfer_unit_price` from `policy@version + lot.landed_unit_cost (+resale anchor)`; must match to the unit |
| IC clearing ties to zero | completeness | detective | `Σ IC:<a>:<b> + IC:<b>:<a> == 0` per pair per period (§7.1) |
| Paired-leg atomicity | existence | preventive | no `intercompany_link` in `posted` with a missing/half-posted TB leg; `Σdebits==Σcredits` per currency |
| Inventory relieved at landed cost | valuation | detective | seller `INV` credit on a movement == `Σ specific-lot landed cost` (not TP, not average) |
| Unrealised-margin elimination | valuation, presentation | detective | recompute `(TP − landed)×units-still-held` from serial/lot lineage; equals consolidation elimination |
| FX provenance on hop & translation | valuation | detective | every hop/translation conversion carries a resolvable `(rate,type,source,as_of)`; hedged legs draw `fx_hedge.notional_used` ≤ notional |
| Import-tax recorded cross-border | completeness, cutoff | detective | every `is_cross_border` posted movement has a `landed_cost_component(duty)` and an import-VAT disposition + `determination_ref` |

These run continuously/at close, write `control_run` rows, and surface in the Auditability Center (doc 14 §6 controls register, lineage explorer, reconciliation dashboard).

---

## Acceptance

A subsystem implementation is **done** when:

1. **Topology is config, not code.** Year-1 = a single hop **UK ← Luxshare-UK** with the UK entity raising the external (USD) PO; adding the **Singapore procurement hub** entity + `transfer_price_policy(SG→UK)` and pointing `UK.procurement_parent_id` at SG switches to the multi-tier chain **with no migration and no code branch** — `procurementChain` yields the new hops and movements flow through them. The multi-tier Singapore hub is demonstrably configuration.
2. **Transfer price is method-correct and batch-specific.** `cost_plus`, `resale_minus` and `fixed` each compute against the **specific `lot_batch.landed_unit_cost`** (never a weighted average); two lots of the same SKU with different landed costs produce two different transfer prices on the same policy.
3. **TP documentation is reproducible.** Every movement writes an immutable `tp_document`; re-deriving `transfer_unit_price` from `policy@policy_version + lot_landed_unit_cost (+ resale anchor)` reproduces the recorded price exactly (the TP-reproducibility control passes).
4. **Paired legs post atomically.** A cross-entity move produces **two linked TigerBeetle transfers** across the two entities' currency ledgers with `flags.linked=true` (and an `FX_CLEARING` bridge leg when currencies differ); the set commits all-or-none, `Σ debits == Σ credits` per currency, the seller's inventory is relieved at **specific-lot landed cost**, and redelivery of `intercompany.movement.posted` is a no-op (deterministic transfer ids).
5. **Hedge-designated hop FX.** When the buying market has a designated active hedge covering the hop currency pair, the hop uses the hedge's `contracted_rate` (audited `fx_basis='hedged'`) and draws down `notional_used`; otherwise provenanced spot (`fx_basis='spot'`).
6. **Import VAT/duty via the tax engine.** A cross-border hop calls the tax/customs engine with HS code + destination regime + customs value, records duty into landed cost and import VAT to the VAT control (or capitalises if irrecoverable), and stores the engine's `determination_ref`; year-1 domestic UK ← Luxshare-UK has no duty leg. The boundary is the `TaxQuote` contract — this subsystem records, it does not determine.
7. **Elimination & consolidation.** Mirror `IC` balances net to zero (tie control); unrealised intragroup margin in ending inventory eliminates exactly from serial/lot lineage; the group consolidates to **USD under ASC 830** (flows at average, balances at closing, hedged at contracted rate, CTA to equity), and every consolidated figure + the CTA re-derive by replay with provenanced rates.
8. **Access wall holds.** Transfer prices, lot costs and policy details project only to the `inter_entity` layer; consolidated/CTA/translation figures only to `treasury`; a `fulfilment_agent` sees the physical move but not the price; Deal Desk sees none of it. Policy activation and movement reversal are maker-checker; no posting enters a `locked` period.

> Supports **M12** (doc 07): intercompany + transfer pricing + tax/customs + treasury hedges.
