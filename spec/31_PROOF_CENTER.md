# 31 — The Proof Center: the demo book of record + the interactive formal proof (M-Proof)

**Requested 2026-06-12 (CEO):** a complete demo database, scripted and realistic to Hypervolt's business,
that can prove every formalism to the CTO — and an interactive part of the React/StyleX portal dedicated to
the formal proof: how the journals work, how they reconcile, and why they are sufficient for ASC 606.

## 0. The first proof is how the data gets in

A demo database built from SQL INSERTs is an **anti-proof**: it fabricates facts with no journals behind
them — TigerBeetle is empty, `gl_entry` is empty, and every control rightly fails. (The repo's old
`scripts/demo-seed.sql` is exactly this, kept as the cautionary exhibit; it remains useful only for
forecast-history rows, which are projections, not money.)

So the demo book of record is **generated exclusively through the production write paths**: every order is
`OrderService.place` against a governed tier (nobody types a price — including the seeder); every journal
exists because `Journal.post` wrote it; every control passes because the system actually holds, not because
the dataset was arranged to look like it. The seeder is the same code the API runs. That *is* the first
exhibit in the CTO demo: "the seed script cannot cheat — watch what happens when it tries" (the tamper
sandbox, §3.5).

## 1. The demo book of record (`DemoBook` + `DemoSeed`)

### 1.1 Where it lives
- `domain/.../demo/DemoBook.scala` — the seeding logic as a library (`DemoBook.seed(xa, ledger): IO[Summary]`),
  so the api-it suite proves the seeded world headlessly and the scripting main reuses it verbatim.
- `scripting/.../DemoSeed.scala` — the runnable: connects to the local compose stack (PG `localhost:5532`,
  TigerBeetle `localhost:3033`, both env-overridable), runs `DemoBook.seed`, then runs the **verifier**.
- Reset = `docker compose down -v && ./run-local.sh` — the book is append-only like everything else;
  there is no "delete the demo" path because there is no delete path.

### 1.2 The cast (realistic, fictionalized)
- **Entities**: `Hypervolt Procurement SG` (procurement principal) ← `Hypervolt UK` (operating LRD,
  `procurement_parent_id`). GBP functional for both until M-IC-FX slice 1 lands (then SG gets the rate-stamped
  functional view).
- **Contract manufacturer**: `Luxshare-UK` POs → goods receipts → `lot_batch` at landed ~£300/unit
  (+ freight components) → serials. The physical genealogy every dispatch traces back to.
- **Catalogue**: SG sells into the UK market at **£380/unit** (the doc-28 flash-title uplift of ~£80).
- **Customers** (sector-typed, echoing the real channel mix): `Aurora Energy` (utility; 90-day terms;
  cumulative-**retrospective** rebate agreement — the ASC 606 variable-consideration exhibit),
  `ChargeWorks Installations` (installer; transactional, sell-through), `Northern EV Wholesale`
  (distributor; per-order volume bands), `BrightHome Retail` (small, list-price).
- **Agent**: one commission agent on a 10%-of-gross-margin scheme (accrue → post; one claw via a return).
- **Product**: the `hv3` charger family (serialised) + an accessory class (non-serialised, open list price —
  proves the accessory regimen doesn't earn charger tiers).

### 1.3 The year (events in causal order, all through services)
One contract year, quarterly cadence with H2-heavy sell-in (the measured β<0 inversion):
1. CM POs + receipts: 2 receipts per half, batches with distinct landed costs (cost drift → true-up later).
2. Governed pricing: tier agreements per customer (maker→checker activation); Aurora's retrospective bands
   `600/560/520 @ 0/100/500` with `min_commitment_units` (expected-rebate accrual from day one).
3. ~14 orders across Q1–Q4 (1–6 lines, qty 2–40): placed via `OrderService.place` (tier-bound), allocated,
   dispatched (some in tranches), delivered, **recognized** (flash-title journals on every one).
4. Lifecycle variety: 2 voids (one same-day mistake, one cancellation post-recognition — the full per-leg
   reversal incl. the IC pair); 2 returns (one A-grade restock with flash unwind + commission claw, one DOA
   scrap); payments on ~80% of invoices (Stripe + bank, one partial), 2 Stripe payouts (fees to P&L);
   1 stock count with a 2-unit shrinkage (maker≠checker); Q1 VAT remittance; rebate accrue→true-up→settle
   for Aurora year-end; a Q1 period close; a year-end consolidation run.
5. A **below-cost** flash case (clearance at £250 vs £300 landed) — the sign-aware uplift pair, voided.

### 1.4 The verifier (the headless proof)
After seeding, run EVERY automated `CTRL-*` via `ControlRunner` and print the proof table —
law (doc 30) → control/check → violations (must be 0) — plus the conservation headlines an auditor would
recompute: revenue, operating COGS (at transfer), group margin = operating + principal (to the penny),
AR = invoiced − reversed − settled, VAT position, and one full invoice lineage walked end-to-end
(`LineageService.forInvoice` → CM PO). **Determinism, honestly scoped**: ids are service-generated UUIDs
(runs differ), but structure, counts and sums are identical run-to-run; the laws hold on every run.

### 1.5 Pinned by
`DemoBookSuite` (api-it): runs `DemoBook.seed` against testcontainers, asserts all controls pass and the
headline sums match the script's own arithmetic. The demo is itself under test — a green CI run proves the
CTO demo cannot arrive broken.

## 2. The Proof Center (desk section, React/StyleX)

A new gated tab `Proof` (view permission: `proof_center` — admin/ceo/finance/auditor; everything rendered
is data-layer-projected like the rest of the desk). Five pages, all reading LIVE data — nothing
pre-rendered, every figure re-derivable while the CTO watches:

### 2.1 The Laws
Doc 30 rendered as a live register: each law (L1–L14) with its statement, mechanism, **and its pinning
artifact executed on click** — controls re-run via `ControlRunner` (result + violation count + run
timestamp persisted to `control_run`, so the demo leaves audit evidence), property-suite laws shown with
their last CI run. A law whose pinning artifact cannot be re-performed renders as such — the register never
fakes green.

### 2.2 The Journal Walk
The interactive "how the journals work": pick any invoice (search/dropdown over the demo book) → render the
complete double-entry picture from `gl_entry`: each leg as DR/CR cards (account key, role, minor units,
phase, TB transfer id), the flash-title uplift pair visually bracketed behind an `inter_entity` wall marker
(absent entirely for non-holders — the wall demonstrated live), reversal legs mirrored against their
originals, and the full lineage chain (`LineageService.forInvoice` + `contractualSources`): P&L figure →
gl rows → TB ids → events → invoice document → order → customer PO → price agreement → batch → CM PO.
Conservation strip at the top: Σ debits == Σ credits per currency, recomputed client-side from the rows on
screen — the viewer's own browser verifies the books.

### 2.3 ASC 606, step by step *(BUILT — `Proof.tsx` ASC 606 page; this IS doc 29 A3's deliverable surface)*
The five-step matrix as an interactive walkthrough bound to a real demo order:
| Step | Shown live |
|---|---|
| 1 Identify the contract | the order + its governed tier agreement + the stored customer PO (provenance ids) |
| 2 Performance obligations | order lines / tranches, each with status |
| 3 Transaction price | tier resolution trace; Aurora's **variable consideration**: expected-rebate accrual, the REBATE_ACCRUAL balance, true-up history |
| 4 Allocation | the conserving `allocate` over the order's lines — weights, parts, Σ == total, the ScalaCheck law cited |
| 5 Recognition | control transfer at dispatch: the dispatch event, the recognition journal, per-event reversal symmetry (void shown side-by-side), the principal/LRD overlay (COGS at transfer, group elimination netting to zero) |
Every row cites its pinning suite/control id — the spec table (A3) and this page are generated from the
same source so they cannot drift.

### 2.4 Reconcile
The proof the books tie: trial balance from `gl_entry` (posted balances, per entity/currency);
`gl_vs_tb` (CTRL-GL-MIRROR) re-run live; elimination groups netting to zero in the group view; AR aging vs
the AR law; VAT position vs CTRL-TAX-VAT-CONSERVE; the year-end consolidation run with CTA as the explained
plug. Each panel = a query + the control that polices it, side by side.

### 2.5 The Tamper Sandbox *(non-prod only, the closer)*
Gated exactly like dev tokens (`HYPERVOLT_ENV != prod`, plus `manage:proof_center`): three buttons —
"delete a journal leg", "orphan a transfer", "strip a reversal leg" — each seeds the corruption A2's suite
seeds, re-runs `CTRL-LINEAGE-CLOSURE` live, and shows the violation row **naming the exact break**; then
"restore" puts it back and the control returns to zero. The CTO watches detection happen, not a slide
about it.

## 3. Backend additions (thin — the machinery exists)
- `ProofRoutes`: `GET /api/v1/proof/laws` (the doc-30 register as data + last control runs),
  `POST /api/v1/proof/controls/{code}/run` (ControlRunner; exists conceptually in Auditability — reuse),
  `GET /api/v1/proof/trial-balance`, `GET /api/v1/proof/asc606/{orderId}` (the five-step bundle),
  `POST /api/v1/proof/tamper/{kind}` + `/restore` (env-gated, §2.5).
- `proof_center` permission seeds (view: admin/ceo/finance/auditor; manage: admin only).
- Lineage + controls + consolidation + gl queries already exist (LineageService, ControlRunner,
  AuditQueryRepo, Consolidation).

## 4. Slices (test-first) & acceptance
1. **P1 DemoBook + verifier** *(BUILT)* — `DemoBookSuite` green: all controls 0 violations on the seeded
   world; sums match script arithmetic; one lineage walk reaches the governed agreement + the ledger.
   `sbt "scripting/runMain com.hypervolt.conduit.scripting.DemoSeed"` against compose prints the proof table.
   As built: 11 fulfilled orders (4 Aurora on 90-day credit terms incl. the retrospective-rebate year,
   3 ChargeWorks with accessories + commission, 2 Northern incl. the voided 25-unit expansion, BrightHome,
   and the below-cost clearance — voided), 220 serials over 4 Luxshare batches with cost drift, restock
   return with flash unwind + refund, DOA scrap, partial payment, 2 payouts, commission post/claw/true-up,
   2-unit shrinkage count, Q2 VAT remittance, Aurora rebate accrue→settle, year-end USD consolidation.
2. **P2 ProofRoutes + permission seeds** — route×role tests (403 walls; tamper 404s when env=prod).
3. **P3 Desk: Laws + Journal Walk + Reconcile + Tamper** *(BUILT)* — the `Proof` tab (gated `view:proof_center`),
   four sub-pages over the live API. Laws: the doc-30 register with per-pin re-run (green earned per click).
   Journal Walk: an invoice's DR/CR leg cards from a new `GET /proof/journal/{invoiceNo}` (gl_entry legs,
   flash legs walled for non-inter_entity holders) with the **conservation strip recomputed client-side**.
   Reconcile: the trial balance with the balanced proof. Tamper: the corrupt→named→restore loop (admin only;
   404 in prod). `e2e/proof.spec.ts` (finance walks + re-runs; admin drives the full tamper loop).
4. **P4 Desk: ASC-606 walkthrough** *(BUILT)* — the ASC 606 sub-page binds `GET /proof/asc606/{order}`: the
   five steps for a real order, each citing its pinning law/control ids; the principal/LRD overlay renders
   only for inter_entity holders (absence is the wall — the holder-sees/non-holder-doesn't pair is pinned in
   `ProofRoutesSuite` over the DemoBook's flash order). The **Tamper Sandbox** shipped in P3. `proof.spec.ts`
   covers the five-step render + the finance wall (no flash overlay) alongside the tamper loop.

**Milestone acceptance:** on a fresh `docker compose up`, one command seeds the book; the CTO opens the
Proof tab, re-runs every control to green, walks an invoice from P&L to the CM purchase order, watches a
seeded corruption get named and restored — without anyone touching a terminal.
