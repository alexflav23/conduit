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
(orgconfig/terraform-time data), never a migration. *(FX settlement and price evolution, originally listed
here, are now specced — §5.)*

## 5. FX & time — the IC balance lifecycle (M-IC-FX)

**Requested 2026-06-12 (CEO):** prices evolve over time, and the inter-entity balances need FX settlement —
clear concepts for spot rates, hedges, and the rest, at the highest standard of US GAAP and ASC 606.

**The gap, stated honestly:** today the catalogue is denominated in the operating market's currency and the
IC pair posts on that single-currency TigerBeetle ledger. That means the principal carries a
foreign-currency monetary asset (IC_AR in GBP/EUR against a USD functional currency) with **no booked-rate
stamp, no period-end remeasurement, no settlement lifecycle, and no realized/unrealized FX distinction**.
The existing `fx_hedge` register (V1_0_25) fixes rates for costing — useful treasury machinery, but it is
not ASC 815 hedge accounting. This section closes both gaps.

### 5.0 The standards map
| Standard | Governs | In Conduit |
|---|---|---|
| **ASC 606** | When control transfers (customer side) — and therefore **the instant that fixes everything** on the IC hop that mirrors it | dispatch = recognition = flash title (§2.2) |
| **ASC 830** | Foreign currency: transaction-date measurement, period-end remeasurement of monetary balances, translation/CTA | §5.2–5.4; consolidation_run already handles translation + CTA |
| **ASC 815** | Derivatives & hedge accounting: designation, effectiveness, OCI mechanics | §5.5 |
| **IRC §482 / OECD TPG** | Arm's-length transfer prices, year-end true-ups | §5.6 |

IC revenue is **not** ASC 606 revenue (eliminated at group) — but ASC 606 discipline is exactly what makes
the FX model deterministic: control transfer at dispatch is one instant, and that instant fixes the
catalogue version, the spot rate, and the batch genealogy together.

### 5.1 One moment fixes everything
The **dispatch instant** binds: (a) the catalogue version in effect (`effective_from::date`, built),
(b) the **booked spot rate** (from `exchange_rate`, source + timestamp recorded — CTRL-FXRATE-COMPLETE),
(c) the origin batches. Order-time quotes are estimates; dispatch binds. Nothing about a later price
version, rate move, or hedge changes a booked match — corrections are new events (true-ups §5.6,
remeasurements §5.3), never edits. `ic_match` gains: `booked_rate NUMERIC(18,8)`, `rate_source`,
`principal_functional_ccy CHAR(3)`, `transfer_total_functional NUMERIC(18,4)` (the principal's-books
measure of the same fact, fixed at the same instant).

### 5.2 The currency model (who bears FX risk — and why it's the principal)
- The catalogue stays denominated **per market in the operating currency**. This is the LRD model working
  as designed: the limited-risk distributor buys in its own functional currency and bears no FX risk; the
  **principal bears the FX exposure** — that is part of the economic substance that justifies the residual
  margin sitting in Singapore (the TP documentation should say so explicitly).
- TigerBeetle stays **transaction-currency** (one ledger per currency; the IC pair balances on the
  operating-currency ledger). The principal's functional-currency view is a **measurement layer**: stamped
  on `ic_match` at booking (§5.1) and maintained by remeasurement postings (§5.3) — there is no second
  "shadow pair" in TB, so conservation laws stay single-currency per leg.
- Law (joins doc 30 when pinned): **`FX_GAINLOSS` is the only account allowed to absorb rate movement.**
  Conservation holds per currency per leg; any difference between booked, closing, and settled measures
  lands in exactly one named place, with the rate and source on the posting.

### 5.3 Period-end remeasurement (ASC 830-20-35)
A period-close run (joins the M13b close calendar): for each open (unsettled, unreversed) IC balance per
entity-pair per currency, remeasure at the **closing rate**; post the **delta** since last remeasurement to
`FX_GAINLOSS:<principal>` (unrealized) against `IC_AR` remeasurement adjunct — delta method, append-only,
one posting per pair per period, reversible like everything else. **CTRL-IC-REMEASURE**: re-performs the
computation from open matches + the closing rate and proves the posted delta matches to the minor unit.

### 5.4 Settlement (`ic_settlement`) *(BUILT, V1_0_69 — full-set-per-pair; partial/explicit selection
arrives with the desk UI)*
A governed settlement run (maker-checker; treasury permission):
1. **Select & net**: open matches per entity-pair per currency (full or partial, oldest-first or explicit
   selection — partial settlements reference exactly which matches/amounts they cover).
2. **Settle at settlement-date spot**: cash legs (principal receives the operating currency or the netted
   functional equivalent — record which), and the **realized FX leg** = booked measure − settled measure,
   cleared from unrealized first (the previously-remeasured portion reclassifies, no double counting).
3. **One matched journal**: cash + IC_AR/IC_AP relief + realized FX, deterministic ids from the settlement
   event, `ic_settlement` rows binding the settled matches (lineage closure — A2 — extends through
   settlements; a settled match with no settlement legs, or vice versa, fails the control).
**CTRL-IC-SETTLE-ZERO**: after a full settlement, the netted IC pair for the covered set is exactly zero
and `Σ realized FX = Σ (booked − settled)` per currency.

### 5.4b Hedge-locked booking (slice 2b — the treasury reality)
Hedges are negotiated **at the start of a fiscal period with 6–12 month validities**, fixing the rate for a
pair for the window. The system encodes them as the existing `fx_hedge` register rows (pair, entity,
contracted rate, `valid_from`/`valid_to`, notional capacity) — created at period start as treasury data,
maker-checker like everything governed. They participate in the IC lifecycle as **rate-locks**:
- **Booking**: `stampRate` resolves **hedge → spot → fail-closed**. A live hedge covering
  (txn ccy → principal functional ccy) for the principal at the dispatch date, with remaining capacity ≥ the
  uplift exposure, books the match at the **contracted rate** (`rate_source = 'hedge:<id>'`) and draws down
  capacity. Insufficient capacity falls through to spot — never a partial split at the match grain.
  Below-cost (negative-uplift) matches never draw a hedge.
- **The drawdown is the live exposure**, atomic with the match row (redelivery cannot double-draw): a partial
  return releases the unwound share; a void releases the remainder. `fx_hedge.ic_drawdown` tracks the
  IC-booked exposure separately from `notional_used` (which M12 movements also consume), so
  **CTRL-HEDGE-LOCK** can assert exact equality: per hedge, `ic_drawdown == Σ live hedge-booked exposure`.
- **Remeasurement**: hedge-booked matches are **locked** — excluded from the §5.3 remeasure base entirely.
  A fixed rate has no FX variability to absorb; any difference at settlement execution is realized in §5.4.
  (This is the estate's existing hedge-locked treatment extended to the IC hop; full ASC 815 designation —
  cash-flow OCI mechanics, effectiveness — remains slice 4/§5.5.)

### 5.5 Hedges — three concepts, kept distinct (ASC 815) *(performance + disclosure BUILT, V1_0_75; gross-presentation correction = slice 4b)*

**What GAAP genuinely requires (the honest read).** Hedge accounting is *elective*; the undesignated default
is a derivative marked to fair value through earnings ("economic"). The IC monetary balance MUST be
remeasured at spot through earnings regardless (ASC 830 — slice 5.3). Cash-flow/OCI deferral is **not
available for an already-recognized monetary balance** — it is for *forecasted* transactions; so hedging the
booked IC receivable is correctly the **economic** treatment (forward MTM through earnings, naturally
offsetting the ASC 830 remeasurement). `designation` defaults to `economic`; `cash_flow`/`net_investment`
require contemporaneous inception documentation (`doc_ref`), enforced fail-closed (ASC 815-20-25) and
reserved for genuinely forecasted exposures.

**Performance + disclosure (slice 4a, BUILT).** A hedge is a first-class valued instrument: `hedge_valuation`
records each period's fair value (`(contracted − spot) × open notional`) and gain/loss, so treasury sees how
every individual hedge performs over its life, per market — the ASC 815-50 disclosure data and the Reg S-K
Item 305 market-risk view. `hedge_disclosure` is the per-hedge/per-market surface (notional, contracted,
latest spot, fair value, designation). `CTRL-HEDGE-PERF` re-derives the figure; `HedgeValuationService`
revalues + governs designation. `HedgePerfSuite` 3✓.

**Slice 4b (the gross-presentation correction, staged next, behaviour-changing).** Today a hedge-booked
balance is frozen at the contracted rate (slice 2b) — the right *net* number but not the GAAP gross
presentation. 4b: the hedged balance remeasures at **spot** like any monetary item, and the hedge's MTM (now
tracked by 4a) posts through earnings to offset it; booking uses spot, not the locked rate. Needs its own
regression pass over slices 2b/3 and an `IcHedgeLockSuite` rewrite — staged deliberately.

### 5.5.1 Hedges — three concepts, kept distinct (ASC 815)
| Concept | Accounting | Conduit mechanism |
|---|---|---|
| **Undesignated economic hedge** (default) | Forward MTM through P&L each period — no hedge accounting, no documentation burden | `fx_hedge` row, `designation = 'economic'`; period-close MTM posting to FX_GAINLOSS |
| **Designated cash-flow hedge** of forecasted IC purchases/settlements | Inception documentation (instrument, hedged item, risk, effectiveness method) **before** designation; effective portion → **OCI**; reclassified to earnings when the hedged item affects earnings (the settlement / the COGS) | `fx_hedge` + new `hedge_designation` (documentation ref, hedged-item link, effectiveness method, OCI account); reclass posting tied to the settlement/recognition event it hedged |
| **Net-investment hedge** | Gain/loss → **CTA**, with translation | consolidation_run is already hedge-aware (locked rate where designated); designation row makes the CTA routing explicit |

The existing rate-fixing/drawdown machinery (`notional_used`, CTRL-HEDGE-DRAWDOWN) is kept — it becomes
the **capacity** model under whichever designation applies. A hedge with no designation row is `economic`
by default: **fail-closed into the simplest correct treatment**, never silently into hedge accounting
(which has documentation preconditions a default cannot satisfy).

### 5.6 Price evolution & TP true-ups (§482/OECD — not ASC 606) *(retrospective true-up BUILT, V1_0_74)*
- **Prospective** changes: already built — append-only catalogue versions, maker-checker, dispatch-date
  binding (§5.1). A price change never touches an existing match.
- **Retrospective** (year-end arm's-length true-up): a governed **`ic_true_up` event** — one matched
  journal pair adjusting the period's aggregate uplift, allocated **conservingly** (largest-remainder, the
  L1 allocator) across the period's matches *for TP documentation only* — the `ic_match` rows themselves
  are never rewritten (L6). Eliminated at group; affects entity statutory P&L; flagged to the customs/VAT
  interplay backlog (§4) since declared values may need adjustment notices.
- Keep the concepts distinct: **customer-side retrospective rebates are ASC 606 variable consideration**
  (doc 24, built); **IC true-ups are §482 compliance**. They look similar (retrospective, accrue-vs-settle)
  and share machinery (conserving allocation, append-only events), but they answer different standards and
  must never be conflated in the matrix (A3 gets a row for each).

### 5.7 Slices & acceptance (test-first, in order)
1. **Rate stamping** — `booked_rate`/`rate_source`/functional measures on `ic_match`; FlashTitle resolves
   the dispatch-date rate fail-closed (no rate row → recognition blocks, like an unpriced hop).
   *Accept:* two dispatches across a rate change carry different booked rates; missing rate blocks.
2. **Remeasurement** — close-calendar run + CTRL-IC-REMEASURE. *Accept:* open balance remeasured across
   two period closes posts only deltas; settled/voided matches drop out; control re-performs to the minor unit.
3. **Settlement** — netting + realized FX + lineage. *Accept:* partial then full settlement; realized =
   booked − settled with prior unrealized reclassified, not double-counted; CTRL-IC-SETTLE-ZERO;
   JournalLawsSuite gains a `settle` tail and the void law still nets to zero pre-settlement.
4. **Hedge designation** — the three-concept table; OCI mechanics + reclass for cash-flow hedges.
   *Accept:* economic hedge MTMs through P&L; designated hedge routes effective portion to OCI and
   reclassifies on the hedged settlement; undocumented designation is rejected (fail-closed to economic).
5. **TP true-up** *(BUILT)* — `ic_true_up` + `ic_true_up_line` (conserving allocation via the L1 allocator) +
   `CTRL-IC-TRUEUP`; `IcTrueUpService` propose/approve (maker≠checker). Posts one sign-aware IC_AP/IC_AR/
   IC_MARGIN pair at period grain, eliminates at group, leaves `ic_match` untouched (L6), lineage closes over
   the new legs. `IcTrueUpSuite` 2✓ (upward true-up + detection). Slice 4 (ASC 815 hedge designation) is the
   remaining M-IC-FX piece.

**Sequencing vs doc 29:** A2 (lineage closure) lands first and is designed **settlement-aware** (the leg
set it closes over includes future `ic_settlement` legs); slices 1–2 are cheap and make the balances honest,
so they go right after A2; slice 3 before A3 (so the compliance matrix covers the full lifecycle);
slices 4–5 follow A3. New laws ("one moment fixes everything"; "FX_GAINLOSS is the only absorber") join
doc 30 with their pinning suites, per its amendment procedure.
