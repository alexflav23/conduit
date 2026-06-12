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

### A3. The ASC-606 compliance matrix (auditor-consumable)
A spec table mapping the five steps to mechanism + pinning test/control id:
identify contract (order + governed tier agreement) · performance obligations (order lines / tranches) ·
transaction price (tier resolution; variable consideration = retrospective rebate accrual + true-up) ·
allocation (conserving `allocate`) · recognition (control transfer at dispatch, provable in the immutable
ledger; per-event reversal). Every row cites its suite/control. Holes found become A1 generators or new
controls. Includes the principal/LRD overlay (doc 28): operating revenue gross as principal-vs-agent
analysis demands, COGS at transfer, group elimination.

## Slice B — the authz matrix
Auto-generate from the permission seeds: EVERY route × EVERY preset role → expected {200/403}, plus
layer-projection absence assertions for every (object, field) in FieldLayerMap. Makes the
checker-cannot-see-what-they-approve and wall-leak bug classes structurally unreintroducible.

## Slice C — coverage measurement
sbt-scoverage wired; honest baseline published; CI gate ≥90% branch on the money path
(`ledger`, `money`, `revenue`, `intercompany`, `batch`); visibility-only elsewhere (no vanity gates).

## Slice D — reproducibility, proven
`scripting/Fingerprint` (canonical per-table row/sum digest + ingest git SHA); the refresher commits
`ingest/fingerprint.json` per run; **CTRL-REPRO** compares a rebuild-from-git against the manifest.
Retroactively settles the 2026-06-12 cross-machine question; prospectively makes drift visible in git.

## Slice E — perf floors
Asserted with generous margins, env-skippable locally, watched in CI: policy_selection reads <1s;
recognition <250ms/dispatch; an origin refit <15min on the CI shape; desk build <60s.

## Slice F — desk unit layer (Vitest, per CLAUDE.md)
The shared data-table’s four states (loading/empty/error/forbidden — the crash class found during the
screenshot capture), `api.ts` response-shape contract, SignIn session logic (expiry decode, sign-out).

## Slice G — terraform plan gate
`terraform plan` in CI on an estate-credentialed runner (blocked on GitLab SSH + AWS role; queued last).

## Order & doneness
A1 → A2 → B → D → C → E → F → G. Each slice is done when its suite/control is green in CI and (A2/D) the
control appears in the register. A milestone-level acceptance: an auditor, given READ access and doc A3,
can re-perform every control and trace one arbitrary invoice from P&L figure to CM purchase order without
asking a human.
