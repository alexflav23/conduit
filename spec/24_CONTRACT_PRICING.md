# 24 — Contract & Volume-Tiered Pricing (ADLP, re-stated)

**Status:** design spec — **spec only, no implementation yet** (build per the milestone in §10). This deep-dive
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

```
price_agreement                          -- the contract / governed tier set
  id, name, surface(customer|inter_entity), currency,
  scope: applies_to ∈ { open_list | customer_set | segment },   -- "open_list" = the standard ADLP everyone gets
  volume_basis ∈ { per_order | cumulative_prospective | cumulative_retrospective },   -- §4
  rebate_method ∈ { none | expected_value | most_likely },       -- ASC 606 estimation (§5) when retrospective
  effective_from, effective_to,                                  -- validity window (immutable; supersession, not edit)
  terms (JSONB: min_commitment_units, period, notes),
  status(draft|active|superseded), version,
  proposed_by, approved_by, approved_at                          -- maker-checker (doc 05 §4)

price_agreement_customer  (M:N)          -- which parties this agreement is valid for
  agreement_id → price_agreement, party_id → party
  -- empty set + applies_to=open_list ⇒ the standard list (today's behaviour); a non-empty set ⇒ a customer contract

price_tier  (the volume bands; evolves price_rule)
  agreement_id → price_agreement, product_variant_id,
  from_qty (band threshold), up_to_qty (NULL = open-ended),       -- the ladder: [1–99]@X, [100–499]@Y, [500+]@Z
  price (ex-VAT)  OR  discount_pct (off the variant list),        -- one of; price wins if both given
  -- (carries forward price_rule's currency/tax_regime/min_qty→from_qty/version/effective semantics)
```

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

## 4. Volume tiers — per-order vs cumulative (the crux)

`price_agreement.volume_basis` decides how the band is selected and is the hinge for downstream accounting:

| basis | band chosen by | downstream effect |
|---|---|---|
| **`per_order`** | this order's line qty | none beyond the line price — the simplest case (today's stacked `min_qty` rules). |
| **`cumulative_prospective`** | the customer's **running volume** over the agreement period; crossing a threshold improves the price **going forward only** | future orders price at the better band; no retro adjustment. Requires **cumulative tracking** but no rebate. |
| **`cumulative_retrospective`** | as above, but the better price **applies to all volume once the threshold is hit** (a classic contract rebate) | **variable consideration** — earlier sales must be revisited; this is the ASC-606 case in §5. |

**Cumulative tracking:** a running total of qualifying volume (ordered / dispatched — configurable; default
**dispatched**, to align with revenue recognition) at the grain **(agreement, variant, contract_year)** — note
**per contract year, with an annual reset** (real contracts tier on *annual* cumulative volume), and **aggregated
across the agreement's whole customer set, not per buying party**. So resolution knows the current band and how
close the next threshold is, and the **additivity of successive orders moves the customer up a tier** (order #2 can
price cheaper than order #1). This ties naturally to **H6Q** (the customer's committed/forecast annual volume is the
expected final tier — see §5.2).

> **Group aggregation.** A single agreement can name many buyers (the M:N `price_agreement_customer`) — e.g. a parent
> and all its group/authorised-agent companies. Cumulative volume sums **across all of them under the one agreement**,
> so the tier is reached at the **agreement level**. `contract_volume` is therefore keyed by agreement (+ variant +
> contract_year), not by the individual ordering party.

> **Worked example — modelled on the Octopus Energy supply agreement** (the real shape; negotiated rates omitted for
> confidentiality). Three products with list/RRP **£575 / £610 / £650**; each has a **six-tier cumulative-annual-
> volume** ladder stepping the unit price *down* as the year's volume grows (roughly −24% at tier 1 to −32% at tier 6
> off list). Octopus + its group companies (the "Authorised Agent" clause) **all buy under the one agreement**, so
> their orders **aggregate** toward the same annual tier; the ladder **resets each contract year**. So if the same
> buyer places the same order twice and the second crosses a band, the better tier applies — **prospectively** (next
> orders cheaper) or **retrospectively** (a year-end rebate trues-up *all* the year's units) per the contract's
> charges clause — which is the §5 ledger split.

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
The cumulative window is **12 months from the agreement's `commencement_date`**, rolling — *not* a calendar or
fiscal year. Year *N* = `[commencement + N·12mo, commencement + (N+1)·12mo)`; cumulative volume and the rebate
accrual are **scoped to the current contract year and reset at each anniversary** (no carry-over across years). Each
agreement has its own anchor, so many contracts run on different year boundaries simultaneously. `contract_year` is
derived from `commencement_date` + the order's `occurred_at` (a UTC-instant re-projection, exactly like fiscal-period
assignment, doc 14 §2 — never baked into rows). The fiscal/accounting period (doc 14) is a *separate* axis: a single
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

- `price_agreement` (new), `price_agreement_customer` (new M:N).
- `price_rule` → gains `price_agreement_id` (FK), `up_to_qty` (band ceiling); `min_qty` reread as band `from_qty`.
  Existing single-list rules become tiers of an `open_list` agreement (back-fill) so nothing regresses.
- `order_line` → already has `price_rule_id`; add `price_agreement_id` for the contract reference; record the
  resolved band + the recognised-net vs list for rebate trace.
- `contract_volume` (new) — cumulative position keyed by **(agreement, variant, contract_year)**, summed across the
  agreement's whole customer set (group-level), with an annual reset (for the cumulative bases).
- `contract_year` is **derived** from `commencement_date` + `occurred_at` (not stored) — a rolling-12mo re-projection.
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
- **doc 20 D3–D6** — desk pricing governance manages **agreements** (validity, customer scope, bands) + approves
  tier requests; the layer wall (inter-entity) is unchanged.
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
4. Desk (agreements governance) + companion (tier-request form, ladder display) — per the design pass (doc 22/23).

> **Not started.** This is the design of record; implementation is sequenced above and begins only when greenlit.
