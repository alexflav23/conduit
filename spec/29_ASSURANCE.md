# 29 — M-Assurance: the proven journal model + gap closure

**Requested 2026-06-12 (CEO):** address the testing gaps with formalism — a **proven journal model** for
ASC-606 compliance, tracing all money with **full lineage**. This doc is the contract; slices land test-first.

**State at writing:** 141 domain unit tests (incl. ScalaCheck money properties), 211 integration tests over
85 suites (real Postgres/Pulsar/TigerBeetle), 16 desk e2e specs, 7 documented falsified forecast experiments,
re-performable CTRL-* controls shipped in-product. Gaps: model-level journal laws only spot-tested; no
lineage-closure proof; no authz matrix; no coverage measurement; reproducibility believed not proven; no perf
floors; no desk unit layer.

## Slice A — the journal model, formalized

### A1. Lifecycle conservation laws (stateful property suite)
ScalaCheck stateful/command testing over the REAL services against real TigerBeetle + Postgres: generate
random valid lifecycles — `place → dispatch → recognize → {void | return×n | pay | rebate-settle}` —
including procurement-parent (flash-title) variants and below-cost catalogues. After EVERY generated
sequence, assert the global laws:

| Law | Invariant |
|---|---|
| AR | `AR(party) = Σ invoiced − Σ reversed − Σ settled` |
| Inventory | `INV(entity) = Σ received − Σ relieved + Σ restocked`, at specific batch cost |
| Margin | `group margin = operating + principal`; `0 ≤ returned_uplift + reversed uplift ≤ uplift_total` |
| Void | a fully-voided lifecycle nets every touched account to exactly zero |
| Idempotency | replaying any event subsequence is a no-op on every balance |
| VAT | `VAT(entity, jurisdiction) = Σ recognized − Σ reversed − Σ remitted` |

“Proven” = laws that thousands of generated histories cannot break — the standard the money core already
meets (`allocate` conservation). New laws discovered while writing A3 join the table.

### A2. Lineage closure as a shipped control — `CTRL-LINEAGE-CLOSURE` *(BUILT, V1_0_64)*
One re-performable control, both directions, NO orphans:
- forward: any GL/P&L figure → `gl_entry` rows → TB transfer ids → source event (dispatch / reversal / RMA /
  payment / rebate) → business document (order, invoice, PO, batch, RMA);
- backward: every recognized business fact owns its COMPLETE leg set (incl. flash legs 4/5, their reversals,
  carriage, commission) — a missing or extra transfer fails the control.
Registered in the controls register (re-performable by auditors on live data). The api-it suite proves it
DETECTS corruption: seed a lifecycle, delete one leg / orphan one transfer → control fails with the precise
identity of the break.

**As built:** the pre-work was a claims audit of all 11 posting services — six sites posted legs whose ids
were computed-but-ephemeral (RMA refund/restock/unwind, stock-count variances — which also used a *random*
event id, an L4 violation —, Stripe payout, rebate accrue/true-up/settle, commission settle, reversal
carriage). Every leg now has a claim home, stamped **iff posted** (the Journal drops zero-amount legs, so an
unconditional stamp is a false claim; the claim test mirrors the Journal's minor-unit test exactly).
`ledger_claim` (one row per claimed leg, the **settlement-aware extension point** — M-IC-FX legs UNION in
here) + `lineage_closure_violation` (kinds: `missing_leg` / `orphan_transfer` / `one_sided_mirror` /
`incomplete_fact`, each naming fact table + id + leg). Companions `CTRL-IC-MATCH` (exact decomposition,
sign-carrying unwind bound, leg genealogy) and `CTRL-IC-CATALOGUE` (no self-approved/unapproved/overlapping
active lists) registered alongside. `LineageClosureSuite`: 3 closure worlds (flash void; return unwind;
payments+payout+commission incl. zero-delta true-up+stock count) + 5 seeded-corruption detection tests.

### A3. The ASC-606 compliance matrix (auditor-consumable) *(BUILT — this section; live surface = `/api/v1/proof/asc606/{order}`, same row source)*

The five steps, each: what the standard requires → the mechanism → the pinning artifact. The Proof Center
page renders the same content bound to a live order; spec and page share `FormalismRegister`/
`Asc606Walkthrough` so they cannot drift.

| Step | Requirement | Mechanism | Pinned by |
|---|---|---|---|
| **1. Identify the contract** | A contract with commercial substance, approved by both parties | The order bound to a **governed tier agreement** (`price_agreement`, maker→checker activation; nobody types a price — non-tier prices 422 at placement, doc 24) + the stored customer PO (`customer_po_number`, `source_attachment_id` — doc 25 provenance) | Placement suites (tier rejection); `ProcurementSuite` (governance); `CTRL-IC-CATALOGUE`; L8/L9 |
| **2. Performance obligations** | Distinct promises identified | Order lines (and `delivery_tranche` schedules — independently fulfillable, doc 02) each carry their own fulfilment state | `TrancheSuite` (tranches independently fulfillable, conserving freight); L14 |
| **3. Transaction price** | Incl. **variable consideration**, constrained | Tier resolution from the agreement's bands at placement; retrospective volume rebates invoice at the FIRM entry price while the **expected rebate accrues from the first unit** and trues up bidirectionally as evidence changes (doc 24 §5.2–5.3) | `RebateAccrualVerificationSuite` (the production event chain; year-boundary isolation; conservation across settlement); `CTRL-REBATE-ACCRUAL`; L6 |
| **4. Allocation** | Allocate to obligations on relative standalone price | The conserving largest-remainder `allocate`: Σ parts == total, always — a ScalaCheck law, not a rounding hope (doc 14) | The money property suite (`allocate` conservation); L1 |
| **5. Recognition** | Revenue when control transfers; full reversal on cancellation | Control transfers at **dispatch** (`deliver` → `recognize`): the journal posts to the immutable ledger with deterministic ids; voids mirror the EXACT original leg set (per-event reversal, incl. carriage and the IC pair) | `JournalLawsSuite` (generated lifecycles: void-nets-to-zero, replay no-op); `LineageClosureSuite`; `CTRL-LINEAGE-CLOSURE`; L2/L3/L4 |

**The principal/LRD overlay (doc 28)** — the gross-vs-net analysis the structure demands: the operating
entity reports revenue **gross** (it controls the good pre-transfer: holds inventory risk at landed, sets
nothing — prices are governed tiers — but bears credit risk and fulfilment); its P&L carries **COGS at
transfer** (flash title); the principal books exactly the residual margin; the pair **eliminates at group**
(`elimination_group_id` → consolidation netting). Variable consideration (customer rebates, ASC 606) and
§482 transfer-pricing true-ups are **separate rows by design** — similar machinery, different standards.
Pinned by: `ProcurementSuite`, `CTRL-IC-MATCH`, the consolidation `gl_vs_tb` extension.

**The FX overlay (doc 28 §5, ASC 830)** — the moment of control transfer ALSO fixes the principal's
functional measure: booked rate stamped at dispatch (hedge → spot → fail-closed); open exposure remeasures
at close (delta method); settlement realizes settled − booked with prior unrealized **reclassified exactly
once**; hedge-locked exposure carries at the contracted rate and realizes zero. Pinned by:
`IcFxRateStampSuite`, `IcRemeasureSuite`, `IcHedgeLockSuite`, `IcSettlementSuite`;
`CTRL-IC-REMEASURE`, `CTRL-HEDGE-LOCK`, `CTRL-IC-SETTLE-ZERO`.

### A3.2 The Journal Atlas — how everything is put together

The cross-reference an auditor (or a new engineer) reads first: **every posting site in the system**, its
legs, where each leg's id is claimed (doc 30 L13: *if you post it, you record it*), how it reverses, and
what pins it. One writer (`ledger/Journal.scala`) posts every row below to TigerBeetle AND mirrors it into
`gl_entry`; transfer id = `TbIds.transferId(event, leg)` throughout — deterministic, replay-safe.

| Event (id source) | Leg | DR → CR | Code | Claim home | Reversal story | Pinned by |
|---|---|---|---|---|---|---|
| **Recognition** (`dispatch_id`) — `revenue/RevenueRecognitionService` | 0 | AR:party → REVENUE:entity | 1 | `revenue_recognition.ar_transfer_id` | `invoice_reversal` leg 0 | JournalLaws, LineageClosure |
| | 1 | AR:party → VAT:entity:jur | 1 | `…vat_transfer_id` | reversal leg 1 | CTRL-TAX-VAT-CONSERVE |
| | 2 | COGS:entity → INV:entity (**landed**) | 1 | `…cogs_transfer_id` | reversal leg 2 (**at landed** — the physical leg) | CTRL-INV-CONSERVATION |
| | 3 | CARRIAGE_EXPENSE → CARRIAGE_ACCRUAL | 1 | `…carriage_transfer_id` | reversal leg 3 | LineageClosure |
| | 4 | COGS:op → IC_AP:op:pr (**uplift**, sign-aware) | 1 | `ic_match.op_leg_tb_transfer_id` | reversal leg 4 (sign-aware) | CTRL-IC-MATCH |
| | 5 | IC_AR:pr:op → IC_MARGIN:pr (sign-aware) | 1 | `ic_match.pr_leg_tb_transfer_id` | reversal leg 5 | ProcurementSuite |
| **Invoice void** (`reversal_id`) — `revenue/InvoiceReversalService` | 0–5 | exact mirrors of the originals | 50 | `invoice_reversal.rev_*` + `ic_match.rev_*` | is itself the reversal; idempotent on UNIQUE(invoice) | JournalLaws void law |
| **Return disposition** (`rma_line.id`) — `returns/ReturnService` | 1 | INV → COS_CLEARING (restock at landed) | 1 | `rma_line.restock_tb_transfer_id` | n/a (a return IS an unwind) | LineageClosure |
| | 2/3 | pro-rata uplift unwind pair (sign-aware) | 1 | `rma_line.unwind_{op,pr}_tb_transfer_id` + `ic_match.returned_uplift` (+ hedge release) | n/a | CTRL-IC-MATCH, CTRL-HEDGE-LOCK |
| **Refund** (`rma_id`) | 10/11 | REVENUE → AR; VAT → AR | 1 | `rma.refund_{ar,vat}_transfer_id` | n/a | LineageClosure |
| **Commission claw on return** (`commission_entry.id`) | 9 | COMM_PAYABLE → COMM_EXPENSE | 10 | new `commission_entry` (kind=claw) | n/a | CommissionLedgerSuite |
| **Commission** (`commission_entry.id`) — `commission/CommissionService` | 0 | COMM_EXPENSE → COMM_PAYABLE (**pending**) | 10 | `commission_entry.tb_transfer_id` | claw = void-pending leg 2 | CommissionLedgerSuite |
| | 1/2 | post-/void-pending settle | 10 | `commission_entry.settle_tb_transfer_id` | two-phase semantics | LineageClosure |
| **Payment / refund** (`payment_id`) — `payment/PaymentService` | 0 | BANK\|STRIPE_CLEARING → AR (refund flips) | 40 | `payment.tb_transfer_id` | refund = a new flipped payment | PaymentSuite |
| **Stripe payout** (name-UUID of ref) | 0/1 | BANK ← clearing; FEE_EXPENSE ← clearing | 40 | `payment_payout.{bank,fee}_tb_transfer_id` | n/a | LineageClosure |
| **Stock adjustment** (`adjustment_id`) — `stockops/StockOpsService` | 0 | INV_WRITEOFF ↔ INV (at batch cost) | 1 | `stock_adjustment.tb_transfer_id` | maker-checker, append-only | StockOpsSuite |
| **Cycle-count variance** (`stock_count_line.id`) | 0 | INV ↔ INV_WRITEOFF (sign by variance) | 1 | `stock_count_line.tb_transfer_id` | n/a | LineageClosure (was a RANDOM id pre-A2 — L4 breach, fixed) |
| **VAT remittance** (`remittance_id`) — `tax/VatRemittanceService` | 0 | VAT:entity:jur → BANK | 1 | `vat_remittance.tb_transfer_id` | per-period, conserve-checked | CTRL-VAT-NO-OVER-REMIT |
| **IC movement (M12)** (`event_id`) — `intercompany/IntercompanyService` | 0–3 | IC clearing / INV / IC_MARGIN / FX_CLEARING bridge (linked) | 30 | `intercompany_link.{sell,buy,fx_bridge}_tb_transfer_id` | paired linked legs | IntercompanySuite, CTRL-FXCLEARING-ZERO |
| **Rebate accrue / true-up / settle** (det-id from agreement·year·state) — `pricing/RebateService` | 0 | REVENUE ↔ REBATE_ACCRUAL; settle → BANK | 1 | `rebate_posting` (claim table) | bidirectional true-up IS the correction path | CTRL-REBATE-ACCRUAL |
| **Migration opening** (`MigIds`) — `migration/MigrationService` | 0 | account ↔ OPENING_BALANCE_EQUITY | 20 | `migration_record.tb_transfer_id` | cutover-only, bespoke id scheme | MigrationSuite |
| **Remeasurement** (`ic_remeasurement.id`) — `intercompany/IcRemeasurementService` | 0 | IC_AR_REMEASURE:pr:op ↔ FX_GAINLOSS:pr (**functional ledger**, delta only, sign-aware) | 60 | `ic_remeasurement.tb_transfer_id` | the void TRUES cumulative deltas to zero | CTRL-IC-REMEASURE, IcRemeasureSuite |
| **Settlement** (`ic_settlement.id`) — `intercompany/IcSettlementService` | 0/1 | IC_AP → BANK:op; BANK:pr → IC_AR (txn ccy, sign-aware) | 70 | `ic_settlement.{op,pr}_cash_tb_transfer_id` | full-set; status guard refuses replays | CTRL-IC-SETTLE-ZERO |
| | 2 | final remeasure-to-settlement (functional) | 70 | `…fx_final_tb_transfer_id` | — | IcSettlementSuite |
| | 3 | reclass: IC_AR_REMEASURE → FX_SETTLED (realized **once**) | 70 | `…fx_reclass_tb_transfer_id` | the telescoped adjunct (`prior_deltas_at_settle`) | IcSettlementSuite |

**Assembly invariants the atlas rests on** (each a doc-30 law): claims exist **iff the leg posted**
(zero-amount legs are dropped by the Journal and claim NULL); `ledger_claim` is the union of every claim
column (V1_0_64 → 67 → 69 — each new poster joins it, and `lineage_closure_violation` then closes over it);
open IC exposure = unreversed AND unsettled everywhere; the remeasurement adjunct position telescopes as
Σ(deltas) − Σ(prior-at-settle); FX_GAINLOSS is the only account that absorbs rate movement.

**Account-key registry** (entity-scoped unless noted): `AR:<party>` · `REVENUE:<e>` · `VAT:<e>:<jur>` ·
`COGS:<e>`/`COS_CLEARING:<e>` (note: returns restock to COS_CLEARING) · `INV:<e>` · `INV_WRITEOFF:<e>` ·
`CARRIAGE_{EXPENSE,ACCRUAL}:<e>` · `BANK:<e>` · `STRIPE_CLEARING:<e>` · `FEE_EXPENSE:<e>` ·
`COMM_{EXPENSE,PAYABLE}:<agent>:<ccy>` · `REBATE_ACCRUAL:<e>` · `IC_AP:<op>:<pr>` · `IC_AR:<pr>:<op>` ·
`IC_MARGIN:<pr>` · `IC_AR_REMEASURE:<pr>:<op>` · `FX_GAINLOSS:<pr>` · `FX_SETTLED:<pr>` ·
`FX_CLEARING:<ccy>` · `OPENING_BALANCE_EQUITY`. One TigerBeetle ledger per currency; account `code` = the
GL role (`LedgerAccountCode`); transfer codes: 1 generic · 10 commission · 20 opening · 30 intercompany ·
40 payment · 50 reversal · 60 remeasure · 70 settlement.

## Slice B — the authz matrix *(BUILT — `AuthzMatrixSuite`)*
Generated from the permission seeds + FieldLayerMap, so two bug classes become structurally
unreintroducible:
- **the wall leak** — the projection matrix proves, for EVERY (object, field) in `FieldLayerMap.seed`,
  the field is ABSENT without its layer and present with it (both directions);
- **checker-cannot-see-what-they-approve** (the V1_0_61 class) — *no preset role can act on an object it
  cannot view*, and *no role can edit a layer it cannot view* (edit ⊆ view, per role×object).

Running the matrix found **five real seed gaps** and fixed them (V1_0_71/72): the CEO approved
`transfer_price_policy` and edited `price_rule` with no matching view; admin managed `role` with no
view:role; the retail agent captured `forecast` with no view:forecast; and — at the layer grain — the CEO's
V1_0_61 tax views carried `{}` layers while it approved `{volume,commercial,pii}`, so it signed off tax
governance the projection hid from it. All closed; the three invariants now hold at 0 across the seed.

Also delivered the wider permission-builder ask (doc 05 §2): a **sector** scope axis (`role_assignment.
scope_sectors`, V1_0_70) alongside the existing entity/market(geography)/channel axes — so a grant narrows to
"UK Wholesale, energy sector" (`markets{UK} ∧ channels{wholesale} ∧ sectors{energy}`) and no other. Each axis
ANDs; empty = unconstrained. Wired through `Grant`/`Target`/`PolicyEngine`/`ScopePredicate`/the
`/admin/users/{kc}/assignments` builder API. Pinned by `PolicyEngineSpec` (the UK-wholesale-energy case) and
`AuthzMatrixSuite` (the sector list-filter, both directions).

## Slice C — coverage measurement
sbt-scoverage wired; honest baseline published; CI gate ≥90% branch on the money path
(`ledger`, `money`, `revenue`, `intercompany`, `batch`); visibility-only elsewhere (no vanity gates).

## Slice D — reproducibility, proven *(BUILT — `Fingerprint`/`FingerprintService`, `CTRL-REPRO`)*
`Fingerprint` digests the money tables' **id-independent aggregates** (count + numeric sum per key) folded
with the ingest git SHA — invariant to row order and to the demo book's fresh-UUID churn, sensitive only to
money. `FingerprintService` records a `reproduction_manifest` row (V1_0_73); the refresher's step `[4b/4]`
runs `FingerprintReport` and commits `ingest/fingerprint.json` per cycle, so the digest sits in git beside
the snapshot it fingerprints. **CTRL-REPRO** surfaces any `(scope, git_sha)` that produced more than one
distinct digest — same code + data reproducing differently is non-determinism or drift. `FingerprintSpec`
(pure: order/id invariance, money sensitivity, SHA binding — 5✓) + `ReproSuite` (the demo book fingerprints
identically twice despite id churn; a seeded drift fails the control then clears — 2✓). Retroactively
settles the 2026-06-12 cross-machine question; prospectively makes drift a visible git diff.

## Slice E — perf floors
Asserted with generous margins, env-skippable locally, watched in CI: policy_selection reads <1s;
recognition <250ms/dispatch; an origin refit <15min on the CI shape; desk build <60s.

## Slice F — desk unit layer (Vitest, per CLAUDE.md)
The shared data-table’s four states (loading/empty/error/forbidden — the crash class found during the
screenshot capture), `api.ts` response-shape contract, SignIn session logic (expiry decode, sign-out).

## Slice G — terraform plan gate
`terraform plan` in CI on an estate-credentialed runner (blocked on GitLab SSH + AWS role; queued last).

## Order & doneness
A1 ✓ → A2 ✓ → A3 ✓ (matrix + atlas above; live at /proof/asc606) → B → D → C → E → F → G. Each slice is done when its suite/control is green in CI and (A2/D) the
control appears in the register. A milestone-level acceptance: an auditor, given READ access and doc A3,
can re-perform every control and trace one arbitrary invoice from P&L figure to CM purchase order without
asking a human.
