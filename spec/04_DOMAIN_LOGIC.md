# 04 — Domain Logic & Algorithms

Pseudocode is language-neutral; implement in Scala/cats-effect. All monetary arithmetic uses `BigDecimal` with `NUMERIC(18,4)` scale and `HALF_UP` rounding at presentation only — intermediate math stays full precision.

---

## §Pricing — resolution

Given `(variant, channel, market, currency, entity, qty, asOf)` resolve the customer price:

```
resolvePrice(variant, channel, market, currency, entity, qty, asOf):
  candidates = price_rule WHERE surface='customer'
     AND product_variant_id = variant
     AND currency = currency
     AND status='active'
     AND effective_from <= asOf AND (effective_to IS NULL OR effective_to > asOf)
     AND (channel_id = channel OR channel_id IS NULL)
     AND (market_id  = market  OR market_id  IS NULL)
     AND (entity_id  = entity  OR entity_id  IS NULL)
     AND min_qty <= qty
  if candidates empty: raise PriceNotFound(variant, channel, market, currency)
  // specificity: prefer exact channel>null, market>null, entity>null; then highest min_qty <= qty (volume break); then highest version
  rule = candidates.sortBy(specificityScore desc, min_qty desc, version desc).head
  taxRate = tax_regime[rule.tax_regime].rate_percent
  return PriceResolution(
     ruleId=rule.id, exVat=rule.authorised_price, maxDiscountPct=rule.max_discount_pct,
     taxRegime=rule.tax_regime, taxRatePct=taxRate)
```

Inter-entity transfer price (`surface='inter_entity'`) resolves on `(from_entity, to_entity, variant, asOf)`; if `tp_method='cost_plus'` then `transferPrice = batch.landed_unit_cost × (1 + tp_markup_pct/100)` using the **specific lot_batch** being moved (doc §Intercompany). The two surfaces are walled by data layer (doc 05).

---

## §ADLP — line categorisation & enforcement

On adding/updating an order line:

```
priceLine(line, account, agent):
  pr = resolvePrice(line.variant, order.channel, order.market, order.currency, order.entity, line.qty, now)
  appliedDiscountPct = (pr.exVat - line.unit_price_ex_vat) / pr.exVat * 100   // 0 if at list
  if appliedDiscountPct <= pr.maxDiscountPct + EPS:
     line.adlp_category = 'standard'
  else:
     line.adlp_category = 'exception'
  line.price_rule_id = pr.ruleId
  line.vat = round(line.unit_price_ex_vat × line.qty × pr.taxRatePct/100)
  return line
```

Order commit gate:

```
placeOrder(order):
  require authz(create, 'order', scope=order.scope)
  if order.lines.any(adlp_category == 'exception' AND not hasApprovedException(line)):
     order.status = 'pending_ceo'
     create adlp_exception(status='pending_ceo') per offending line
     emit adlp.exception.requested
     return  // NOT committed as placeable; no allocation, no commission
  creditCheck(order)                       // §Credit
  order.status = 'placed'
  persist(order, order_lines) + outbox(order.placed)   // one transaction
```

Approved-exception path: CEO approves → `adlp_exception.status='approved'`, `approved_by`, `approval_memo_ref` (immutable) → `adlp.exception.approved` → order re-evaluated and `placeOrder` proceeds; commission uses `commission_plan.exception_treatment`.

**Deal Desk / exception state machine:**
```
draft ──submit──> pending_ceo ──CEO approve──> approved ──> (order releases)
                       │
                       └──CEO reject──> rejected (order stays held / re-priced)
```
Only role with `permission(approve,'adlp_exception')` (CEO preset) can transition `pending_ceo→approved`. Deal Desk role has `edit` (assemble justification, volume P-denomination, margin assessment) but **not** `approve`.

---

## §Credit check

```
creditCheck(order):
  if order.payment_method == 'stripe': return  // card, no credit
  soldTo = order.sold_to_party ; payer = order.bill_to_party
  cp = creditProfile(soldTo)
  creditNode = (cp?.scope == 'shared_with_parent') ? rootParent(soldTo)   // walk parent_party_id to the credit-owning party
                                                    : soldTo
  cp = creditProfile(creditNode)
  exposure = outstandingInvoices(payer-subtree) + openOrdersValue(creditNode-subtree) + order.total_inc_vat
             // shared scope aggregates exposure across all branches under the parent/master
  if exposure > cp.credit_limit:
     if cp.policy == 'block': raise CreditLimitExceeded
     else: attach warning(order, exposure - cp.credit_limit)
```
A CEF branch order draws on **CEF master's** shared limit (exposure summed across all CEF branches); a self-credit party is assessed on its own `credit_profile`. AR posts to the **bill-to/payer**; statements are per payer. A party with no `credit_profile` and no card cannot place a credit order (must be promoted with billing + credit first).

---

## §Orders — state machine, amendment & scheduling

```
draft → placed → (partially_allocated|allocated) → (partially_dispatched|dispatched) → delivered →[auto] invoiced → closed
   │        │                                                                   │
   │        └── pending_ceo (exception) ──approve──> placed                     └── return/rma  (first-class; doc 09)
   └── cancelled (from any pre-dispatch state)
```
Transitions emit the matching events (§03). **`delivered` auto-triggers `invoiced`** (ASC 606 — invoice cannot precede delivery; doc §Ledger). Invariants: `Σ dispatch_line.qty ≤ order_line.qty`; no `dispatched` with a serialised line lacking serials; no `invoiced` without `delivered`.

### Amendment (post-placement, permission-gated, pre-dispatch cutoff)
Orders are amended in the field/desk all the time. An order may be amended after `placed` **until `amend_cutoff`** (default = first dispatch on the order/tranche; configurable):
```
amendOrder(order, changes, actor):
  require actor holds edit:order:amend            // permission-gated (admin/elevated), doc 05
  require now < order.amend_cutoff AND no tranche of the changed line is dispatched
  snapshot = order.linesAndSchedule
  apply changes (add/remove line, qty, schedule, ship-to, discount)
  re-run priceLine + ADLP per changed line       // may re-trigger pending_ceo if a new discount breaches band
  re-run allocate for affected lines/tranches     // release then re-reserve
  recompute commission preview/accrual
  insert order_amendment(before=snapshot, after=…, actor, reason) + emit order.amended
```
After the cutoff (or once dispatched), changes are not amendments — they go through **return/RMA** (first-class, doc 09).

### Scheduled / tranched orders (call-off)
A line may carry a **delivery schedule** of tranches (e.g. 500 = 250 on D1 + 250 on D2). Each tranche is fulfilled independently:
```
order_line.is_scheduled = true
for tranche in line.tranches (by requested_date):
   allocate(tranche)                 // ATP per tranche, against tranche.requested_date
   dispatch(tranche) → delivered(tranche) → [auto] invoice(tranche)   // revenue+COGS recognise per drop (ASC 606)
line.status = rollup(tranche statuses)   // allocated / partially_dispatched / dispatched
```
Future tranches feed **supply planning** (net requirement by `requested_date`) and **H6Q** (scheduled future demand). Backorder/ATP, commission accrual and invoicing all operate per tranche.

---

## §ATP & Allocation

`available(variant, location, entity) = stock_item.qty_on_hand − stock_item.qty_allocated`. Allocation targets an `order_line` or a specific `delivery_tranche` (scheduled lines allocate per tranche, by `requested_date`).

Allocation must be **concurrency-safe** — two desks hitting the last unit must not over-commit:

```
allocate(line_or_tranche):                  // runs in a DB transaction
  needed = target.qty - target.qty_allocated
  for loc in preferredLocations(order.entity):
     // row lock the stock row to serialise concurrent allocators
     row = SELECT * FROM stock_item
            WHERE entity_id=order.entity AND product_variant_id=line.variant AND location_id=loc
            FOR UPDATE
     take = min(needed, row.qty_on_hand - row.qty_allocated)
     if take > 0:
        UPDATE stock_item SET qty_allocated = qty_allocated + take WHERE id=row.id
        if line.variant.is_serialised:
           serials = SELECT ... FROM serial_unit
                      WHERE variant=line.variant AND location=loc AND status='in_stock'
                      ORDER BY created_at LIMIT take FOR UPDATE SKIP LOCKED
           mark serials status='allocated', order_line_id=line.id
           insert allocation rows (with serial_unit_id, tranche_id)
        else:
           insert allocation(order_line_id, tranche_id, location_id=loc, qty=take)
        target.qty_allocated += take ; needed -= take
     if needed == 0: break
  target.status = (needed==0 ? 'allocated' : 'backordered')
  emit order.allocated
```

**Backorder fill on receipt:** `inventory.received` consumer re-runs `allocate` for `backordered` lines/tranches of that variant, by `requested_date` then order age (configurable priority), within the same locking discipline.

**ATP forward-promise:** if no on-hand, promised/tranche date checks earliest `po_line.expected_date` with uncommitted incoming ≥ needed.

---

## §Commission — scheme resolution, calculation & lifecycle

Commission is a first-class, time-bounded, team/channel/country-scoped scheme (doc 02 §J). At order time the applicable scheme is resolved like pricing — by specificity + validity window:

```
resolveScheme(agent, order, asOf):
  candidates = commission_scheme s JOIN commission_scheme_assignment a ON a.scheme_id=s.id
     WHERE s.status='active'
       AND s.valid_from <= asOf AND (s.valid_to IS NULL OR s.valid_to > asOf)   // validity window
       AND (a.team_id    = agent.team_id   OR a.team_id    IS NULL)
       AND (a.channel_id = order.channel_id OR a.channel_id IS NULL)
       AND (a.market_id  = order.market_id  OR a.market_id  IS NULL)
       AND (a.entity_id  = order.entity_id  OR a.entity_id  IS NULL)
  if candidates empty: return NoScheme   // 0 commission, flagged
  // specificity: a more specific assignment wins (team+channel+market > team+channel > team > channel ...)
  return candidates.sortBy(specificityScore desc, s.valid_from desc).head
```
So a **wholesale team** scheme and a **retail team** scheme differ by assignment; a **per-country** override is just an assignment with `market_id` set; all are bounded by `valid_from/valid_to`.

```
computeCommission(line, order, scheme):
  // basis confirmed = gross_margin
  basis = switch scheme.basis:
     'gross_margin' -> (line.unit_price_ex_vat − unitLandedCost(line)) × line.qty   // unitLandedCost from the dispatched batch
     'net_revenue'  -> (line.unit_price_ex_vat × line.qty) − allocatedRebates(line)
     'revenue'      -> line.unit_price_ex_vat × line.qty
  if line.adlp_category == 'exception':
     if not approved(line): return 0
     applyExceptionTreatment(scheme.exception_treatment)   // full|reduced|zero
  rate = scheme.rate_pct/100
       × tierMultiplier(scheme.tiers, agentAttainment(order.agent, period))   // optional
       × productModifier(scheme.product_modifiers, line.variant)              // optional
       × discountModifier(scheme.discount_modifier, line.discount_pct)        // optional
  return round(basis × rate)
```
Note: gross margin needs `unitLandedCost` — at quote/preview time (no batch yet) use the variant's reference `std_cost`; at posting time use the **actual dispatched batch** landed cost, and true-up the entry.

Real-time: recompute + show on every line edit (web/mobile), using the resolved scheme. Each agent sees their own running commission in the **companion app** on login (own-scope, commission layer). Authoritative recompute server-side at placement; true-up at the next period close.

**Lifecycle (TigerBeetle two-phase):**
```
order.placed (compliant) -> commission.accrued -> PENDING transfer  (COMM_PAYABLE:<agent> ← accrual, provisional std_cost margin)
order.dispatched          -> commission.posted -> POST pending      (earned)
order.cancelled/refunded  -> commission.clawed -> VOID pending / reversing transfer
```
**True-up cadence (resolves #17):** a posted entry is **not reopened** when a cost later changes. Instead a **periodic true-up run** (cadence = **quarterly** by default, configurable on `commission_period`) recomputes each agent's earned commission on **actual** batch margins for the closed period and books the delta as a current-period adjustment entry. The prior period stays as reported (clean close), the agent's app shows accrued-vs-trued-up, and the cadence can change without code (it's data). `commission_entry` records `scheme_id` + `commission_period_id` (provenance) and `status` mirroring the transfer state; statements = projection over posted/adjusted entries per agent/team/period.

---

## §Ledger — TigerBeetle posting model

One **ledger per currency** (`ledger = currencyCode → int`). Conduit's TigerBeetle is the **operational financial sub-ledger** (AR, AP, inventory asset, commission payable, intercompany, tax control) — it is **not** the P&L. **P&L construction (revenue recognition + COGS recognition + period close) is a downstream consumer** (future ERP / Horizons), built off Conduit's events; see "P&L boundary" below.

Accounts (TigerBeetle `Account`, 128-bit id, `code` = account-type enum):

| account | role |
|---|---|
| `AR:<company>` | receivable per trade customer |
| `AP:<supplier>` | payable per supplier |
| `INV:<entity>` | inventory asset per entity (valued at **specific-identification** batch landed cost) |
| `COS_CLEARING:<entity>` | cost-of-sales clearing — inventory relieved here on delivery; downstream P&L reclassifies into COGS |
| `VAT:<entity>` | tax control |
| `COMM_PAYABLE:<agent>` | commission payable |
| `IC:<entityA>:<entityB>` | intercompany clearing |
| `FX_CLEARING` | cross-currency bridge |

**Postings (transfers; pending→post/void for lifecycle):**
- `inventory.received` → DR `INV`, CR `AP:<supplier>` at **batch landed cost** (USD cost × FX + freight + duty, per lot — doc §FX/§Inventory).
- `dispatch.delivered` is the **single recognition point** (Conduit **enforces ASC 606** by auto-triggering the invoice on delivery — an invoice can never be issued before control transfers, so invoice date = delivery date = revenue-recognition date; there is no invoice/delivery divergence and no unbilled/deferred case). On this one event:
  - DR `AR:<bill_to>`, CR `VAT` (tax) + revenue amount → the invoice is generated and AR booked **to the bill-to/payer** (a CEF branch order invoices to CEF master); credit terms set the *due date*, not the recognition date;
  - DR `COS_CLEARING:<entity>`, CR `INV:<entity>` at the **specific batch landed cost** of the delivered serials.
  Conduit emits the matched **revenue + COGS** on the event; the downstream P&L/GL recognises them (Conduit owns AR + inventory sub-ledgers and the recognition *trigger*, not the P&L).
- `commission.accrued` → **pending** transfer to `COMM_PAYABLE:<agent>`; `posted`→post_pending; `clawed`→void_pending.
- `intercompany.movement.posted` → two **linked** transfers across the two entities' currency ledgers, bridged via `FX_CLEARING` when currencies differ; `flags.linked=true` so both commit atomically.

**P&L boundary:** Conduit knows the **COGS amount** the moment serials are allocated (serial → batch → landed cost) and recognises the cost flow at **delivery**, the same event that issues the invoice and books AR — so revenue and COGS are inherently matched. Period close, statutory P&L and the GL live downstream — not in Conduit's MVP. **Costing is strict specific-identification — no weighted-average fallback**: each serial carries its own lot's landed cost into margin, commission true-up and inventory valuation.

Idempotency: transfer `id` is deterministic from `event_id` (+ leg index), so redelivery is a no-op. The Postgres row stores `tb_transfer_id`.

---

## §FX

Every monetary record stores transaction amount + currency and the functional amount at `fx_rate` (resolved from `fx_rate` table at `rate_type='spot'`, latest `effective_date ≤ date`). Group presentation currency (likely **USD**, given Luxshare/Nasdaq orientation — confirm) is a reporting projection: functional → presentation at `period_close`. Realised/unrealised revaluation is a ledger consumer (Phase: ERP/GL) — out of MVP.

**Luxshare / purchase cost FX.** Luxshare bills **always in USD**. A lot's landed cost is built per-batch and is **batch-specific** (the contract manufacturer can reprice lot-to-lot, and freight/duty/FX all move):

```
landed_unit_cost(lot) =
   ( lot.unit_cost_usd × fxApplied(USD → entity.functional_currency, lot) )   // per-lot USD price
   + perUnit(lot.freight_alloc)
   + perUnit(lot.duty_alloc)
```
`fxApplied` defaults to spot at goods-receipt, **but** Hypervolt hedges USD exposure in operating markets. Hedges are first-class (`fx_hedge`, doc 02 §A): a hedge covers a currency pair (`USD→GBP` etc.) for a **validity window** with a **contracted rate**. If a lot's purchase falls in a hedge's window and is **designated** to it (`lot_batch.hedge_ref`), landed cost uses the hedge's `contracted_rate`, the hedge's `notional_used` is drawn down, and `lot_batch.fx_basis='hedged'` records it; otherwise spot, `fx_basis='spot'`. The `fx_hedge` register also drives **consolidated reporting** (translating exposure to the USD presentation currency at hedged rates). Hedge instruments are administered under a dedicated treasury permission set (doc 05).

---

## §Intercompany transfer pricing

```
moveStockBetweenEntities(fromEntity, toEntity, variant, qty, lots):
  policy = transfer_price_policy(fromEntity, toEntity, variant, now)
  for lot in lots:                      // batch-specific
     tp = policy.method=='cost_plus' ? lot.landed_unit_cost × (1+policy.markup_pct/100) : ...
     create IC sell_order (fromEntity, line @ tp) ; create IC buy_po (toEntity, line @ tp)
     link = intercompany_link(sell_order, buy_po)
     emit intercompany.movement.posted   // ledger linked transfers + tax/customs
  // cross-border: compute import VAT/duty on the toEntity side from HS code + destination regime
```
Transfer price is reproducible from `policy + lot.landed_unit_cost` → recorded for TP documentation.

---

## §H6Q — coverage & sell-through

### Distributed weekly capture & auto-rollup
H6Q is built from many owners forecasting their own accounts asynchronously; Conduit captures and rolls up — no central re-keying.

```
openCycle(cadence='weekly'):                       // scheduler, timezone-agnostic
  cycle = forecast_cycle(code=isoWeek(now), period_start, period_end, status='open')
  for owner in users who own ≥1 account:
     for acct in accountsOwnedBy(owner):           // company.owner_user_id / account_manager_user_id
        forecast_submission(cycle, owner, acct, status='outstanding')
  notify each owner (push/email) — they act whenever their timezone allows

submitForecast(owner, acct, cycle, lines):          // from mobile/tablet/web; offline-queued
  require owner owns acct
  for (variant, period_month, scenario, qty) in lines:    // catalogue pulled live → new SKUs appear automatically
     prior = currentEntry(acct, variant, period_month, scenario)
     insert forecast_entry(submission, cycle, owner, acct.channel, acct.segment, acct.market,
                           company=enclosing(acct), branch=acct, variant, period_month, scenario, qty, source='manual')
     if prior: prior.superseded_by = newRow            // append-only; full history kept
  submission.status='submitted'; submission.submitted_at=now
  emit forecast.submitted
```
- **Auto-rollup:** the `forecast.submitted` consumer recomputes `pipeline_coverage` bottom-up: account → branch → enclosing customer/wholesaler → segment → sub-channel → channel → market, and independently **by `forecaster_user_id`** (so branch and agent views reconcile). Materialised, replayable — no spreadsheet, no manual consolidation.
- **Outstanding view:** `forecast_submission.status` gives "who still owes this week" across regions for nudging; coverage shows partial vs complete capture.
- **Catalogue growth:** the submission surface reads the live catalogue, so new variants are forecastable the moment they exist — no schema/config change.
- **Accuracy:** because every estimate is retained with its author and timestamp, `forecast_accuracy` later scores each owner's estimates against actual sell-in/sell-through (error/bias/MAPE) per account and period.

### Coverage & sell-through
```
// coverage is computed at a grouping level; the board rolls up/drills down across:
//   channel → sub_channel → segment → customer(company) → branch ; and independently by sales agent
coverage(groupBy, key, market, period, scenario):     // groupBy ∈ {channel, sub_channel, segment, company, branch, agent}
  scopeDeals  = open deals matching (groupBy=key, market, expected_close in period)
  scopeOrders = orders matching (groupBy=key, market) dispatched in period
  forecast = Σ forecast_entry.qty (key,market,period,scenario)
  weightedPipeline = Σ over scopeDeals: Σ deal_line.qty × pipeline_stage.probability_pct/100
  shipped = Σ order_line.qty_dispatched for scopeOrders
  coverage_pct = (shipped + weightedPipeline) / forecast
  coverage_ex_account_pct = same, filtering out the company in scenario.toggle_basis (e.g. ex-Octopus)
```
- **Branch rollup:** a wholesaler's coverage = Σ of its branch rows (`company.parent_company_id`); drill into each branch (its own `account_manager_user_id`).
- **By sales agent:** group on the deal/order owner (`agent_user_id`) so the same numbers re-aggregate by person — every branch maps to an account manager, so branch and agent views reconcile.
- **Segment/sector:** group on `company.segment` to roll many smaller customers into a sector line; expand to the enclosing customers on demand.

```
sellThrough(company|branch, channel, period):
  sell_in       = Σ dispatched qty to company/branch in period      // from dispatch
  sell_through  = Σ activations bound to company/branch in period   // from activation
  overhang      = cumulative sell_in − cumulative sell_through      // shipped-not-activated
```
`pipeline_coverage` and `sell_through` are **materialised projections** rebuilt by consumers of `order.*`, `dispatch.*`, `activation.recorded`, `crm.deal.*`, `forecast.updated`. No cross-system cache; replayable.

**V2/V3:** activation/sell-through maths consider only `serial_unit.generation='v3'` (prefix `0301`); `v2` (`HYPV-`) are excluded from on-shelf/activation calculations (they activate via a legacy path and would otherwise read as forever-on-shelf).

**Forecast sources (Hyperview).** `forecast` for a channel/period sums `forecast_entry` rows; entries carry `source` (`manual` | `hyperview`). For **retail**, Hyperview (Prophet on ad-spend + inputs, a separate project) publishes model forecasts that land as `source='hyperview'` rows; H6Q can use Hyperview as the retail line, keep manual as an override, or show both for comparison. Resolution rule when both exist for a key (configurable): prefer the latest `manual` override else `hyperview`. Hyperview is an upstream integration (Phase 3); H6Q does not run Prophet itself.

---

## §Serial & Activation

```
onActivation(record from athena-placement-versioned):  // idempotent
  serial = deviceIdToSerial(record.device)
  if serial.generation != 'v3': ignore        // v2 legacy path
  existing = activation[serial]
  if existing: return                          // first-write-wins
  insert activation(serial, placement_id, version, installer(from v1), model, mac, owner_keycloak, country, now)
  serial_unit[serial].status='activated'; .company_id = resolveAccount(serial); .activated_at=now
  serial_unit.warranty_end = now + legal_warranty(jurisdiction, variant.family).months + Σ warranty_extension   // clock starts at ACTIVATION
  openWarrantyProvision(serial, start=now, end=serial_unit.warranty_end)   // §Warranty
  unit_lifecycle_event(serial,'activated')
  emit serial.lifecycle + activation.recorded + warranty.provision.accrued
```
`resolveAccount(serial)` uses `serial_number`/order genealogy (which account we shipped to). Re-placements (version>1) update placement metadata but never re-fire first activation.

---

## §Warranty provision & exposure

The warranty clock **starts at activation**. Conduit maintains a per-unit provision register and the **consolidated exposure + release-to-balance-sheet cycle**; balance-sheet *posting* is downstream (P&L/GL boundary), fed by `warranty.*` events.

```
openWarrantyProvision(serial, start, end):
  rate = warranty_rate(variant.family, variant.generation, asOf=start)   // versioned assumption
  basis = serial.lot_batch.landed_unit_cost                              // cost basis (specific-ID)
  est   = rate.provision_per_unit ?? (basis × rate.provision_rate_pct/100)
  insert warranty_provision(serial, entity, lot_batch, warranty_start=start, warranty_end=end,
                            estimated_provision=est, currency=entity.functional, status='open')

// warranty term (set at activation):
//   warranty_end = activation_date
//                + legal_warranty(jurisdiction, variant.family).statutory_months   // mandatory, jurisdiction-specific
//                + Σ warranty_extension(serial|order_line).extra_months             // sold/goodwill extension
// release runs straight-line over [activation_date, warranty_end] (curve swappable later)

releaseSchedule(provision, asOf):                 // straight-line over the term by default
  termDays   = provision.warranty_end - provision.warranty_start
  elapsed    = min(asOf, warranty_end) - warranty_start
  shouldHaveReleased = estimated_provision × elapsed/termDays
  delta = shouldHaveReleased − provision.released_to_date
  if delta > 0: provision.released_to_date += delta ; emit warranty.provision.released(delta)
  provision.outstanding = estimated_provision − released_to_date − consumed_by_claims
  if asOf >= warranty_end and outstanding≈0: provision.status='expired'

onWarrantyClaim(serial, cost):
  p = warranty_provision[serial]; p.consumed_by_claims += cost
  recompute p.outstanding ; emit warranty.claim.raised
  if p.consumed_by_claims >= p.estimated_provision: p.status='claimed_out'

consolidatedExposure(entity, period):            // for consolidated reporting
  = Σ warranty_provision.outstanding WHERE entity=entity AND status='open' (translated to USD)
```

**Retroactive backfill:** the register is fully reconstructable — replay **all historical `activation` records** through `openWarrantyProvision` (with the warranty_rate effective at each activation date) and roll `releaseSchedule` forward to today. This yields the historical and current provision/exposure without manual entry. A nightly job advances `releaseSchedule` for all open provisions and emits the period's release.

*(GAAP note: the provision is an estimate under a versioned, audited assumption (`warranty_rate`); release is straight-line by default — a curve can replace it later without schema change. Recognition into the balance sheet is downstream, matched in the period of revenue recognition; Conduit owns the exposure and the release cycle.)*

---

## §Stock operations (cycle count, transfer, write-off — maker-checker)

On-hand is the immutable sum of `stock_movement`; every operational change posts a signed movement, never an edit. The mutating operations below all run **maker-checker** (the requester cannot approve their own; approver holds the gated permission — doc 05) and emit immutable events + `audit_log`.

```
cycleCount(location, lines[counted_qty], counter):
  snapshot system_qty per variant; variance = counted − system
  stock_count.status = 'pending_approval'
  on approve(by ≠ counter, holds approve:stock_adjustment):
     for each variance≠0: stock_movement(type='count_correction', qty=variance, reason, ref=count)
     post inventory write-up/down to ledger (INV vs COS_CLEARING/adjustment account)
     emit inventory.count.posted

transfer(from_loc, to_loc, variant, qty, requester):
  require approve if policy (cross-entity always → intercompany, doc §Intercompany)
  on approve: stock_movement(type='transfer_out', -qty, from_loc) + mark in_transit
  on receipt:  stock_movement(type='transfer_in', +qty, to_loc); serials move location
  cross-entity → transfer-priced legs + linked ledger transfers

adjustment(kind ∈ {write_off,damage,shrinkage,found,correction,quarantine}, variant/serials, qty, requester):
  stock_adjustment.status='pending_approval'
  on approve(by ≠ requester, holds approve:stock_adjustment):
     stock_movement(type='write_off'|'adjustment', signed qty, reason, evidence)
     serialised: serial_unit.status → 'quarantined'|'scrapped'; unit_lifecycle_event
     ledger: inventory write-down at the units' specific batch landed cost (DR loss/COS_CLEARING, CR INV)
     emit inventory.adjusted | inventory.write_off
```
Invariants: maker ≠ checker on every adjustment/count/transfer-approval; every change is reason-coded, evidence-attachable, immutable, and reconstructable from movements + events + ledger (controls/SOX, doc 01 §3b). Damaged/written-off serials never re-enter sellable stock without an audited `found`/refurbish path.
