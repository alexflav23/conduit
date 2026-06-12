# 30 — The Engineering Formalism

**Requested 2026-06-12 (CEO):** define and document the engineering formalism. This doc is it.

This is not a style guide (that is `CLAUDE.md` + the house conventions). It is the small set of
**laws** every Conduit feature must satisfy. The discipline has a fixed form:

> **A law = statement → mechanism → pinning artifact → origin.**
> A principle without a pinning test or re-performable control is an opinion, and opinions regress.
> Where a law was purchased with a measured failure, the origin column records the bug — the laws
> are empirical, not aesthetic.

The pinning artifacts are the contract: if you can delete the test/control and the build stays
green, the law is not yet formalized. Doc 29 (M-Assurance) is the backlog of laws whose pinning is
still being strengthened; this doc is the register of the laws themselves.

---

## 1. Money & journals

| # | Law | Mechanism | Pinned by | Origin |
|---|---|---|---|---|
| **L1** | **Typed money.** No `Double`/`Float` on the money path; cross-currency arithmetic is a compile error; only `convert(rate)` crosses, recording rate+source+rounding; splitting conserves: `Σ allocate(total, w) == total` always. | `Money`/`Currency`/`RoundingPolicy`/`allocate` (doc 14); Squants for physical quantities; CI no-float lint. | ScalaCheck money property suite; the `usd + eur` compile-fail test; the lint stage. | Designed-in (doc 14, M1) — the one law that predates a failure. |
| **L2** | **Conservation over lifecycles.** For any valid sequence `place → dispatch → recognize → {void \| return×n \| pay}`: COGS = transfer when flash-titled else landed; group margin = operating + principal and IC_AP/IC_AR move in lockstep; unwound uplift carries the uplift's sign and never exceeds it; a fully-voided lifecycle nets **every** touched account to exactly zero. | `Journal` (single writer of `gl_entry`), the 10 posters, `FlashTitle`, `ic_match`. | `JournalLawsSuite` — generated random lifecycles (18/run incl. below-cost flash worlds), laws asserted after every history; `CTRL-INV-CONSERVATION`, `CTRL-GL-MIRROR`. | The void bug (2026-06-12): reversal enumerated legs 0–3 only — IC pair left standing, COGS reversed at transfer vs INV. The margin sign law itself was mis-stated once (`ret >= 0` fails below-cost) and corrected by a generated counterexample. |
| **L3** | **Per-event reversal symmetry.** A reversal enumerates the **exact legs of the original event** — never a generic recompute, never ledger-per-category. Physical legs reverse at their original basis (landed), uplift legs at theirs (transfer−landed), sign-aware. | `InvoiceReversalService` leg enumeration + `FlashTitle.stampReversal`; per-event VAT reversal incl. carriage (M13-VAT). | `ProcurementSuite` void/below-cost-void tests; `JournalLawsSuite` void law; `CTRL-VAT-NO-OVER-REMIT`. | Same void bug as L2 — the generic-recompute shortcut is exactly what produced it. |
| **L4** | **Determinism & idempotency.** TB transfer id = `TbIds.transferId(eventId, leg)` — pure function of the fact; replaying any event subsequence is a no-op on every balance; `gl_entry` has one writer. | Deterministic ids; `IdempotentConsumer.processOrDlq` (dedupe on `event_id`); `Journal` as the single posting path; replay runs the **same handler** over the immutable outbox log — no second write path. | `JournalLawsSuite` replay-injection law; `ReplayDlqSuite`; `CTRL-GL-MIRROR`, `CTRL-OUTBOX-DRAINED`, `CTRL-DLQ-EMPTY`. | Athena/GB dual-write drift (doc 01 §3) — the pain Conduit exists to fix. |

## 2. Events & state

| # | Law | Mechanism | Pinned by | Origin |
|---|---|---|---|---|
| **L5** | **One transaction, one truth.** The business row and its outbox row commit in one Postgres transaction; the relay publishes in `partition_key` order; every consumer is idempotent on `event_id`. At-least-once + idempotent = effectively-once. | `outbox_event` + relay fiber; envelope (doc 03 §1); `BACKWARD` schema gate (`sbt schemaCheck`). | M1 acceptance suite (atomic write+outbox, ordered relay, redelivery dedupe); the schema-compat CI stage. | Estate-wide dual-write staleness (doc 01 §3). |
| **L6** | **Append-only supersession.** Facts are never edited or deleted — new versions supersede (price agreements, `transfer_price_list`, `model_accuracy`, accounting periods, the outbox log). Corrections are new events (reversals, true-ups), so history is always re-derivable. | Versioned tables with activate-supersedes-prior; the immutable ledger; period lock (`locked` rejects postings at the boundary). | M1 locked-period test; catalogue versioning tests in `ProcurementSuite`; the forecast evidence ledger. | H6Q overwrites its own history and is therefore **unscoreable** — the measured counterexample (doc 26). |

## 3. Access

| # | Law | Mechanism | Pinned by | Origin |
|---|---|---|---|---|
| **L7** | **The wall is absence.** A withheld layer/field/row is **removed** from the payload — never null, zero, masked, or placeholdered. Where the structure's existence is itself the secret, the endpoint serves two truths (rows filtered AND the linking field deleted). UI widgets collapse for hidden layers. | `Projection.projectFor` + `FieldLayerMap.seed` (the source of truth, not the DB table); `EntityStructureRoutes` two-truths shaping; data-layer-aware desk widgets. | `ProcurementSuite` wall test; `EntityStructureSuite` (field-key-absent assertions); doc 29 slice B will close this exhaustively (every object×field×role). | Doc 05 design; the procurement entity (doc 28) made absence-vs-null load-bearing — `procurement_parent_id: null` would reveal the schema. |
| **L8** | **Fail closed.** Unknown, unpriced, unverified, or unmapped ⇒ the action is blocked, never defaulted. An unpriced internal hop blocks recognition; token verification rejects on any missing claim (`hd`, audience, expiry, `email_verified`); dev tokens are dead when `HYPERVOLT_ENV == prod`; non-tier prices are rejected at placement (422). | `FlashTitle.resolve` Left-on-missing; `GoogleTokenVerifier`; tier-bound placement (doc 24). | `ProcurementSuite` fail-closed test; `GoogleTokenVerifierSuite` (6 rejection cases); `JournalLawsSuite` plain-world generator (an unpriced parented hop **must** fail — the generator itself once violated this and was corrected, not the law). | Designed-in, then re-proven: the A1 generator initially "taught the model the law" by avoiding the case — the fix kept the law and fixed the test. |
| **L9** | **Maker–checker, and the checker can see what they approve.** Governed changes (prices, catalogues, stock ops, DSAR shreds) need a second human; `checker ≠ maker` is enforced; the checker's role must hold view rights on the object class it approves. | Propose/activate pairs everywhere (`ProcurementCatalogue`, tier requests, DsarService); permission seeds. | Maker-checker tests across suites; V1_0_61 seed. | V1_0_61: the CEO approval role could not view the tax rates awaiting its approval — caught by the sign-in gate work. |

## 4. Empirics (forecasting — doc 26)

| # | Law | Mechanism | Pinned by | Origin |
|---|---|---|---|---|
| **L10** | **Falsification by the standing metric.** Every model/selector change is judged by the 8-quarter backtest means from `PolicyTournamentReport`: improve or revert. A revert also reverts its evidence rows (`model_accuracy`). Champions emerge by argmin error — never hardcoded. Negative results are **documented, not deleted** (7 retained in doc 26). | The tournament; the means rule (README runbook); append-only accuracy ledger. | The backtest itself is the pinning artifact — any "improvement" that doesn't move the means is rejected by procedure. | 7 falsified experiments (bias correction, persistence ramp, pipeline_velocity, shelf guard ×4, recency decay, depletion_fast, sell_through prior) — each looked plausible, each lost on the metric. |
| **L11** | **Censoring — no leakage.** A backtest at origin *t* trains only on data visible at *t*; forecasts are scored at the grain they were served; origins/horizons derive from the calendar (quarter close auto-extends the record), and anomalous origins are excluded by rule, not by hand. | Rolling-origin censored backtest (`RealBacktest`); calendar-derived origins (`LocalDate.now()`); served-grain scoring. | The backtest harness; the "actuals out on prior quarters" investigation resolved into this law rather than a patch. | The Q2'26-accurate/priors-wrong puzzle — the answer was censoring discipline, not model error. |
| **L12** | **Reproducibility.** Same data + same code ⇒ bit-identical output, on any machine. Data is pinned by ingest git SHA; deterministic ids (L4) make replay byte-stable. | git-NDJSON ingest snapshots; `Fingerprint` (per-table row/sum digests) + `CTRL-REPRO` (doc 29 slice D). | Slice D — **believed, not yet proven** (the 2026-06-12 cross-machine 16,500 discrepancy is the open counterexample this slice retroactively settles). | The two-machines question. The honest status is recorded here until D lands. |

## 5. Assurance

| # | Law | Mechanism | Pinned by | Origin |
|---|---|---|---|---|
| **L13** | **Controls are re-performable, and detection is proven.** Every audit-grade invariant is a `CTRL-*` row in the in-product register an auditor can re-run on live data. A control is not done until a test **seeds the corruption it claims to detect** and watches it fail with the precise identity of the break. | The controls register (20 shipped: CTA-BALANCE, DLQ-EMPTY, DOC-GAPLESS, FXCLEARING-ZERO, FXRATE-COMPLETE, GL-MIRROR, HEDGE-DRAWDOWN, IC-CATALOGUE, IC-MATCH, INV-CONSERVATION, LINEAGE-CLOSURE, OUTBOX-DRAINED, PII-SHRED, REBATE-ACCRUAL, RECON-EXCEPTIONS, TAX-EXT-EVIDENCE, TAX-NEXUS-GATE, TAX-REPRO, TAX-VAT-CONSERVE, VAT-NO-OVER-REMIT). | Per-control api-it suites; `CTRL-LINEAGE-CLOSURE` (doc 29 A2, V1_0_64) closes lineage bidirectionally — its corollary law, **if you post it, you record it**: every posted leg's deterministic id lands in a fact-table claim column, stamped iff the leg was posted; `LineageClosureSuite` proves detection by seeded corruption (deleted leg, orphan transfer, one-sided mirror, stripped reversal leg — each named precisely). | Doc 14 SOX/ICFR design; the M-Assurance review found controls existed but detection-proof was uneven — and the A2 claims audit found six posting sites with computed-but-ephemeral leg ids (incl. a random event id in stock counts, an L4 breach). |
| **L14** | **Test-first milestones.** A spec "Accept" block becomes a suite **before** implementation; a milestone is done only when its acceptance suite is green (plus Playwright where UI). Real infrastructure in integration tests — testcontainers Postgres/Pulsar/Consul and a real TigerBeetle, never mocks of the money path. | The build plan (doc 07/15); 83 api-it suites, 30 domain test files, 12 e2e specs at writing. | CI itself. | House rule (CLAUDE.md §9), held since M0. |

---

## 6. The conformance checklist

Every feature/PR answers these. "Not applicable" is an acceptable answer; silence is not.

1. **Touches money?** → typed `Money` only (L1); posts through `Journal` (L2); reversal story enumerated per-leg before merge (L3); deterministic ids (L4); inside the ≥90% branch-coverage gate (doc 29 C).
2. **New event?** → envelope + `BACKWARD`-compatible schema; produced via the outbox in the business transaction; every consumer idempotent on `event_id` (L5).
3. **New fact table?** → append-only with supersession; if it can be corrected, the correction is a new event (L6).
4. **New field on a served object?** → a `FieldLayerMap.seed` entry, and the projection test proves absence not null for non-holders (L7).
5. **New resolution/lookup that can miss?** → the miss blocks the action; write the fail-closed test first (L8).
6. **New approval flow?** → maker ≠ checker enforced, and the checker's role demonstrably holds view rights on what it approves (L9).
7. **Touches the forecast?** → judged by the 8Q means, improve-or-revert, evidence rows follow the verdict (L10); no train-time access to post-origin data (L11).
8. **Claims an invariant auditors care about?** → a `CTRL-*` register row + a seeded-corruption detection test (L13).
9. **Any of the above?** → the acceptance test exists before the implementation (L14).

## 7. Amending the formalism

Laws are added by the same empirical route most of these arrived by:

1. An incident, measured failure, or audit finding occurs.
2. It is generalized into a **statement** (what must always hold), not a patch (what went wrong once).
3. A **mechanism** enforces it and a **pinning artifact** makes its deletion break the build.
4. It gets a row here with its **origin** recorded.

Laws are never removed. A superseded law keeps its row with the supersession reason — the same
append-only rule the data obeys (L6). If a law is found mis-stated, the corrected statement
replaces it and the mis-statement joins the origin column (see L2's margin sign law).

## 8. Cross-references

- `CLAUDE.md` — house conventions (style, stack, milestones); this doc layers laws on top.
- Doc 14 — financial integrity (L1–L4's design root) · Doc 03 — events (L5) · Doc 05 — access (L7).
- Doc 24 — contract pricing (L8's placement enforcement) · Doc 26 — forecasting (L10–L12, incl. the negative-results register).
- Doc 28 — procurement entity (the wall and reversal symmetry at full stretch).
- Doc 29 — M-Assurance: the active backlog strengthening the pinning of L2 (A1, shipped), lineage (A2), L7 (B), L12 (D).
