package com.hypervolt.conduit.proof

// The law register (spec doc 30) AS DATA — the single source the Proof Center renders and the doc-29 A3
// matrix generates from, so the page and the spec cannot drift. A pin is the artifact whose deletion would
// break the build: a re-runnable control (the page re-performs it on click), a CI suite, or a CI gate.
final case class LawPin(kind: String, ref: String) // kind: control | suite | gate
final case class Law(
    id: String,
    title: String,
    statement: String,
    mechanism: String,
    origin: String,
    pins: List[LawPin]
)

object FormalismRegister {

  private def control(c: String) = LawPin("control", c)
  private def suite(s: String)   = LawPin("suite", s)
  private def gate(g: String)    = LawPin("gate", g)

  val laws: List[Law] = List(
    Law(
      "L1",
      "Typed money",
      "No floating point on the money path; cross-currency arithmetic is a compile error; splitting conserves: Σ allocate(total, weights) == total, always.",
      "Money/Currency/RoundingPolicy/allocate (doc 14); Squants for physical quantities; the CI no-float lint.",
      "Designed-in (doc 14, M1) — the one law that predates a failure.",
      List(suite("MoneyPropertiesSpec"), gate("no-float lint"), suite("usd+eur compile-fail"))
    ),
    Law(
      "L2",
      "Conservation over lifecycles",
      "For any valid lifecycle: COGS lands at transfer under flash title else landed; group margin = operating + principal with the IC pair in lockstep; unwound uplift carries the uplift's sign and never exceeds it; a fully-voided lifecycle nets every touched account to exactly zero.",
      "The Journal (single writer of gl_entry), the posters, FlashTitle, ic_match.",
      "The void bug (2026-06-12): reversal enumerated legs 0–3 only — the IC pair was left standing and inventory misstated by the markup. The margin sign law itself was mis-stated once and corrected by a generated counterexample.",
      List(
        suite("JournalLawsSuite"),
        control("CTRL-INV-CONSERVATION"),
        control("CTRL-IC-MATCH"),
        control("CTRL-IC-REMEASURE"),
        control("CTRL-HEDGE-LOCK"),
        control("CTRL-IC-SETTLE-ZERO")
      )
    ),
    Law(
      "L3",
      "Per-event reversal symmetry",
      "A reversal enumerates the exact legs of the original event — never a generic recompute. Physical legs reverse at their original basis, uplift legs at theirs, sign-aware.",
      "InvoiceReversalService leg enumeration + FlashTitle.stampReversal; per-event VAT reversal incl. carriage.",
      "The same void bug as L2 — the generic-recompute shortcut is exactly what produced it.",
      List(suite("ProcurementSuite"), control("CTRL-VAT-NO-OVER-REMIT"))
    ),
    Law(
      "L4",
      "Determinism & idempotency",
      "Transfer id = f(event id, leg) — a pure function of the fact; replaying any event subsequence is a no-op on every balance; gl_entry has one writer.",
      "TbIds deterministic ids; IdempotentConsumer; replay runs the SAME handler over the immutable outbox log — no second write path.",
      "Athena/ghost-busters dual-write drift (doc 01 §3) — the pain Conduit exists to fix.",
      List(
        suite("JournalLawsSuite"),
        suite("ReplayDlqSuite"),
        control("CTRL-GL-MIRROR"),
        control("CTRL-OUTBOX-DRAINED"),
        control("CTRL-DLQ-EMPTY")
      )
    ),
    Law(
      "L5",
      "One transaction, one truth",
      "The business row and its outbox row commit in one transaction; the relay publishes in partition-key order; every consumer is idempotent on the event id.",
      "outbox_event + the relay; the event envelope; the BACKWARD schema-compat gate.",
      "Estate-wide dual-write staleness (doc 01 §3).",
      List(suite("OutboxSuite"), gate("sbt schemaCheck"))
    ),
    Law(
      "L6",
      "Append-only supersession",
      "Facts are never edited or deleted — new versions supersede; corrections are new events, so history is always re-derivable.",
      "Versioned tables with activate-supersedes-prior; the immutable ledger; the period lock.",
      "H6Q overwrites its own history and is therefore unscoreable — the measured counterexample (doc 26).",
      List(
        suite("ProcurementSuite (catalogue versioning)"),
        suite("PeriodCloseSuite (locked rejects)"),
        control("CTRL-IC-CATALOGUE")
      )
    ),
    Law(
      "L7",
      "The wall is absence",
      "A withheld layer, field or row is REMOVED from the payload — never null, zero, masked or placeholdered. Where the structure's existence is itself the secret, the endpoint serves two truths.",
      "Projection.projectFor + FieldLayerMap.seed; the two-truths entity-structure endpoint; data-layer-aware desk widgets.",
      "Doc 05 design; the procurement entity (doc 28) made absence-vs-null load-bearing.",
      List(suite("EntityStructureSuite"), suite("ProcurementSuite (the wall)"))
    ),
    Law(
      "L8",
      "Fail closed",
      "Unknown, unpriced, unverified or unmapped blocks the action — never defaulted. An unpriced internal hop blocks recognition; token verification rejects on any missing claim; non-tier prices are rejected at placement.",
      "FlashTitle.resolve fails Left; GoogleTokenVerifier; tier-bound placement (doc 24).",
      "Designed-in, then re-proven: the A1 generator initially avoided the case — the fix kept the law and fixed the test.",
      List(suite("ProcurementSuite (fail-closed)"), suite("GoogleTokenVerifierSuite"))
    ),
    Law(
      "L9",
      "Maker–checker, and the checker can see what they approve",
      "Governed changes need a second human; checker ≠ maker is enforced; the checker's role must hold view rights on the object class it approves — no role acts on what it cannot view, none edits a layer it cannot view. Scope grants compose as entity ∧ market(geography) ∧ channel ∧ sector.",
      "Propose/activate pairs everywhere; the permission seeds; the four scope axes (Grant/PolicyEngine/ScopePredicate).",
      "V1_0_61: the CEO could not view the tax rates it approved. AuthzMatrixSuite (slice B) then found five more act-without-view gaps in the seed and fixed them (V1_0_71/72) — the matrix now keeps the class structurally unreintroducible.",
      List(
        suite("AuthzMatrixSuite (act ⇒ view; edit ⊆ view; the wall matrix; the sector filter)"),
        suite("PolicyEngineSpec (UK-wholesale-energy scope)"),
        suite("ProcurementSuite (governance)")
      )
    ),
    Law(
      "L10",
      "Falsification by the standing metric",
      "Every model/selector change is judged by the 8-quarter backtest means: improve or revert, and reverts also revert their evidence rows. Champions emerge by argmin error — never hardcoded. Negative results are documented, not deleted.",
      "The policy tournament; the means rule; the append-only accuracy ledger.",
      "Seven falsified forecast experiments — each looked plausible, each lost on the metric (doc 26).",
      List(gate("PolicyTournamentReport — the metric IS the pin"))
    ),
    Law(
      "L11",
      "Censoring — no leakage",
      "A backtest at origin t trains only on data visible at t; forecasts are scored at the grain they were served; origins derive from the calendar; anomalous origins are excluded by rule, not by hand.",
      "The rolling-origin censored backtest; calendar-derived origins; served-grain scoring.",
      "The 'Q2 accurate, priors wrong' puzzle — the answer was censoring discipline, not model error.",
      List(gate("RealBacktest harness"))
    ),
    Law(
      "L12",
      "Reproducibility",
      "Same data + same code produce bit-identical output, on any machine. Data pinned by ingest git SHA; deterministic ids make replay byte-stable.",
      "git-NDJSON ingest snapshots; the Fingerprint manifest (doc 29 slice D).",
      "The 2026-06-12 two-machines question — honestly recorded as believed-not-proven until slice D lands.",
      List(control("CTRL-TAX-REPRO"), gate("CTRL-REPRO (doc 29 D — planned)"))
    ),
    Law(
      "L13",
      "Controls are re-performable, and detection is proven; if you post it, you record it",
      "Every audit-grade invariant is a control an auditor can re-run on live data; a control is not done until a test seeds the corruption it claims to detect and watches it fail with the precise identity of the break. Corollary: every posted leg's deterministic id lands in a fact-table claim column, stamped iff the leg was posted.",
      "The controls register + ControlRunner; ledger_claim + lineage_closure_violation (V1_0_64).",
      "The A2 claims audit found six posting sites with computed-but-ephemeral leg ids — incl. a random event id in stock counts, an L4 breach.",
      List(control("CTRL-LINEAGE-CLOSURE"), suite("LineageClosureSuite (seeded corruption)"))
    ),
    Law(
      "L14",
      "Test-first milestones",
      "A spec acceptance block becomes a suite before implementation; a milestone is done only when its suite is green. Integration tests run real infrastructure — never mocks of the money path.",
      "The build plan (docs 07/15); testcontainers Postgres/Pulsar/Consul + a real TigerBeetle.",
      "House rule (CLAUDE.md §9), held since M0.",
      List(gate("CI"), suite("DemoBookSuite — the demo itself is under test"))
    )
  )
}
