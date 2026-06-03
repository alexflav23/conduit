# 09 — Returns / RMA (first-class)

Returns are a **first-class domain**, not a refund flag on an order. A return is the controlled, audited path by which goods and money flow *back* across the boundary that `order` → `dispatch.delivered` → `order.invoiced` flowed *forward* — and it must reconcile to the ledger and the serial genealogy with the same rigour. This document deep-dives the `rma`/`return` stub (doc 02 §F) into the full subsystem supporting build milestone **M9b** (doc 07).

Design stance (consistent with the pack): **money reverses by reversing transfers at the unit's specific batch landed cost — never by overwriting**; **serials never silently re-enter sellable stock**; **commission claws via the two-phase lifecycle**; **a warranty replacement issues a new order and starts a fresh warranty clock**; **approval is maker-checker** (requester ≠ approver). The forward path (doc 04 §Ledger/§Orders/§Commission/§Serial/§Warranty) is the mirror this document reverses; every algorithm here references its forward twin.

Conventions per doc 00: every table has `id UUID PK DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`, optional `deleted_at TIMESTAMPTZ` (transactional records are never hard-deleted). Money = `NUMERIC(18,4)` + `CHAR(3)` currency. Scoping columns `entity_id`/`market_id`/`channel_id` per doc 00. Common columns omitted below. `→ X` = FK to `X.id`.

---

## A. Return types (the taxonomy)

A return carries a `type` that selects per-type rules (§G) — refund basis, disposition default, whether a replacement order is issued, whether warranty resets, commission treatment, and who may approve. The stub's six types (doc 02 §F) are the canonical set; new types are configuration in `return_type_rule` (§B), not code.

| `type` | Meaning | Refund default | Replacement | Disposition default | Commission | Warranty effect |
|---|---|---|---|---|---|---|
| `full_unit` | A delivered serialised unit returned whole | full line value (variable consideration) | none (customer refunded) | `assess` → restock / refurbish / scrap | claw the line's accrued/posted commission | provision **voided** if not yet activated; if activated, closed on scrap |
| `part_only` | A component/accessory returned (cable, faceplate, mounting kit), not the whole charger | component value only | optional component re-ship | `assess` (often `scrap` for low-value parts) | **no claw** (the sale stands) unless component sold standalone | unaffected |
| `multi_unit` | Several serials / a tranche returned together (wholesaler over-ship, project cancel) | sum of returned lines | none / partial | per-serial disposition (mixed allowed) | claw per returned serial's line | per-serial as `full_unit` |
| `dead_on_arrival` (`doa`) | Unit faulty on receipt, before/at activation | full value (or replacement, customer's choice) | **issued by default** (warranty-style new order) | `return_to_supplier` (Luxshare claim) or `scrap` | claw original; **no new commission** on a goodwill/warranty replacement (§G) | original voided; replacement starts **fresh** clock at its activation |
| `warranty_replacement` | In-warranty failure, unit replaced under the provision | **no customer refund** (warranty, not a sale reversal) | **issued by default**, zero-priced replacement order | `return_to_supplier` / `refurbish` / `scrap` | **no claw, no new commission** (not a commercial sale) | draws down `warranty_provision`; replacement starts a **fresh** warranty (doc 04 §Warranty) |
| `goodwill` | Discretionary return outside policy/warranty (relationship) | partial/full per approval | optional | `assess` | claw or retain per approval memo | per approval |

`type` is immutable once the RMA leaves `raised` (a different decision = a new RMA). `scope` (`whole_order`/`line`/`serial`/`component`) is orthogonal and recorded per line (§B `rma_line`).

---

## B. Data model (extends doc 02 §F)

The doc 02 §F `rma` stub is **extended**, not redefined: the columns there (`order_id`, `type`, `scope`, `serials`, `component_ref`, `reason_code`, `disposition`, `refund_amount`, `replacement_order_id`, `status`, `tb_transfer_id`, `approved_by`) remain; the columns below are **added** to the same table, and `rma_line` / `return_disposition` / `return_type_rule` / `reason_code` are new tables in §F style (Orders & Fulfilment).

### `rma` — return header (extends doc 02 §F)
Added/clarified columns (existing stub columns retained):
| column | type | constraints | notes |
|---|---|---|---|
| rma_no | TEXT | UNIQUE NOT NULL | human ref; scheme `RMA-<entity>-<YYYYMM>-<seq>` (data, like `order_no`/`batch_no`) |
| order_id | UUID | → order NOT NULL | the originating sale (forward path) |
| entity_id | UUID | → entity NOT NULL | selling entity (the one that reverses) |
| sold_to_party_id | UUID | → party NOT NULL | mirrors `order.sold_to_party_id` (stats attribute here) |
| bill_to_party_id | UUID | → party NOT NULL | mirrors `order.bill_to_party_id` — **credit note / refund posts here** |
| channel_id, market_id | UUID | | scope (copied from order; immutable) |
| type | TEXT | NOT NULL | §A taxonomy |
| scope | TEXT | NOT NULL | `whole_order`/`line`/`serial`/`component` |
| reason_code | TEXT | → reason_code.code NOT NULL | governed list (§B `reason_code`) |
| refund_currency | CHAR(3) | NOT NULL | = order `txn_currency` (no implicit cross-currency) |
| refund_amount | NUMERIC(18,4) | NULL | resolved at approval (variable consideration, §H); NULL until then |
| restocking_fee | NUMERIC(18,4) | NOT NULL DEFAULT 0 | deducted from refund (per-type policy) |
| replacement_order_id | UUID | → order NULL | set when a replacement order is issued (§E) |
| credit_note_id | UUID | → credit_note NULL | the issued credit note (§I) |
| status | TEXT | NOT NULL DEFAULT 'raised' | state machine (§C) |
| requested_by | UUID | → app_user NOT NULL | **maker** (CS agent / desk) — cannot approve |
| approved_by | UUID | → app_user NULL | **checker** (different person; permission-gated, §J) |
| approval_memo_ref | TEXT | NULL | immutable memo on `goodwill`/over-threshold approval |
| assessed_by | UUID | → app_user NULL | who inspected/dispositioned on receipt |
| received_at | TIMESTAMPTZ | NULL | goods physically back |
| closed_at | TIMESTAMPTZ | NULL | terminal |
| tb_reversal_group | NUMERIC(39,0) | NULL | linked-transfer group id for the reversal set (§H) |
| attributes | JSONB | NOT NULL DEFAULT '{}' | governed (doc 02 §M) — workflow/segmentation only, **never money** |

Indexes: `rma_no`, `(order_id)`, `(status)`, `(bill_to_party_id, created_at DESC)`, `(channel_id, market_id, created_at)`, `(type)`, GIN(`attributes`). Soft-delete only pre-`approved`; once money or stock has moved the record is immutable (corrections are new RMAs / reversing movements, never edits).

> **Note on `serials`/`component_ref`/`disposition` on the header (doc 02 §F):** these stay for the simple single-line case but are **superseded by `rma_line`** for `multi_unit` and mixed-disposition returns. The header `disposition` becomes a denormalised rollup (`mixed` when lines differ); the authoritative per-unit decision lives on `rma_line`. (Same graduation pattern as `order` header → `order_line`.)

### `rma_line` — per returned item (new; §F style)
One row per returned serial / component / line so multi-unit and mixed dispositions are first-class.
| column | type | constraints | notes |
|---|---|---|---|
| rma_id | UUID | → rma NOT NULL | |
| order_line_id | UUID | → order_line NOT NULL | the forward line being reversed |
| tranche_id | UUID | → delivery_tranche NULL | which drop (scheduled lines reverse per tranche) |
| product_variant_id | UUID | → product_variant NOT NULL | |
| serial_unit_id | UUID | → serial_unit NULL | serialised returns (1 row per serial) |
| component_ref | TEXT | NULL | part-only returns (non-serialised component id/SKU) |
| qty | INTEGER | NOT NULL DEFAULT 1 | 1 for serialised; >1 for non-serialised components |
| reason_code | TEXT | → reason_code.code | may differ per line (mixed return) |
| condition_grade | TEXT | NULL | set at assess: `a`/`b`/`c`/`scrap` (drives disposition) |
| disposition | TEXT | NULL | `restock`/`refurbish`/`scrap`/`return_to_supplier` (set at disposition step) |
| lot_batch_id | UUID | → lot_batch NULL | resolved from the serial (specific-ID cost basis) |
| unit_landed_cost | NUMERIC(18,4) | NULL | snapshot of `lot_batch.landed_unit_cost` at delivery — the reversal basis |
| line_refund_amount | NUMERIC(18,4) | NULL | this line's share of the refund (largest-remainder split, §H) |
| commission_entry_id | UUID | → commission_entry NULL | the forward entry to claw |
| restock_location_id | UUID | → location NULL | where restock/refurbish lands |
| status | TEXT | NOT NULL DEFAULT 'expected' | `expected`/`received`/`assessed`/`dispositioned`/`reversed` |
UNIQUE(rma_id, serial_unit_id) WHERE serial_unit_id IS NOT NULL. Index `(rma_id)`, `(serial_unit_id)`, `(order_line_id)`.

### `return_type_rule` — per-type policy (new; governed config)
Makes §A/§G data-driven (no code change to tune policy).
| column | type | notes |
|---|---|---|
| type | TEXT NOT NULL | one of §A (extensible) |
| entity_id | UUID → entity NULL | per-entity override (NULL = group default) |
| market_id | UUID → market NULL | per-market override |
| refund_basis | TEXT NOT NULL | `full`/`line_value`/`component_value`/`none`/`per_approval` |
| restocking_fee_pct | NUMERIC(5,2) NOT NULL DEFAULT 0 | |
| return_window_days | INTEGER NULL | NULL = no window (warranty/DOA) |
| issues_replacement | BOOLEAN NOT NULL DEFAULT false | DOA/warranty default true |
| replacement_priced | BOOLEAN NOT NULL DEFAULT false | false = zero-priced (warranty/DOA), true = chargeable |
| default_disposition | TEXT NOT NULL | `assess`/`restock`/`refurbish`/`scrap`/`return_to_supplier` |
| commission_treatment | TEXT NOT NULL | `claw`/`retain`/`per_approval` |
| warranty_effect | TEXT NOT NULL | `void`/`none`/`draw_down`/`fresh_on_replacement` |
| requires_memo | BOOLEAN NOT NULL DEFAULT false | `goodwill`/over-threshold |
| approval_threshold | NUMERIC(18,4) NULL | refund above this needs an elevated approver (§J) |
| approval_currency | CHAR(3) NULL | |
| version | INTEGER NOT NULL | versioned + audited (a SOX-relevant policy) |
| effective_from | TIMESTAMPTZ NOT NULL | |
| effective_to | TIMESTAMPTZ NULL | |
UNIQUE(type, entity_id, market_id, version, effective_from). Resolution = most specific (entity>market>group) valid at `rma.created_at`. Changes are governed/audited (maker-checker; doc 05 §4, doc 14 §4).

### `reason_code` — governed reason list (new)
`code TEXT PK` (`faulty`/`doa`/`not_as_described`/`damaged_in_transit`/`changed_mind`/`wrong_item`/`over_shipment`/`warranty_fault`/`goodwill`/`recall`), `name TEXT`, `category TEXT` (`fault`/`customer`/`logistics`/`commercial`), `default_disposition TEXT NULL`, `counts_against_supplier BOOLEAN DEFAULT false` (faults → Luxshare claim / RTV), `status TEXT DEFAULT 'active'`. Reasons are runtime data (like channels/segments, doc 02 §A).

### `return_disposition` — the physical-routing record (new; immutable)
Append-only record of what physically happened to each returned unit/part — the bridge to `stock_movement` + `serial_unit.status` + `unit_lifecycle_event`. One per `rma_line` disposition action.
| column | type | notes |
|---|---|---|
| rma_line_id | UUID → rma_line NOT NULL | |
| serial_unit_id | UUID → serial_unit NULL | |
| disposition | TEXT NOT NULL | `restock`/`refurbish`/`scrap`/`return_to_supplier` |
| from_status | TEXT NOT NULL | serial status before (audit) |
| to_status | TEXT NOT NULL | serial status after |
| location_id | UUID → location NULL | restock/refurbish destination |
| stock_movement_id | UUID → stock_movement NULL | the signed movement posted |
| supplier_claim_ref | TEXT NULL | RTV / Luxshare warranty-claim ref |
| evidence_ref | JSONB NULL | photos/inspection notes |
| actor_user_id | UUID → app_user | who dispositioned |
| occurred_at | TIMESTAMPTZ NOT NULL | |
Append-only, immutable (doc 04 §Stock ops discipline). Index `(rma_line_id)`, `(serial_unit_id)`.

### `credit_note` — the money-reversal document (new; mirrors `order_invoice`)
A return that refunds issues a **credit note** against the original invoice — the AR-side reversal document (the mirror of `order_invoice`, doc 02 §F).
| column | type | notes |
|---|---|---|
| rma_id | UUID → rma NOT NULL | |
| order_id | UUID → order NOT NULL | |
| order_invoice_id | UUID → order_invoice NULL | the invoice being credited (per tranche) |
| credit_note_no | TEXT UNIQUE NOT NULL | scheme `CN-<entity>-<YYYYMM>-<seq>` |
| bill_to_party_id | UUID → party NOT NULL | credited party (= invoice bill-to) |
| issued_at | TIMESTAMPTZ NOT NULL | |
| total_ex_vat | NUMERIC(18,4) NOT NULL | |
| vat_total | NUMERIC(18,4) NOT NULL | reversed at the **original** `tax_regime`/rate |
| total_inc_vat | NUMERIC(18,4) NOT NULL | |
| tax_regime | TEXT → tax_regime.code | copied from the invoice line |
| refund_method | TEXT NOT NULL | `stripe_refund`/`credit_memo`/`bank` |
| stripe_refund_id | TEXT NULL | retail card refund |
| xero_credit_note_id | TEXT NULL | filled by Xero consumer |
| tb_transfer_id | NUMERIC(39,0) NULL | reversing AR transfer |
| status | TEXT NOT NULL DEFAULT 'issued' | `issued`/`refunded`/`applied` |

### Reuse (referenced, not redefined)
- **`order`, `order_line`, `delivery_tranche`, `order_invoice`** (doc 02 §F) — the forward path being reversed.
- **`serial_unit`, `unit_lifecycle_event`, `lot_batch`** (doc 02 §G) — serial status flow, genealogy, specific-ID cost basis.
- **`stock_item`, `stock_movement`** (doc 02 §G) — `stock_movement.type='return'` already exists; restock/refurb/scrap post signed movements through it.
- **`commission_entry`** (doc 02 §J) — the claw target.
- **`warranty_provision`, `warranty_claim`** (doc 02 §G) — `warranty_claim.rma_id` already FKs here.
- **TigerBeetle ledger model** (doc 04 §Ledger) — reversing transfers.
- **`audit_log`, `outbox_event`** (doc 02 §L).

---

## C. Lifecycle state machine

```
raised ──assess──> assessed ──approve──> approved ──[await goods]──> received
  │ (maker)          │ (inspect/grade)     │ (checker, maker≠checker)        │
  │                  │                      │                                 ▼
  └──cancel──────────┴──reject──────────────┴──> rejected            dispositioned
       (pre-money, no stock moved)                                           │
                                                          ┌──────────────────┤
                                                          ▼                  ▼
                                                   refunded / replaced ──> closed
```

States (`rma.status`):
- **`raised`** — maker (CS/desk) logs the request; type/scope/lines captured; nothing has moved. Cancellable.
- **`assessed`** — desk has reviewed eligibility (return window, reason validity, per-type rule); refund amount *previewed*. (For DOA/warranty, eligibility = warranty/claim check.)
- **`approved`** — **checker** (≠ requester, §J) authorises. Refund amount **fixed** (variable consideration estimate, §H). Replacement order **issued** here if the type requires it (§E) so the customer is not left waiting on goods movement. Commission **claw scheduled** (pending reversal armed, §H). Inbound RMA label/instructions emitted.
- **`received`** — goods physically back at the warehouse; `rma_line.status='received'`; serials flip to `returned` (§D). No sellable-stock change yet.
- **`dispositioned`** — each `rma_line` routed (restock/refurbish/scrap/RTV) — the **only** step that may return a serial toward sellable stock, and only via an audited path (§D).
- **`refunded`** / **`replaced`** — money reversed (credit note + reversing transfers) and/or replacement order issued; commission clawed.
- **`closed`** — terminal; all lines dispositioned, money + commission + stock reconciled. Immutable.
- **`rejected`** — not eligible (out of window, invalid reason, failed warranty check); terminal; no money/stock movement.

**Ordering invariant:** money never reverses before `approved`; sellable stock never increases before `dispositioned`; a replacement order is issued no earlier than `approved`. `received` may precede or follow `refunded` per type (warranty replacement often refunds-as-replacement at approval, then receives the dead unit later for RTV).

Transitions emit the matching `return.*` events (§K). The maker-checker gate (`raised`→`approved` requires a different principal holding `approve:rma`) is enforced server-side (§J), mirroring stock-adjustment maker-checker (doc 04 §Stock ops, doc 05 §4).

---

## D. Disposition routing — stock & serial effects

Disposition is the heart of "serials never silently re-enter sellable stock." Every routed unit posts an immutable `stock_movement` (doc 02 §G), writes a `return_disposition` row, transitions `serial_unit.status`, and appends a `unit_lifecycle_event`. A returned serial sits at `status='returned'` (non-sellable) from `received` until an **explicit, audited** disposition moves it on.

`serial_unit.status` flow for a return (extends the doc 02 §G enum — `returned` and `refurbished` already exist there; `scrapped` exists):

```
dispatched/activated ──received──> returned ──disposition──> {in_stock | refurbished→in_stock | scrapped | <RTV: removed>}
```

```
disposition(rma_line, choice, actor, location?, evidence?):     // runs in a DB txn; maker-checker already passed at approve
  require rma.status == 'received'                              // goods physically back
  require actor holds edit:rma:disposition
  serial = rma_line.serial_unit
  from   = serial.status                                        // 'returned'
  switch choice:

    'restock':                                                  // back to SELLABLE — gated, never silent
       require rma_line.condition_grade == 'a'                  // only A-grade may restock; B/C must refurbish
       stock_movement(type='return', qty=+1, location, ref=rma, reason=rma_line.reason_code)
       serial.status='in_stock'; serial.location_id=location; serial.order_line_id=NULL; serial.dispatch_id=NULL
       // unbind activation linkage only if NOT previously activated (see below)
    'refurbish':
       stock_movement(type='return', qty=+1, location=REFURB_LOC, ref=rma)   // lands in a NON-sellable refurb location
       serial.status='refurbished'                              // NOT 'in_stock' — a later audited 'found'/QA pass promotes to in_stock
    'scrap':
       stock_movement(type='write_off', qty=0-already-relieved, ref=rma, reason)  // no sellable add; value already off books at delivery
       serial.status='scrapped'
    'return_to_supplier':                                       // RTV / Luxshare claim
       stock_movement(type='transfer_out', qty=-1, ref=rma) ; supplier_claim_ref set
       serial.status='scrapped'                                 // off our books; AP claim tracked separately

  insert return_disposition(rma_line, serial, disposition=choice, from_status=from, to_status=serial.status,
                            location, stock_movement_id, supplier_claim_ref?, evidence, actor, now)
  insert unit_lifecycle_event(serial, event_type=mapToLifecycle(choice), ref_type='rma', ref_id=rma.id, actor, now)
  rma_line.status='dispositioned'; rma_line.disposition=choice; rma_line.restock_location_id=location
  emit return.restocked  (or serial.lifecycle for refurb/scrap/RTV)   // §K
  if all rma_lines dispositioned: advance rma → close path
```

Rules (invariants, tested in §Acceptance):
- **Only A-grade may `restock`.** B/C condition or any fault reason (`reason_code.category='fault'`) cannot route to sellable stock directly — they go `refurbish` (non-sellable refurb location) or `scrap`. This is the "never silently re-enter sellable stock" guard.
- **`refurbish` lands in a non-sellable refurb location**; promotion to `in_stock` is a separate audited `found`/QA pass (doc 04 §Stock ops `adjustment(kind='found')`), maker-checker — not part of the RMA itself.
- **`scrap`** posts no sellable add. The unit's inventory value was already relieved into `COS_CLEARING` at the original delivery (doc 04 §Ledger), so scrap is a stock-status change, not a second write-down — unless the unit was **restocked then later scrapped** (then the standard write-down at batch cost applies, doc 04 §Stock ops).
- **`return_to_supplier`** raises a supplier claim (`supplier_claim_ref`) for faulty units returnable to Luxshare; the AP/recovery side is tracked off the claim (intercompany/supplier flow, doc 04 §Intercompany), not modelled as sellable stock.
- **Genealogy:** every disposition appends a `unit_lifecycle_event` (`returned`→`rma`→`refurbished`/`scrapped`), so a serial's full history (manufactured → … → dispatched → activated → rma → scrapped) is always reconstructable (doc 02 §G, doc 04 §Serial). A serial that was `activated` and is returned keeps its activation genealogy; restock of a previously-activated unit is **disallowed** (an activated unit is field-used → must refurbish or scrap), preventing a used unit silently re-entering sellable stock and double-counting in sell-through (doc 04 §H6Q V2/V3 rule).

---

## E. Replacement-order issuance

DOA and warranty replacements (and optionally goodwill) issue a **new `order`** rather than reshipping inside the RMA — so the replacement unit gets its own allocation, dispatch, serial binding, delivery event, genealogy and (on activation) a **fresh warranty clock** (doc 04 §Warranty: clock starts at activation). This is the explicit ASC-606 distinction: a warranty replacement is **not** a new sale (no revenue, zero-priced), whereas a chargeable replacement is.

```
issueReplacement(rma, lines, actor):                  // at 'approved'; only if return_type_rule.issues_replacement
  rule = resolveReturnTypeRule(rma)
  repl = new order(
     type = orderTypeFor(rma),                         // mirrors original order.type
     entity_id = rma.entity_id, sold_to=rma.sold_to_party_id, bill_to=rma.bill_to_party_id,
     channel_id=rma.channel_id, market_id=rma.market_id,
     txn_currency=rma.refund_currency, payment_method='warranty' if not rule.replacement_priced else original,
     origin_rma_id = rma.id)                            // back-reference (new order column, see below)
  for l in lines:
     unit_price_ex_vat = rule.replacement_priced ? resolvePrice(...) : 0   // ZERO-priced for warranty/DOA
     add order_line(variant=l.product_variant_id, qty=l.qty, unit_price_ex_vat, discount=0)
  // zero-priced replacement: NO ADLP exception, NO commission accrual (computeCommission basis=0 → 0; §G)
  rma.replacement_order_id = repl.id
  placeOrder(repl)                                      // doc 04 §Orders — allocates, dispatches, delivers as normal
  emit return.replaced { rma_id, replacement_order_id, priced: rule.replacement_priced }
```

- **Fresh warranty:** the replacement unit is an ordinary new unit through fulfilment; on its `activation.recorded` (doc 04 §Serial) it opens its **own** `warranty_provision` with a new start date — the original unit's warranty does not transfer (doc 04 §Warranty). The original provision is voided/closed per §G.
- **No double-count:** the replacement order's revenue is **0** for warranty/DOA, so it does not inflate sell-in/coverage as a sale; sell-through still tracks its activation (the customer does get a working unit). A *priced* replacement (`goodwill` chargeable, or customer-choice upgrade) is a normal sale and commissions normally.
- **`order.origin_rma_id`** — a new nullable column on `order` (`UUID → rma NULL`) records that an order originated from an RMA (for genealogy/H6Q filtering and the audit chain). This is the only `order`-table change.

---

## F. Commission clawback (ties to the two-phase, doc 04 §Commission)

Commission reverses through the **same TigerBeetle two-phase lifecycle** as the forward accrual (doc 04 §Commission), never by editing the original entry. The forward states are `accrued → PENDING`, `posted → POST pending`, `cancelled/refunded → clawed → VOID pending / reversing transfer`. A return drives the third arm.

```
clawCommission(rma, line, rule):                        // at 'approved' (armed) → effected at 'refunded'/'replaced'
  if rule.commission_treatment == 'retain': return       // part_only (sale stands), or per-approval retain
  if rule.commission_treatment == 'per_approval' and not approvalSaysClaw(rma): return
  entry = line.commission_entry                           // the forward entry on order_line
  if entry is null: return                                // no commission was earned (e.g. zero-priced)
  switch entry.status:
    'pending':                                             // accrued but not yet posted (order not dispatched) — rare on a return
        VOID pending transfer (COMM_PAYABLE:<agent>)       // doc 04 §Ledger
        entry.status='clawed'
    'posted':                                              // earned — book a reversing transfer for the clawed portion
        clawAmount = proRataClaw(entry, line)              // full line claw, or pro-rata for partial/component
        reversing transfer DR COMM_PAYABLE:<agent> for clawAmount   // reverses the posted earning; never overwrites
        insert commission_entry(kind='claw', agent, scheme_id=entry.scheme_id,
                                commission_period_id=currentOpenPeriod(), order_id, order_line_id,
                                basis_amount = -line basis, rate_applied=entry.rate_applied,
                                amount = -clawAmount, currency, status='clawed', tb_transfer_id=reversal)
  emit commission.clawed { agent_id, order_id, line_id, amount: -clawAmount, currency }   // doc 03 Commission
```

- **Reversing, not reopening:** consistent with doc 04 §Commission "a posted entry is **not reopened**." The claw is a **new** `commission_entry(kind='claw')` booked in the **current open** `commission_period`, with a reversing transfer — the prior period stays as reported (clean close). The agent's companion app shows accrued → posted → clawed.
- **Period interaction (true-up):** if the return lands after the original period closed, the claw is a current-period adjustment exactly like the quarterly true-up delta (doc 04 §Commission true-up). If it lands in the same open period, it nets against the accrual before close.
- **Partial / component claw:** for `multi_unit` (subset returned) or `part_only` (component of a kit), `proRataClaw` claws only the returned line's share of the basis (gross-margin basis, doc 04 §Commission), using largest-remainder conservation (doc 14 §1.3) so Σ claws never exceed the original earning.
- **Zero-claw types:** `warranty_replacement` and `part_only` (default) **retain** commission — the original commercial sale stands; only the unit is being serviced. `return_type_rule.commission_treatment` governs this per type (data, not code).

---

## G. Per-type rules (resolution)

All per-type behaviour resolves through `return_type_rule` (§B) at `rma.created_at` (most-specific entity>market>group, like pricing/commission resolution in doc 04). The defaults seeded:

```
resolveReturnTypeRule(rma):
  candidates = return_type_rule WHERE type=rma.type AND effective valid at rma.created_at
               AND (entity_id=rma.entity_id OR entity_id IS NULL)
               AND (market_id=rma.market_id OR market_id IS NULL)
  return candidates.sortBy(specificity desc, version desc).head     // entity>market>group; highest version
```

| type | refund_basis | issues_replacement / priced | default_disposition | commission_treatment | warranty_effect | requires_memo |
|---|---|---|---|---|---|---|
| `full_unit` | `line_value` (− restocking_fee) | no | `assess` | `claw` | `void` (if pre-activation) / close on scrap | over `approval_threshold` |
| `part_only` | `component_value` | optional / priced | `assess` (often `scrap`) | `retain` | `none` | no |
| `multi_unit` | `line_value` per returned serial | no | per-line `assess` | `claw` (per returned serial) | per-serial as `full_unit` | over threshold |
| `doa` | `full` or replacement (customer choice) | **yes / unpriced** | `return_to_supplier` else `scrap` | `claw` (no new commission) | original `void`; **fresh on replacement** | no |
| `warranty_replacement` | `none` (no refund) | **yes / unpriced** | `return_to_supplier`/`refurbish`/`scrap` | `retain` (no claw, no new) | `draw_down` + **fresh on replacement** | no |
| `goodwill` | `per_approval` | optional / per_approval | `assess` | `per_approval` | `per_approval` | **yes (always)** |

- **`warranty_replacement` ties to the provision:** at approval, `onWarrantyClaim(serial, cost)` draws down the unit's `warranty_provision` (doc 04 §Warranty: `consumed_by_claims += cost`), where `cost` = replacement unit's batch landed cost + handling; `warranty_claim.rma_id` links the two (doc 02 §G). No credit note (no refund). The replacement order is zero-priced → no revenue, no commission. The dead unit dispositions to RTV/scrap.
- **`doa`:** functionally a warranty failure at t≈0. If the customer chooses refund, a credit note issues (§I); if replacement, a zero-priced replacement order issues (§E). Original commission claws either way (the sale did not stand). DOA reasons are typically `counts_against_supplier=true` → RTV/Luxshare claim.
- **`goodwill`:** always `requires_memo=true` → `approval_memo_ref` mandatory and an **elevated** approver if over `approval_threshold` (§J), mirroring ADLP-exception governance (doc 04 §ADLP). Refund, disposition, commission and warranty are all per the approval memo.

---

## H. Ledger reversal at the unit's specific batch landed cost

The forward recognition point (doc 04 §Ledger) was `dispatch.delivered`: **DR `AR:<bill_to>` / CR `VAT` + revenue**, and **DR `COS_CLEARING:<entity>` / CR `INV:<entity>`** at the **specific batch landed cost** of the delivered serials. A refunding return **reverses both legs at that same specific cost**, via **reversing TigerBeetle transfers** — it never overwrites the original transfers (doc 04 §Ledger idempotency + doc 14 §1.5 "the ledger is the truth… every figure traces back"). Specific-identification (doc 14 ASC 330) means the reversal uses the *exact* lot the unit came from, captured as `rma_line.unit_landed_cost` at delivery.

```
postReturnReversal(rma):                                  // at 'refunded'; one linked transfer group
  group = deterministicGroupId(rma.refund_event_id)        // idempotent; flags.linked=true → atomic
  rma.tb_reversal_group = group
  for line in rma.lines WHERE this return refunds:
     // 1) AR + VAT reversal (credit note) — reverse revenue side at the ORIGINAL price/tax
     reverse: DR revenue + DR VAT:<entity> , CR AR:<bill_to>   for line.line_refund_amount (− restocking_fee)
              // a partial refund (restocking fee / component) reverses only the refunded portion
     // 2) Inventory/COGS reversal — at the SPECIFIC batch landed cost of THIS unit
     if disposition ∈ {restock, refurbish}:                // goods come back as an asset
        reverse: DR INV:<entity> , CR COS_CLEARING:<entity>   for line.unit_landed_cost × line.qty
        // inventory re-recognised at the unit's own lot cost (specific-ID) — restock to sellable, refurbish to refurb asset
     else:                                                 // scrap / return_to_supplier — goods do NOT return to inventory
        no INV reversal                                    // cost stays relieved (it left as COGS); RTV recovery is a separate AP claim
  // restocking fee, if any, is retained revenue (DR AR partial only / income) — recorded on the credit note
  emit return.refunded { rma_id, credit_note_id, reversal_group, lines:[{serial, refund_amount, unit_landed_cost, disposition}] }
```

Key properties (mirroring doc 04 §Ledger + doc 14):
- **Reverse, never overwrite.** The original delivery transfers stay immutable; the return books **new reversing transfers**, linked (`flags.linked=true`) so AR-side and inventory-side reverse atomically. Transfer ids are deterministic from the refund `event_id` → redelivery is a no-op (doc 04 §Ledger idempotency).
- **Specific-identification cost.** Inventory re-recognition uses `rma_line.unit_landed_cost` = the snapshot of *that serial's* `lot_batch.landed_unit_cost` taken at delivery — **not** an average and **not** the current replacement cost (doc 14 ASC 330, doc 04 §Inventory). Two returned units from different lots reverse at different costs.
- **Disposition gates the inventory leg.** Restock/refurbish → inventory comes back (DR INV / CR COS_CLEARING). Scrap/RTV → no inventory return (the cost legitimately left the business as COGS); a subsequent write-down only occurs if a restocked unit is later scrapped (doc 04 §Stock ops). This is why the reversal runs at/after `dispositioned` for the inventory leg, while the AR/VAT leg can run at `refunded`.
- **VAT reversal** uses the **original** line `tax_regime`/rate (copied to the credit note), so output VAT is reversed at the rate originally charged — never the current rate (cutoff correctness, doc 14 §2/§3 ASC 606).
- **P&L boundary unchanged.** Conduit reverses the AR + inventory sub-ledgers and emits matched negative revenue + COGS on `return.refunded`; the downstream P&L/GL recognises the reversal (the return is the variable-consideration adjustment to the original recognition — §below). Conduit owns the sub-ledgers and the reversal trigger, not the P&L (doc 04 §Ledger P&L boundary).

---

## I. Credit note & refund mechanics

- A refunding return issues exactly one `credit_note` (§B) against the original `order_invoice` (per tranche where the invoice was per-tranche, doc 02 §F). The credit note carries `total_ex_vat`/`vat_total`/`total_inc_vat`, the **original** `tax_regime`, and `refund_method`.
- **Refund routing by payment method** (mirrors order capture, doc 04 §Credit):
  - `stripe` (retail card) → `refund_method='stripe_refund'`, Stripe refund issued, `stripe_refund_id` captured.
  - `credit`/`invoice` (trade) → `refund_method='credit_memo'` applied against the payer's AR (the **bill-to**, central billing — a CEF branch return credits CEF master, doc 02 §F), or `bank` for an explicit repayment.
- **Restocking fee** (per `return_type_rule.restocking_fee_pct`, e.g. `changed_mind`) reduces the refund: `line_refund_amount = line_value × (1 − fee_pct/100)`; the retained fee is recorded on the credit note and is **not** reversed from revenue (it is retained consideration).
- **Largest-remainder split** (doc 14 §1.3): when a whole-order or multi-line refund is apportioned to `rma_line`s, `allocate(refund_total, weights=line_values)` guarantees Σ line refunds == refund total exactly — no penny created/lost.
- Xero consumer fills `xero_credit_note_id` from `return.refunded` (mirrors `order.invoiced` → Xero, doc 03).

---

## J. Access control & maker-checker (doc 05)

Returns follow the pack's maker-checker / segregation-of-duties pattern (doc 04 §Stock ops, doc 05 §4–5: "Returns/RMA approval follows the same pattern… the system rejects self-approval").

| action | permission (object:action) | who (seed roles, doc 05 §4) | notes |
|---|---|---|---|
| raise an RMA | `create:rma` | `customer_service_agent` (already "raise refund/RMA + warranty claims"), `retail_sales_agent` (own orders) | the **maker** |
| assess/grade | `edit:rma:assess` | `customer_service_agent`, `fulfilment_agent` (inspection) | |
| **approve** | `approve:rma` | a **different** principal holding it (`customer_service` lead / `finance` / desk lead) | **requester ≠ approver, system-enforced** |
| approve over `approval_threshold` / `goodwill` | `approve:rma` + elevated (`finance`/CEO) | mirrors ADLP elevated approval (doc 04 §ADLP) | `approval_memo_ref` required |
| disposition (route stock) | `edit:rma:disposition` | `fulfilment_agent` | posts movements / serial status |
| issue credit note / refund | `create:credit_note` | `finance` | money leg |
| view RMA | `view:rma` (scoped + layer-projected) | per scope; refund/cost fields gated by `profitability`/`commercial` layers (doc 05 §3) | `unit_landed_cost` is `profitability`; `refund_amount` is `commercial` |

```
approveRma(rma, approver):
  require authz(approve, 'rma', scope=rma.scope)
  require approver.id != rma.requested_by                  // SoD — system rejects self-approval (doc 05 §5)
  rule = resolveReturnTypeRule(rma)
  if rule.requires_memo: require rma.approval_memo_ref present
  if rma.refund_amount > rule.approval_threshold: require approver holds elevated approve:rma   // ADLP-style
  rma.approved_by = approver.id; rma.status='approved'
  armCommissionClaw(rma)                                   // §F (effected on refund/replace)
  if rule.issues_replacement: issueReplacement(rma, ...)   // §E
  insert audit_log(entity_type='rma', action='approve', before, after, actor=approver) + emit return.approved
```

- **Scope & layers:** `rma` carries `entity_id`/`market_id`/`channel_id` (copied from the order) → scope-filtered on every list/read (doc 05 §2). Cost/refund/commission fields are layer-projected: `unit_landed_cost`/margin → `profitability`; `refund_amount`/credit-note money → `commercial`; commission claw → `commission`; PII (return contact) → `pii`. A volume-only viewer sees the RMA and its units but no money (doc 05 §3).
- **Audit:** every transition (raise/assess/approve/reject/disposition/refund/replace) writes `audit_log` (append-only, Admin-uneditable, doc 05 §5) and is anchored in TigerBeetle for the money legs (doc 14 §4). The full RMA is reconstructable from `audit_log` + the event log + TB alone (doc 05 §5 reconstruction guarantee).

---

## K. Events (extends doc 03 — `return.*`)

Doc 03 already registers `return.raised/assessed/approved/restocked/refunded/replaced` · key `rma_id` · → inventory (restock/refurb/scrap), serial lifecycle, ledger reversal, commission claw, audit. This section gives the payload schemas, producers and consumers (Avro envelope per doc 03 §1; `aggregate_type='rma'`, topic `conduit.orders`, partition by `rma_id`; `BACKWARD` compatibility; idempotent on `event_id`).

| event | producer | key payload fields | consumers |
|---|---|---|---|
| `return.raised` | RMA service | `{rma_id, rma_no, order_id, type, scope, reason_code, lines:[{order_line_id, serial?, component_ref?, qty}], requested_by, entity_id, market_id, channel_id}` | RMA read-model, **audit**, notifications (RMA label), warranty (if warranty/DOA → pre-check) |
| `return.assessed` | RMA service | `{rma_id, eligibility, condition_grades:[{serial, grade}], refund_preview:{amount,currency}, assessed_by}` | RMA read-model, audit |
| `return.approved` | RMA service | `{rma_id, type, approved_by, refund_amount:{amount,currency}, restocking_fee, replacement_order_id?, commission_claw_armed:bool, approval_memo_ref?}` | **commission** (arm claw), **orders** (issue replacement), warranty (draw-down arm), audit, notifications |
| `return.restocked` | RMA service (disposition) | `{rma_id, lines:[{serial, disposition, to_status, location_id, stock_movement_id}]}` | **inventory** (stock + serial status), **serial.lifecycle**, audit. *(carries refurbish/scrap/RTV dispositions too, despite the name — the disposition event)* |
| `return.refunded` | RMA service | `{rma_id, credit_note_id, credit_note_no, reversal_group, refund_method, totals:{ex_vat,vat,inc_vat,currency}, tax_regime, lines:[{serial, refund_amount, unit_landed_cost, disposition}]}` | **ledger** (reversing transfers, §H), **Xero** (credit note), **commission** (effect claw), AR projection, audit |
| `return.replaced` | RMA service | `{rma_id, replacement_order_id, priced:bool, lines:[{original_serial, variant, qty}]}` | **orders** (the replacement is itself an `order.placed`), warranty (fresh provision on the replacement's activation), audit |

- **Causation chain:** `return.approved.causation_id = return.raised.event_id`; `return.replaced` causes a downstream `order.placed` (doc 03 Orders) for the replacement; `return.refunded` causes `ledger.posted` (the reversal) and a Xero credit note. The chain is traceable end-to-end (doc 14 §5.1 lineage).
- **Layer projection on the wire:** external adapters subscribe to **layer-filtered** projections (doc 05 §3) — e.g. a 3PL consumer of `return.restocked` sees serials/locations (`volume`) but not `unit_landed_cost` (`profitability`).
- **Custom attributes** ride as the Avro `map<string,string>` mirror of `rma.attributes` (doc 03 §2 / doc 02 §M) — workflow/segmentation only, never money.

---

## L. REST surface (extends doc 06 — `/orders/{id}/returns` + `/returns`)

Extends the doc 06 stub (`POST /orders/{id}/returns`, `GET /returns`, `POST /returns/{id}/approve`). Base `/api/v1`; Keycloak bearer; authz per §J; money fields `{amount,currency}`; errors per doc 06 (`400/401/403/404/409/422`). All list endpoints scope-filtered + layer-projected (doc 05).

```
POST   /orders/{id}/returns
        { type: full_unit|part_only|multi_unit|dead_on_arrival|warranty_replacement|goodwill,
          scope: whole_order|line|serial|component,
          reason_code,
          lines:[{ order_line_id, serial?, component_ref?, qty?, reason_code? }],
          restocking_fee_override?, attributes? }
        → Rma (status=raised)
        // 422 if outside return_window_days (type-specific) | order not delivered | serial not on this order
        // 422 if warranty_replacement/doa and the serial is out of warranty / no provision

GET    /returns?status=&order_id=&type=&serial=&bill_to_party_id=&entity_id=&market_id=&channel_id=
        → [Rma]   (scope-filtered, layer-projected; cost/refund/commission fields gated)

GET    /returns/{id}
        → Rma (lines[], dispositions[], credit_note?, replacement_order_id?, lifecycle[])

POST   /returns/{id}/assess
        { lines:[{ rma_line_id, condition_grade, reason_code? }], eligibility_note? }
        → Rma (status=assessed; refund_preview computed)        // requires edit:rma:assess

POST   /returns/{id}/approve
        { decision: approve|reject, refund_amount?, approval_memo_ref?, issue_replacement? }
        → Rma   // requires approve:rma; 403 if approver == requester (SoD); 403 if over threshold without elevation;
                // 422 if goodwill without memo. On approve: arms commission claw, issues replacement if applicable.

POST   /returns/{id}/receive
        { lines:[{ rma_line_id, received_qty, serial? }] }
        → Rma (status=received; serials → 'returned')           // fulfilment; per line/tranche

POST   /returns/{id}/disposition
        { lines:[{ rma_line_id, disposition: restock|refurbish|scrap|return_to_supplier,
                   location_id?, supplier_claim_ref?, evidence? }] }
        → Rma   // requires edit:rma:disposition; 422 if restock requested on non-A-grade or previously-activated serial;
                // posts stock_movement + serial status + return_disposition; emits return.restocked

POST   /returns/{id}/refund
        { refund_method: stripe_refund|credit_memo|bank }
        → CreditNote   // requires create:credit_note (finance); posts reversing transfers at batch cost (§H);
                       // emits return.refunded; 409 if already refunded / not approved

GET    /returns/{id}/credit-note            → CreditNote
GET    /returns/{id}/disposition            → [ReturnDisposition]   (per-serial routing + movements)

// type-rule admin (governed; maker-checker on activation, doc 05 §4 / doc 14 §4)
GET    /admin/return-type-rules?type=&entity_id=&market_id=        → [ReturnTypeRule]
POST   /admin/return-type-rules                                    → ReturnTypeRule (versioned; audited)
GET    /admin/reason-codes ; POST /admin/reason-codes              (governed reason list)
```

Status codes: `201` raised; `200` on transitions; `202` if approval needs elevation routing; `409` on illegal transition (e.g. refund before approve, disposition before receive, double-refund); `422` on domain rules (out of window, restock on activated/non-A unit, goodwill without memo, warranty replacement out of warranty).

---

## Acceptance

Each bullet is a test (doc 07 M9b). Turn these into the suite first.

1. **Per-type distinct flows.** A `part_only` return and a `full_unit` return on the same order follow different flows: the part-only refunds only the component value, **retains** commission, and does not touch the charger serial; the full-unit reverses the line, claws commission, and dispositions the serial. (§A/§G)
2. **Maker ≠ checker.** Approving an RMA with `approver.id == requested_by` is rejected (403, SoD); approval by a distinct principal holding `approve:rma` succeeds and writes `audit_log`. (§J)
3. **Serials never silently re-enter sellable stock.** A returned serial sits at `status='returned'` after `receive`; `restock` is **rejected** (422) for non-A-grade, fault-reason, or previously-**activated** units — those route only to `refurbish` (non-sellable refurb location) or `scrap`. A B-grade unit cannot reach `in_stock` without a separate audited `found`/QA pass. (§D)
4. **Disposition posts immutable movements + genealogy.** Each disposition writes a signed `stock_movement`, a `return_disposition` row, flips `serial_unit.status`, and appends a `unit_lifecycle_event` (`returned`→`rma`→`refurbished`/`scrapped`); the serial's full history is reconstructable. (§D)
5. **Ledger reverses at the unit's specific batch landed cost.** Two returned units from **different lots** reverse inventory at **different** `unit_landed_cost` values (the snapshot taken at delivery, not current cost, not average); the reversal is a **new reversing transfer group** (`flags.linked=true`), the original delivery transfers are untouched, and redelivery of `return.refunded` is a no-op (idempotent). VAT reverses at the **original** rate. (§H)
6. **Disposition gates the inventory leg.** `restock`/`refurbish` re-recognise inventory (DR INV / CR COS_CLEARING) at batch cost; `scrap`/`return_to_supplier` book **no** inventory return. (§H)
7. **Commission claws via the two-phase, not by reopening.** A return of a dispatched line books a **new** `commission_entry(kind='claw')` with a reversing transfer in the **current open** period (the prior period stays as reported); a partial/component return claws only the pro-rata share; `warranty_replacement`/`part_only` (default) **retain** commission. (§F)
8. **Warranty replacement issues a new order and a fresh warranty.** A `warranty_replacement` issues a **zero-priced** replacement `order` (no revenue, no commission), draws down the original unit's `warranty_provision` (`warranty_claim.rma_id` linked), and on the replacement's activation opens a **new** provision with a fresh start date; the original provision is closed/voided. The replacement carries `order.origin_rma_id`. (§E/§G)
9. **DOA path.** A `doa` return claws the original commission, routes the dead unit to `return_to_supplier` (RTV/Luxshare claim with `supplier_claim_ref`), and — at customer choice — either issues a credit note **or** a zero-priced replacement order. (§G)
10. **Goodwill governance.** A `goodwill` return **requires** `approval_memo_ref`; a refund over `return_type_rule.approval_threshold` requires an elevated approver (ADLP-style); without the memo the approval is rejected (422). (§G/§J)
11. **Variable consideration (ASC 606).** The refund reverses revenue + VAT at the original price/rate as a variable-consideration adjustment to the original recognition; a **restocking fee** reduces the refund and is retained as consideration (not reversed from revenue); the apportioned line refunds sum exactly to the refund total (largest-remainder, no penny lost). (§H/§I, doc 14 §3)
12. **Credit note + refund routing.** A refunding return issues exactly one `credit_note` against the original invoice; a retail card order routes to `stripe_refund` (captures `stripe_refund_id`), a trade order to `credit_memo` against the **bill-to** (central billing credits the master), and the Xero consumer fills `xero_credit_note_id`. (§I)
13. **State-machine invariants.** Money cannot reverse before `approved` (409); sellable stock cannot increase before `dispositioned` (409); a double-refund is rejected (409); an out-of-window `full_unit`/`changed_mind` return is rejected (422) while a warranty/DOA return has no window. (§C/§L)
14. **Scope + layer projection.** A volume-only viewer sees the RMA and its units but **no** `unit_landed_cost`/`refund_amount`/commission fields; an out-of-scope (wrong market) principal does not see the RMA at all; external `return.restocked` consumers get serials/locations but not cost. (§J/§K, doc 05)
15. **Full reconstruction.** A completed RMA (raise→assess→approve→receive→disposition→refund/replace→close) is fully reconstructable from `audit_log` + the `return.*` event log + TigerBeetle alone, with the money legs anchored in TB. (§J, doc 14 §5)

*Supports build milestone **M9b** (doc 07).*
