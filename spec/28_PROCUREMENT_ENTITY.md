# 28 — The Procurement Entity & the Central Price Catalogue (M-Procurement)

**Requested 2026-06-12 (CEO, verbatim intent):** a separate layer for a **central price catalogue** — the
price at which operating entities purchase products internally from our **Singapore procurement entity**.
The procurement entity **sets the price per market**, selling at a markup so profit thins in operating
markets and **consolidates at group level**. Every customer order and PO must carry **very strong matched
journals** tracing the origin PO to the matched order across entities, building the **COGS figure for the
operating entity's report**. The whole structure is **invisible** to everyone except admins and very select
procurement-entity employees.

**Standard model (the terms of art):** this is the *principal / limited-risk-distributor* (LRD) structure —
the procurement entity is the **principal** (owns goods, inventory risk, and the residual margin); operating
entities are **LRDs** earning a routine distribution return. Title passes **flash** at customer dispatch:
CM → principal (landed cost) → operating entity (transfer price) → customer (invoice price), with the middle
hop existing only in the ledgers, never in the warehouse.

## 1. What already exists (M12 — build on, don't duplicate)

| Piece | Where | Status |
|---|---|---|
| `entity.entity_type` + `procurement_parent_id` + `group_parent_id` | V1_0_2 | topology is config ✓ |
| `transfer_price_policy` (cost_plus / resale_minus / fixed; maker-checker; OECD label; arm's-length band) | V1_0_25 | the FORMULA layer ✓ |
| `intercompany_link` (paired sell-order/buy-PO, twin TB transfers, FX bridge, elimination groups) | V1_0_25 | the hop record ✓ |
| `IntercompanyService.move` (paired ledger legs, import-tax quote, TP doc) | M12 | stock-move hops ✓ |
| `inter_entity` **data layer** + FieldLayerMap walling | M2/doc 05 | the wall exists ✓ |
| GL mirror (`gl_entry` via Journal), consolidation_run (ASC-830), period close | M13b | group view ✓ |
| Recognition: COGS at **specific batch landed cost** per dispatch | M-Rev | the hook point |

## 2. What M-Procurement adds

### 2.1 The central price catalogue (`transfer_price_list`) — slice 1
A browsable, governed **price list** (not a formula): the principal *sets a number* per (variant × market).

- `transfer_price_list` (header): procurement_entity_id, market_id, currency, status
  draft→active→superseded (append-only versions, **maker ≠ checker**, both principal-side users),
  effective window. `transfer_price_list_line`: variant → unit price (Money semantics, NUMERIC).
- **Resolution precedence** (one resolver, used by every IC pricing site):
  1. active catalogue line for (principal, market, variant, as-of)
  2. `transfer_price_policy` formula (cost_plus / resale_minus / fixed)
  3. no price → the movement/recognition **fails closed** (no silent landed-cost fallback — an unpriced
     hop is a governance error, not a default).
- The markup is a **decision recorded in the catalogue**, auditable inside the wall: who set it, when,
  superseding what. Arm's-length band check from the policy still applies (warn/block per band config).

### 2.2 Flash-title matched journals (`ic_match`) — slice 2
At **customer dispatch** (the ASC-606 recognition moment) of any order whose selling entity has a
`procurement_parent_id`:

1. Operating entity books revenue (customer price) and **COGS at the TRANSFER price** (its true cost as an
   LRD) — not landed cost.
2. The principal books an **IC sale** at transfer price and **its** COGS at the specific batch landed cost.
3. One `ic_match` row binds the whole chain: dispatch → order line → `intercompany_link` (the IC pair) →
   origin batches (`lot_batch`, hence the physical PO/GRN/CM genealogy) → the four journal legs' TB ids.
   `UNIQUE(dispatch_id)`; transfer ids **deterministic from the dispatch event** (+leg) — redelivery is a no-op.
4. Group margin conserves to the penny: `(customer − landed) = (customer − transfer)_operating +
   (transfer − landed)_principal` — a ScalaCheck property and a runtime control.

**Controls** (re-performable, doc 19): `CTRL-IC-MATCH` — every recognized dispatch under a procurement
parent has exactly one complete match chain, and per elimination group Σ(sell legs) = Σ(buy legs).
`CTRL-IC-CATALOGUE` — no active market lacks a price for any variant it sold in the window.

### 2.3 The wall — slice 3 (not optional; part of the core)
- Everything in 2.1/2.2 rides the existing **`inter_entity` data layer**: catalogue, match rows, the
  principal's margin, even the *existence* of the markup. FieldLayerMap (Scala, the source of truth) gains
  the new objects' field→layer entries.
- New preset role **`procurement`**: view/create on the catalogue + match objects, scoped to the principal
  entity. Only `admin` and `procurement` hold the layer for these objects.
- The operating entity's P&L shows **COGS as a number** — same shape as today, no provenance fields. An
  operating-market finance user sees their report; they cannot see *how* COGS was constructed, that a
  catalogue exists, or any principal-side margin. API: non-holders get 403 on `/api/v1/procurement/*` and
  layer-projected absence (never zeros) anywhere a walled field would appear.
- Desk: a Procurement tab rendered **only** when the principal grants are present (added to spec/27 as a
  gated screen; build follows slice 3).

### 2.4 The gated entity-structure view (added same day)
`GET /api/v1/group/structure` — one endpoint, two truths. `view:entity_structure` (admin, ceo, finance,
auditor, procurement) gates the org chart at all; the `inter_entity` layer decides WHICH chart: without it,
procurement entities and `procurement_parent` edges are ABSENT from the payload (rows filtered, field
removed — never nulled). The desk's Group panel renders whatever the API returns, so the same screen is
safe for every role.

## 3. Acceptance (test-first)
- Catalogue: maker proposes, same-maker activate fails, checker activates; new version supersedes
  (append-only); resolution picks catalogue over policy; unpriced hop fails closed.
- Flash title: dispatch of a procured-variant order books operating COGS at transfer price, principal IC
  sale + landed COGS, one ic_match with full origin genealogy; group margin conserves; redelivery no-op.
- The wall: an operating-market finance principal reading P&L sees COGS only; `/procurement/*` 403s;
  serialized payloads contain NO transfer-price fields (absent, not null); admin + procurement role see all.
- Consolidation: elimination groups net to zero in the group view (extends the existing gl_vs_tb control).

## 4. Out of scope (later)
Customs/VAT interplay on the flash hop beyond the existing TaxEngine import-tax quote; multi-hop chains
(>1 intermediate); desk UI polish (gated tab lands with slice 3); Singapore entity seeding is **config**
(orgconfig/terraform-time data), never a migration.
