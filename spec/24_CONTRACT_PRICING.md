# 24 — Contract & Volume-Tiered Pricing (ADLP, re-stated)

> **✅ Slice 1 implemented (M-Pricing §10.1).** `price_agreement` (+ `price_agreement_customer` M:N) + tier bands
> (evolving `price_rule` with `price_agreement_id` + `up_to_qty`) + governed `product_class` + the open_list
> back-fill (V1_0_48 — nothing regresses, legacy rules resolve as open_list via a LEFT JOIN). Agreement-aware
> resolution (`PricingService.resolve`: customer_set ≻ segment ≻ sector ≻ open_list; per-order band by qty),
> threaded through `QuoteService` (optional `customer`) and `OrderService` (the buyer's `sold_to`, agreement
> stamped on `order_line`). The governed **price-tier request** (`AgreementService.request`/`activate`,
> maker-checker proposer ≠ approver; `POST /pricing/agreements` + `…/{id}/activate`; events
> `pricing.agreement.requested|activated`). `ContractPricingSuite` + `PricingServiceSpec` green; the existing
> pricing/order/deal-desk behaviour is unchanged.

> **✅ Slice 2 implemented (§10.2 / §4(b)).** `cumulative_prospective`: the base band improves going forward as the
> agreement's running cumulative qualifying volume crosses a threshold. `ContractYear` (rolling 12mo from valid_from,
> anniversary reset, DERIVED — `ContractYearSpec`); `ContractVolumeRepo.priorCumulativeQualifying` (a projection over
> the order stream, **group-aggregated across the whole customer set**, qualifying product_class, no stored counter);
> `TierResolver` merges per-order + the cumulative-unlocked band, entry tier from zero. `ContractCumulativeSuite` green.

> **✅ Slice 3 implemented (§10.3 / §5 — the "must be perfect" piece).** `cumulative_retrospective` = ASC-606 variable
> consideration. The order invoices at the FIRM entry tier (`TierResolver` picks tier 1 for retrospective); the rebate
> to the volume-achieved tier is a reproducible **earned projection** (`RebateEngine` pure math + `RebateService.
> earnedRebate`). **ACCRUE ≠ APPLY**: `accrue` brings `REBATE_ACCRUAL:<entity>` (new ledger role 19, contra-revenue)
> up to earned via an idempotent true-up (DR REVENUE / CR REBATE_ACCRUAL, through the `Journal` → TB + gl_entry);
> **settlement** is a SEPARATE maker-checker (`proposeSettlement`/`approveSettlement`, proposer ≠ approver), idempotent
> (deterministic id), drawing the accrual down (DR REBATE_ACCRUAL / CR BANK) — no code path settles unilaterally.
> `rebate_settlement` table; events `pricing.rebate.accrued|settled`; `CTRL-REBATE-ACCRUAL` (over-settlement guard).
> `RebateEngineSpec` (ScalaCheck: non-negative, monotonic, reproducible) + `RebateSuite` prove all six §5.7 properties.
> *Remaining: §3 hard no-typed-prices enforcement at placement; §5.3 recognition-net-of-expected-rebate (H6Q expected
> tier) — the earned/realised path is built, the in-year expected-revenue smoothing is the follow-on; slice 4 below.*

**Status:** design spec — slices 1–3 built (above); the rest is **spec only** (build per the milestone in §10). This deep-dive
re-states how pricing works in Conduit and **supersedes the "agent types a price → CEO approves the number"
reading** of ADLP in doc 04 §Pricing/§ADLP and doc 08 S14/S16. It is grounded in the existing
`price_rule`/`PricingService`/maker-checker machinery (M3) — this is an **evolution of that spine, not a parallel
mechanism**.

> **The principle (from the product owner):** *Nobody types a price.* Every sellable price exists only as an
> **authorized, governed tier** inside a **price agreement** — a contract with **clear validity windows and terms**,
> applicable to **one or more customers**, frequently structured as **volume tiers** (discounts/prices gated on
> hitting volume thresholds). There are **no one-off "special prices"** that can be typed onto an order. A salesperson
> who needs a price that doesn't yet exist **requests a new tier** (the workflow formerly called an "ADLP exception"),
> which is governed (maker-checker → admin/CEO) and, once active, is a **reusable agreement** valid for that
> customer and terms. Crucially, the **full contract structure (validity, tiers, volume thresholds) must propagate to
> every downstream stage** — AR, revenue, sales, commission and the ledgers must all reflect it.

---

## 1. What exists today vs. what this adds

**Today (M3):** `price_rule` (surface · variant · channel · market · entity · currency · `authorised_price` ·
`max_discount_pct` · **single `min_qty`** · effective window · status draft/active/superseded · maker-checker
`approved_by`). `PricingService.resolve` picks the best rule for a line's context; `order_line` records
`price_rule_id` (provenance) + `adlp_category`. A requested out-of-band price is flagged `exception` and the order
is held `pending_ceo`.

**Three gaps vs. the principle:**
1. **No customer dimension.** `price_rule` scopes by channel/market/entity — *not by party/account*. "A price valid
   for this specific customer (or these customers)" is **not expressible** today.
2. **No tier ladder / contract.** `min_qty` gives a single break per rule (stackable into per-order breaks), but
   there is **no agreement container** (validity + terms + a customer set) and **no cumulative-volume** concept.
3. **No downstream propagation of volume structure.** Revenue/AR/commission price each line at its own number;
   there is **no variable-consideration / volume-rebate** handling, so a retrospective volume discount would not be
   reflected in the ledgers.

**This doc adds:** a **`price_agreement`** container (validity, terms, multi-customer scope) whose **tiers** are
volume bands (evolving `price_rule`), **cumulative-volume tracking**, the **no-typed-prices enforcement**, the
**tier-request workflow** (the renamed exception), and the **ASC-606 propagation** so every ledger reflects the
contract (§5 — the substantial part).

---

## 2. The model

> **Modelling discipline (the Conduit grain): persist immutable FACTS; DERIVE interpretations.** The persisted core
> holds only what you cannot compute — **validity windows as begin/end timestamps**, the **governed economic
> definitions** (tier ladder, rebate scheme), the **customer links**, and (already) the **immutable order/volume/
> event stream**. Everything interpretive — *lifecycle* (active/expiring/renewed/lapsed), the *contract year N*,
> *renewal / renewal-rate / retention*, the *rebate accrual* — is a **derived projection** over those facts, exactly
> like fiscal-period assignment, coverage and VAT-exposure already are (doc 14 §2). No stored status field to
> reconcile; the journal never goes stale. Rich states (badges, "expiring soon", renewal dashboards) live in the UI,
> computed from the timestamps.

```
price_agreement                          -- the contract / governed tier set (FACTS only)
  id, name, surface(customer|inter_entity), currency,
  scope: applies_to ∈ { open_list | customer_set | segment | sector },  -- "open_list" = the standard ADLP everyone gets
  base_volume_basis ∈ { per_order | cumulative_prospective },    -- how the BASE tier price is picked (always knowable at order time)
  valid_from, valid_to (timestamps),                             -- THE validity window — begin/end. lifecycle is DERIVED from these + successors, never stored.
  terms (JSONB: min_commitment_units, term_months/renewal_type as descriptive INPUTS — expectations, not reconciled state),
  status(draft|active|superseded), version,                      -- governance state of the row itself (maker-checker), not a "contract lifecycle"
  proposed_by, approved_by, approved_at                          -- maker-checker (doc 05 §4)
  -- NOTE: no `lifecycle`, no `commencement_date` separate from valid_from, no stored renewal status. A "renewal" is
  --       simply the next agreement whose valid_from continues this customer+products; the link is DERIVED (or, if
  --       the business wants certainty, optionally recorded as a `renews_from` fact at creation — but never a status).

price_agreement_customer  (M:N)          -- which parties this agreement is valid for (group aggregation, §4)
  agreement_id → price_agreement, party_id → party

price_tier  (the base price bands; evolves price_rule)
  agreement_id → price_agreement, product_variant_id,
  from_qty (band threshold), up_to_qty (NULL = open-ended),       -- the ladder: [1–99]@X, [100–499]@Y, [500+]@Z
  price (ex-VAT)  OR  discount_pct (off the variant list)

rebate_scheme  (§4.4 — arbitrary time-bound rebates, attachable to an agreement or a tier)
  id, agreement_id → price_agreement,  price_tier_id (NULL = agreement-wide),
  name,
  window: valid_from, valid_to (timestamps)  + optional recurrence(anchor, n_months),  -- arbitrary time-binding AS TIMESTAMPS
                                                                                       -- ("contract year" = a window anchored to the agreement's valid_from; resolved, not enumerated)
  basis  ∈ { volume | spend | growth_vs_prior | flat },          -- a genuine computation difference, so a real field
  unit ∈ { unit | currency },                                    -- volume counted per-UNIT (chargers) or by spend
  qualifying_filter (JSONB: product_class[] / family[] / variant[]),  -- §4.5 — WHICH products' sales TRIGGER the tier (e.g. chargers only)
  applies_filter    (JSONB: product_class[] / family[] / variant[]),  -- §4.5 — WHICH products RECEIVE the rebate/price (often = qualifying)
  treatment ∈ { prospective | retrospective },                   -- retrospective ⇒ the §5 accrue/settle engine
  ladder (JSONB: [{from_threshold, value}])                      -- per-threshold rebate (% or per-unit), in `unit`s
```

> `product_variant` (and/or `product_family`) gains a governed **`product_class`** (charger | accessory | cable |
> spare | bundle) — the dimension the filters use. (Today only `is_serialised` roughly separates chargers from
> accessories; a proper class is needed — §4.5.)

> The **volume-rebate model of §4–§5 is one `rebate_scheme`** (basis=volume, a contract-year-anchored window,
> treatment=retrospective). A contract can carry several at once — annual volume rebate **+** a fixed-window promo
> **+** a growth kicker — each over **its own begin/end window**, evaluated by the same accrue/settle engine (§5).
> Note the window is **timestamps**, not an enumerated "year type" the engine must interpret: a UI convenience picks
> a `contract_year`/`rolling(n)` shape and *resolves it to begin/end* on creation.

`price_rule` is **retained as the tier row** — the cleanest migration is: add `price_agreement_id` to `price_rule`,
let `min_qty`→band `from_qty` (+ an `up_to_qty`), and move the customer scope onto the agreement. An "open-list"
agreement reproduces today's behaviour exactly (year-1 UK standard list), so **nothing regresses**.

**Most-specific wins** (resolution order): a `customer_set` agreement naming the party beats a `segment` agreement
beats the `open_list`; within the chosen agreement, the band is picked by the order's volume position (§4). All
effective-dated (`status <> 'draft'` + the as-of window — the house pattern).

---

## 3. No typed prices — enforcement

- **Capture (doc 08 S14, doc 20 D-order):** an order line is **`(sku, qty)`** — *no price or discount field*. The UI
  shows the resolved tier price and the active band; if a customer has tiered pricing, it shows the ladder and the
  customer's current volume position. The "discount slider bounded by `max_discount_pct`" in doc 08 S14 is
  **removed**.
- **Server enforcement (defense in depth):** placement **rejects** any line whose price ≠ the resolved authorized
  tier price for its (variant, customer, channel, market, qty, as-of, cumulative-position) context. A hand-crafted
  API call cannot inject a price. Introducing a non-tier price requires the `price_agreement:create` (propose)
  right (doc 05); agents don't have it.
- **The only path to a new price is §6** (request a tier). This makes pricing **fully governed and auditable**:
  every price on every order traces to an approved agreement + band.

---

## 4. Volume tiers — base band vs rebate scheme (the crux)

Two distinct mechanics, deliberately separated so the **base price is always knowable at order time** and all
*retrospective* economics live in an explicit, auditable **rebate scheme**:

**(a) Base tier selection** — `price_agreement.base_volume_basis`:

| basis | band chosen by | downstream effect |
|---|---|---|
| **`per_order`** | this order's line qty | none beyond the line price — the simplest case (today's stacked `min_qty` rules). |
| **`cumulative_prospective`** | the customer's **running volume** over the contract year; crossing a threshold improves the price **going forward only** | future orders price at the better band; no retro adjustment. Needs cumulative tracking (§4 below) but **no rebate**. |

**(b) Rebate schemes** (§4.4) — everything *retrospective* (the better price applies to volume already invoiced → a
rebate) is a `rebate_scheme` with `treatment = retrospective`, evaluated by the **accrue/settle engine (§5)**. This
is the **variable-consideration / ASC-606** case (the Octopus annual volume rebate). Keeping it out of base-band
selection means an order's invoice price is never provisional — the rebate is a separate, explicit accrual.

**Cumulative tracking:** a running total of **qualifying** volume (the qualifying product class — chargers, §4.5;
ordered / dispatched — configurable, default **dispatched**, to align with revenue recognition) at the grain
**(agreement, qualifying_class, contract_year)** — note
**per contract year, with an annual reset** (real contracts tier on *annual* cumulative volume), and **aggregated
across the agreement's whole customer set, not per buying party**. So resolution knows the current band and how
close the next threshold is, and the **additivity of successive orders moves the customer up a tier** (order #2 can
price cheaper than order #1). This ties naturally to **H6Q** (the customer's committed/forecast annual volume is the
expected final tier — see §5.2).

> **Group aggregation.** A single agreement can name many buyers (the M:N `price_agreement_customer`) — e.g. a parent
> and all its group/authorised-agent companies. Cumulative volume sums **across all of them under the one agreement**,
> so the tier is reached at the **agreement level**. `contract_volume` is therefore keyed by agreement (+ qualifying
> class + contract_year, §4.5), not by the individual ordering party.

> **Worked example — modelled on the Octopus Energy supply agreement** (the real shape; negotiated rates omitted for
> confidentiality). Three products with list/RRP **£575 / £610 / £650**; each has a **six-tier cumulative-annual-
> volume** ladder stepping the unit price *down* as the year's volume grows (roughly −24% at tier 1 to −32% at tier 6
> off list). Octopus + its group companies (the "Authorised Agent" clause) **all buy under the one agreement**, so
> their orders **aggregate** toward the same annual tier; the ladder **resets each contract year**. So if the same
> buyer places the same order twice and the second crosses a band, the better tier applies — **prospectively** (next
> orders cheaper) or **retrospectively** (a year-end rebate trues-up *all* the year's units) per the contract's
> charges clause — which is the §5 ledger split.

### 4.4 Generalised rebate schemes (arbitrary time-bound)
A `rebate_scheme` (§2) is the **general, reusable concept for any time-bound rebate** — it is not limited to the
annual volume rebate. One agreement can carry several, each with **its own window** (`contract_year` |
`calendar_year` | `fixed(from,to)` | `rolling(n_months)`), **its own basis** (`volume` | `spend` | `growth_vs_prior`
| `flat`), and attached to the **whole agreement or a specific tier/product**:
- *Volume rebate* — the Octopus case (basis=volume, window=contract_year, retrospective).
- *Promo* — a flat % over a fixed window (basis=flat, window=fixed(Q1), prospective or retrospective).
- *Growth kicker* — basis=growth_vs_prior over the prior contract year.

Every scheme is evaluated **independently by the same accrue/settle engine (§5)** over its own window — so "map an
arbitrary time-bound rebate" = create a `rebate_scheme`; "make it part of a contract" = attach it to that
agreement; "a standing tier rebate" = attach it to the `open_list` agreement. The engine's correctness properties
(§5.7) hold per scheme.

### 4.5 What TRIGGERS a tier vs what RECEIVES it — per product class (per-unit)
Rebates are normally **per-unit**, and **the sale of EV chargers triggers the tiers — not the sale of accessories**;
accessories run a *different regimen*. So a scheme separates two product sets (both expressed over a governed
**`product_class`** = charger | accessory | cable | spare | bundle):
- **`qualifying_filter`** — whose **units accumulate toward the tier** (e.g. `product_class = charger`). The
  cumulative count (§4) is over the **qualifying** set only; accessory lines do **not** advance the charger tier.
- **`applies_filter`** — which products **receive** the resulting price/rebate. Often the same (chargers), but it
  can differ ("buy N chargers → accessories discounted" = qualifying:charger, applies:accessory).

Patterns this expresses cleanly:
- **Charger volume rebate** (Octopus, HK00552): qualifying=charger, applies=charger, basis=volume(unit),
  retrospective, contract-year window — chargers accrue the tier; the per-unit rebate applies to chargers.
- **Accessories' separate regimen** (HK00547 — covers, holsters, clamps, cables, brackets, shells, looms): their own
  `rebate_scheme` (or just a flat `price_tier`), with their own (or no) volume basis — **independent of the charger
  tier**. Accessory volume neither advances nor benefits from the charger ladder unless a scheme says so.

`contract_volume` (§4) is therefore keyed by the **qualifying class**: `(agreement, qualifying_class, contract_year)`
— count chargers, in units. This keeps "what counts" explicit and auditable rather than implied by `is_serialised`.

---

## 5. Downstream propagation — every ledger reflects the contract (the substantial part)

The product owner's requirement: *"AR, revenue, sales and all the other ledgers must correctly reflect the full
structure of a contract"* — and this **must be perfect** (it's critical infrastructure). For `per_order` /
`cumulative_prospective` it's automatic (the line price is the band price; nothing retro). For
**`cumulative_retrospective`** it is **ASC 606 variable consideration** and turns on **one central distinction:**

> **ACCRUE (calculate) ≠ APPLY (settle).** The system **continuously tracks and calculates** the rebate owed —
> per product, per tier, against the contract year — and reflects that as an **accrued liability on the immutable
> ledger**. It does **NOT unilaterally apply** (pay/credit) it. Applying the rebate is a **separate, discrete,
> governed act** (year-end or an agreed milestone, maker-checker) that draws the accrual down. Calculation is
> automatic and reproducible; settlement is deliberate and never automatic. Keeping these two apart is the whole
> game — conflating them is the classic way rebate accounting goes wrong.

### 5.1 The contract year (rolling, anchored to commencement)
The cumulative window is **12 months from the agreement's `valid_from`** (its commencement), rolling — *not* a
calendar or fiscal year. Year *N* = `[valid_from + N·12mo, valid_from + (N+1)·12mo)`; cumulative volume and the
rebate accrual are **scoped to the current contract year and reset at each anniversary** (no carry-over across
years). Each agreement has its own anchor, so many contracts run on different year boundaries simultaneously.
`contract_year` is **derived** from `valid_from` + the order's `occurred_at` (a UTC-instant re-projection, exactly
like fiscal-period assignment, doc 14 §2 — never baked into rows). The fiscal/accounting period (doc 14) is a *separate* axis: a single
contract year spans several accounting periods, and the accrual is carried/closed within each (§5.6).

### 5.2 Accrual — track & CALCULATE, per tier (continuous, reproducible)
The accrued rebate is a **deterministic projection** over the contract year's immutable order/dispatch stream + the
agreement's tier ladder — recomputable to the penny by replay (this is what makes "perfect" testable). It is computed
**per product, per achieved tier**, not as a blended number:

- For each product on the agreement, take the **cumulative qualifying volume** in the current contract year →
  the **achieved tier** `t*` and its unit price `p(t*)`.
- The **earned/retrospective accrual** = `Σ over the year's units of ( invoiced_unit_price − p(t*) )`, evaluated
  per product. As volume climbs and `t*` improves, the accrual **recomputes upward** (more units now entitled to a
  lower price). Because it's a pure function of (units, invoiced prices, tier ladder, contract year), redelivery /
  replay yields the identical figure.
- This **earned** accrual (what's actually owed given volume to date) is distinct from the **expected** accrual used
  for revenue recognition (§5.3) — the engine carries both: *expected* drives in-year revenue, *earned* is the
  realised liability, and they converge as the year completes.

### 5.3 Revenue recognition net of the expected rebate (ASC 606)
At each sale the recognition service (M13 `RevenueRecognitionService`) recognises revenue **net of the expected
rebate** — at the *expected* final-tier price, not the current band — posting the difference to `REBATE_ACCRUAL`.
The **expected final tier** comes from the customer's **committed/forecast annual volume (H6Q** — commitments,
time-fences, M11; `terms.min_commitment_units` sets a floor), **constrained** per ASC 606 §56–58 (recognise only
consideration highly likely not to reverse; conservative tier when uncertain). As actual volume lands, the
**earned** accrual (§5.2) trues the **expected** accrual up/down — reusing the **M5 commission true-up pattern**
(posted entries never reopened; the delta is a current-period adjustment) on the revenue side.

### 5.4 Application / settlement — discrete, governed, NOT unilateral
Settling the rebate (the customer actually receives it) is a **separate event**, typically at the **contract-year
boundary** or an agreed milestone:
- It is **maker-checker governed** (finance proposes the year-end rebate statement from the earned accrual; an
  approver authorises) — never auto-applied.
- It **draws down `REBATE_ACCRUAL`** and issues the settlement instrument: a **credit note** (doc-17 machinery →
  reduces AR / cash waterfall) or a **rebate payment**.
- It is **idempotent** (a deterministic settlement id per (agreement, contract_year, milestone)) so a re-run never
  double-credits — the same discipline as the rest of the ledger.
- The **earned accrual statement** (per product/tier, for the contract year) is the auditable basis the settlement
  is computed from; the customer-facing rebate statement is a generated document (doc 17).

### 5.5 The ledger
- New account role **`REBATE_ACCRUAL:<entity>`** (contra-revenue / customer-rebate liability) on the immutable
  TigerBeetle ledger, alongside per-entity Revenue/AR/VAT/COGS, mirrored into `gl_entry` (M13b). Recognition splits
  DR AR / CR Revenue (expected net) / CR `REBATE_ACCRUAL` (expected rebate); true-up moves the accrual; **settlement**
  DRs `REBATE_ACCRUAL` / CRs AR-or-Bank. Per-event reversal (M13b) extends cleanly — a cancellation recalls the
  rebate leg too.
- **VAT** is on the **net** consideration; a retrospective rebate reduces the taxable base (credit note with VAT) —
  confirm per jurisdiction via the tax engine (doc 16).

### 5.6 AR, commission, documents, period close
- **AR / cash:** invoices post at the in-year price; the year-end rebate is a credit note or payment in AR-aging / the
  cash waterfall.
- **Commission (M5):** basis = gross margin at the **contract** price; the rebate true-up is one more input to the
  existing quarterly true-up.
- **Documents (doc 17):** the rebate statement + credit note cite the agreement, contract year, per-tier earned
  figures.
- **Period close / reconciliation (M13b):** at each accounting-period close the open `REBATE_ACCRUAL` is a reconciled
  balance; **`CTRL-REBATE-ACCRUAL`** ties the earned-accrual projection to the ledger account, and a conservation
  check asserts `Σ settled + Σ outstanding == Σ earned` for the contract year.

### 5.7 Correctness properties (because it must be perfect)
Tested as ScalaCheck properties + reconciliation controls, in the M1/M13b style:
1. **Reproducible** — replaying a contract year's orders yields the identical earned accrual (deterministic projection).
2. **Conservation** — `Σ rebate settled + Σ outstanding accrual == Σ earned rebate` per (agreement, contract_year).
3. **Year-boundary integrity** — volume + accrual reset at the anniversary; no unit counts toward two contract years.
4. **Idempotent settlement** — a settlement applied twice credits once.
5. **Ledger tie** — the earned-accrual projection equals the `REBATE_ACCRUAL` ledger balance (the `gl_vs_*` discipline).
6. **No unilateral application** — no code path settles/credits a rebate without the maker-checker step.

### 5.8 Term, renewal-rate & sector — all DERIVED (not stored state)
Contract-lifecycle reporting matters for big accounts (Octopus Group) — but it is **computed from the validity
timestamps + the agreement history, not maintained as persisted status**:
- **Lifecycle & term.** `valid_from/valid_to` are the only stored dates; `term_months`/`renewal_type` are
  descriptive **inputs** in `terms`. Whether an agreement is *active / expiring-soon / lapsed* is a pure function of
  `now` vs `valid_to` (± a notice window) — **derived on read**, so it can never be a stale "active" row that's
  actually expired.
- **Renewal & renewal-rate.** A "renewal" is just the **next agreement whose `valid_from` continues the same
  customer+products** — derivable from the immutable agreement set (optionally pinned by a recorded `renews_from`
  fact at creation, never a status). **Renewal rate** is then a reproducible analytic over that set — *logo
  retention* (renewed ÷ due) and *net revenue retention* (successor value ÷ predecessor) — in a window, **by
  sector**. No parallel store; feeds Horizons (doc 21) + H6Q forward demand (doc 12). The desk's "renewals worklist"
  is this projection, not a table of statuses.
- **Sector** (the one genuine *fact* here). `party` gains a governed **`sector`** taxonomy (energy, automotive, …) —
  coarser than the existing `party.segment` (segment = finer sub-class within a sector/channel). It persists **on the
  party** (you can't derive it) and is a join-away dimension for: agreement/rebate **scope** (`applies_to = sector`),
  **H6Q rollups** (add a `sector` level to the coverage hierarchy, doc 12), and **reporting** breakdowns. The pricing
  journal never encodes sector — it references parties, and sector is read through the join.

---

## 6. The exception, re-stated: a governed price-tier request

The "ADLP exception" is **a workflow for a salesperson to request a new price tier valid against a specific
customer and terms** — not an order-scoped number.

1. **Request.** From a deal/order the agent can't price within existing agreements, they file a **price-tier
   request**: target customer(s), variant(s), proposed **bands** (volume ladder), validity window, terms
   (commitment), `volume_basis`, justification + volume expectation (the H6Q P-denomination). This is a **draft
   `price_agreement`** (+ tiers + customer set).
2. **Govern (maker-checker, doc 05 §4).** `proposed_by` (agent) ≠ `approved_by`. Admin creates directly, or
   CEO/CFO approves the draft. Approval = activation (effective-dated; supersedes any overlapping agreement). This
   **reuses the existing `price_rule` activation governance** (doc 20 D4) — the "exception decision" *is* the tier
   activation.
3. **Order linkage.** The triggering order references the **draft** agreement and is held (the existing
   `pending_ceo` hold); on activation it releases and **re-quotes against the now-active tier** (commission un-zeroes,
   as today). Thereafter the agreement is a **reusable contract** for that customer — the next order just resolves it.
4. **Audit.** The request → decision → activation is one governed, append-only chain (doc 05 §5) with an
   audit-reference, like every other maker-checker action.

This replaces `adlp.exception.requested`-with-a-number; the artifact produced is a **reusable, governed agreement**,
never a one-order patch.

---

## 7. Data-model deltas (summary for the migration)

- `price_agreement` (new) — stores **facts only**: `valid_from/valid_to` (timestamps), scope, `base_volume_basis`,
  `terms` JSONB (term_months/renewal_type as descriptive inputs), governance. **No `lifecycle`/renewal status column**
  — lifecycle, contract-year and renewal-rate are derived projections. `renews_from` optional (a recorded link, not a
  status). `price_agreement_customer` (new M:N).
- `rebate_scheme` (new) — arbitrary time-bound rebates: **`valid_from/valid_to` timestamps** (+ optional recurrence)
  / basis / `unit` / treatment / ladder + **`qualifying_filter` & `applies_filter`** (§4.5 — what triggers the tier
  vs what receives it). The §4–5 volume rebate is one row.
- `product_variant` (and/or `product_family`) → new governed **`product_class`** (charger | accessory | cable | spare
  | bundle) — the dimension the qualifying/applies filters and `contract_volume` use (today only `is_serialised`
  roughly separates them).
- `contract_volume` is keyed by the **qualifying class**: `(agreement, qualifying_class, contract_year)`, group-aggregated, annual reset.
- `party` → gains a governed **`sector`** taxonomy (energy/automotive/…), coarser than the existing `segment`; a
  `sector` reference table.
- `price_rule` → gains `price_agreement_id` (FK), `up_to_qty` (band ceiling); `min_qty` reread as band `from_qty`.
  Existing single-list rules become tiers of an `open_list` agreement (back-fill) so nothing regresses.
- `order_line` → already has `price_rule_id`; add `price_agreement_id` for the contract reference; record the
  resolved band + the recognised-net vs list for rebate trace.
- `contract_volume` (new, for cumulative bases) — group-aggregated across the agreement's customer set, **derived**
  as a reproducible projection (not authoritative state); `contract_year` is **derived** from the agreement's
  `valid_from` + `occurred_at` (not stored) — a rolling-12mo re-projection.
- Ledger: `REBATE_ACCRUAL` account role; the **earned-rebate** is a reproducible **projection** (per agreement /
  product / tier / contract_year), reconciled by `CTRL-REBATE-ACCRUAL`; `rebate_settlement` records discrete,
  governed, idempotent settlements (credit note / payment) that draw it down.
- Events: `pricing.agreement.requested`, `pricing.agreement.activated`, `pricing.rebate.accrued`,
  `pricing.rebate.trued_up`, **`pricing.rebate.settled`** (the discrete, maker-checker settlement — separate from
  accrual); envelope per doc 03.

---

## 8. Spec reconciliations (update when this is built)

- **doc 04 §Pricing/§ADLP** — replace the "type a price → exception" description with this tier-bound model + the
  cumulative/variable-consideration logic.
- **doc 02 §pricing** — the `price_agreement`/`price_tier`/`contract_volume` schema + `price_rule` deltas.
- **doc 06** — `/pricing/agreements` (CRUD + governance), `/pricing/quote` extended to take customer + return the
  ladder + band + cumulative position; the tier-request endpoints; remove any price/discount input on `/orders`.
- **doc 08 S14/S16** — order capture has **no price field** (sku+qty, tier shown); "exception" screen becomes the
  **tier-request** form.
- **doc 20 D3–D6** — desk pricing governance manages **agreements** (validity, customer scope, bands, rebate
  schemes, term/renewal) + approves tier requests + a **renewals worklist**; the layer wall (inter-entity) is unchanged.
- **doc 11 CRM** — `party.sector`; the agreement term/renewal lifecycle + renewal-rate analytic live alongside the
  party/account model.
- **doc 12 H6Q** — add a **`sector`** level to the coverage rollup hierarchy (one shared taxonomy with pricing).
- **doc 21 reporting / Horizons** — revenue / rebate / **renewal-rate by sector** breakdowns.
- **CLAUDE.md §8** — add a reconciliation: *pricing is contract/tier-bound; no typed prices; the ADLP "exception" is
  a governed price-tier request; volume tiers (incl. retrospective rebates) propagate to revenue/AR/commission/ledger.*
- **doc 14 §5 (M13b)** — the rebate accrual joins the reconciled balances + a new control.

---

## 9. Open sub-decisions (resolve at build time)

1. **Year-1 scope of `volume_basis`.** `per_order` + `cumulative_prospective` are straightforward; `cumulative_
   retrospective` (the rebate/variable-consideration path) is the heavy one. Recommend **modelling all three but
   shipping retrospective once the H6Q-driven estimate (§5.2) is wired** — it's the only one needing the accrual +
   true-up. (UK year-1 contracts likely start `per_order`/prospective.)
2. **Qualifying-volume event** for cumulative tracking — **dispatched** (aligns with revenue recognition;
   recommended) vs ordered vs delivered.
3. **Rebate estimation** — `expected_value` vs `most_likely` (ASC 606); default **most-likely tier from the H6Q
   commitment**, constrained.
4. **VAT on rebates** — confirm per-jurisdiction whether a retrospective rebate reduces the taxable base (credit
   note with VAT) via the tax engine (doc 16).
5. **Octopus agreement specifics** — extract the exact **band thresholds** (the cumulative-annual-volume breakpoints)
   and the **prospective-vs-retrospective** clause from Schedule 3 (its table cells didn't survive text extraction);
   that clause decides whether this contract needs the §5 rebate-accrual path or just per-tier prospective pricing.
   Whichever, model it as one `price_agreement` (open the agreement to the Octopus Group via `price_agreement_customer`).
6. **Sector taxonomy** — the governed `sector` value set (energy/automotive/…) and its relationship to the existing
   `segment` (sector = coarse industry; segment = finer sub-class). Confirm whether H6Q's existing `segment` rollup
   level should sit under a new `sector` level.
7. **Renewal-rate definition** — logo retention vs net-revenue retention; the "due for renewal" denominator; the
   window. (Reporting concern — doc 21.)
8. **`product_class` taxonomy** — the governed value set (charger | accessory | cable | spare | bundle …) and where
   it lives (variant vs family); confirm chargers are the default tier-qualifying class and accessories' regimen
   (separate scheme / flat / excluded). Replaces the `is_serialised` proxy (§4.5). *(Real classification today, per
   the `precision` tool: charger = SKU containing `hv3` — e.g. `HV3PROAA…`; `-DEMO` excluded. And precision confirms
   **no system computes these volume rebates today** — it does only the charger classification — so this engine is
   genuinely net-new, doc 18 "Real-system ground truth".)*

---

## 10. Milestone & build order (spec-only now)

A new milestone — **M-Pricing: Contract & volume-tiered pricing** (touches M3 pricing, M4 order capture, M5
commission, M13 revenue, M13b close). Build order when greenlit:
1. `price_agreement` + customer scope + tier ladder (evolve `price_rule`); back-fill the open-list; **resolution by
   customer + per-order band**; remove typed prices + server enforcement; the **tier-request workflow** (reuse the
   maker-checker activation). *(Closes the "no typed prices / exception = tier request" principle.)*
2. **Cumulative tracking** (`contract_volume`) + `cumulative_prospective` resolution.
3. **`cumulative_retrospective`** (the critical, get-it-perfect piece): the rolling contract-year window (§5.1); the
   reproducible **earned-rebate accrual projection** per product/tier (§5.2) + `REBATE_ACCRUAL` ledger account;
   H6Q-driven expected-tier estimate + recognition net-of-rebate + true-up (§5.3); the **separate, governed,
   idempotent settlement** that draws the accrual down (§5.4, NOT auto-applied); `CTRL-REBATE-ACCRUAL` + the §5.7
   property suite (reproducibility, conservation, year-boundary, idempotent settlement, ledger tie); AR/commission
   propagation.
4. **Generalised `rebate_scheme`** (arbitrary windows/bases beyond the contract-year volume rebate) + **term/renewal
   lifecycle** + the **renewal-rate analytic**; **`party.sector`** wired into agreement scope, the H6Q rollup, and
   reporting breakdowns.
5. Desk (agreements + rebate-scheme governance, renewals worklist) + companion (tier-request form, ladder display) —
   per the design pass (doc 22/23).

> **Not started.** This is the design of record; implementation is sequenced above and begins only when greenlit.
