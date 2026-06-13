# 32 — Period governance & the investigation view (M-Period)

**Requested 2026-06-13 (CEO):** finance and auditors should log in and investigate *everything* that happened
within an accounting period — and the periods may differ per operating entity, so how is that reconciled?

## 1. The accounting answer: a forced group calendar, not per-entity drift

What large multi-entity groups genuinely do under US GAAP: **for consolidation they force a common group
reporting calendar.** ASC 810 requires the consolidated entities' period-ends to be **coterminous** — a
subsidiary's reporting period may differ from the parent's by **no more than three months**, and only with
disclosure and adjustment for any material intervening events. In practice groups don't lean on that
exception; they mandate that every operating entity closes to the **same group period-end** (month/quarter),
on the group's close timetable.

So the answer to "consolidate on a quarterly true-up, or force a common close?" is: **force a common group
close** for consolidation. A subsidiary *may* keep a different **local statutory** year-end (a foreign sub's
tax year), but that is handled by separate statutory reporting; for the group's books it reports on the group
calendar, and any genuine gap is bridged by a true-up adjustment (the ≤3-month exception). Year-1 Conduit is
UK-only on one calendar, so the gap case is dormant — but the model is built to force alignment, not to drift.

### How Conduit encodes it
- **`reporting_calendar`** — the authoritative GROUP periods (period_key, from/to, status). One per group.
- Each entity's `accounting_period` for a key aligns to the group period (the existing per-entity table,
  doc 02 — it already carries `entity_id`, `period_key`, `status` open|closed|locked).
- **The roll-up gate (the "force"):** the group period for a key cannot LOCK until **every** operating
  entity's period for that key is locked. `PeriodCloseService.closeGroup` refuses otherwise, naming the
  laggards. Consolidation (doc 14 §2.4) runs over the locked group period. This makes "every entity shares
  the close" a server-enforced invariant, not a hope.
- **The true-up escape hatch (future, multi-calendar):** an entity on a divergent local calendar records a
  `period_bridge` adjustment reconciling its local close to the group quarter — the ASC 810 ≤3-month path.
  Spec-only until a second calendar exists.

## 2. The investigation view (M-Period.2)

A finance/auditor surface to investigate one period end-to-end. Read-only, scoped + layer-projected like the
rest of the desk (a UK-wholesale finance viewer sees only their rows; a viewer without `profitability` never
sees margin). `PeriodInvestigationService` assembles, for an `(entity?, period_key)`:

| Section | Source | What it answers |
|---|---|---|
| Status & close board | `accounting_period` + reconciliations | open/closed/locked, who closed it, what blocks the lock |
| Journals | `gl_entry` where `occurred_at` in the period | every posting, per account/side, tied to its event |
| Events | `outbox_event` in the period | the business spine that drove the money |
| Controls | `control_run` in the period | which controls ran, their result + violation count |
| Reconciliations | `reconciliation` | matched / exception / signed-off |
| Documents | `document` issued in the period | invoices, credit notes, statements (gapless) |
| Lineage entry points | invoices recognised in the period | one click from a figure to its CM PO (the Journal Atlas walk) |

Period assignment is a **re-projection of the UTC instant** (`occurred_at AT TIME ZONE :reporting_tz`),
never a stored period stamp (doc 14 time model / L6) — so the same events re-slice correctly under a
different reporting timezone, and a late-arriving event lands in the period its instant dictates.

Gated `view:accounting_period` (finance/auditor/ceo/admin). The desk **Period** view (doc 27) renders it —
the auditor's "show me everything in 2026-Q2" front door, complementing the Proof Center's per-invoice walk.

## 3. Slices (test-first)
1. **Group calendar + roll-up gate** *(M-Period.1)* — **BUILT** (V1_0_77 `reporting_calendar`;
   `PeriodCloseService.closeGroup`). Refuses until every operating entity's period for the key is locked,
   naming the laggards; an entity created after `period_to` never had to close it, so it's excluded.
   `PeriodGovernanceSuite` proves the gate blocks-then-locks and the double-lock no-op.
2. **Investigation query + route** *(M-Period.2)* — **BUILT** (`PeriodInvestigationService` +
   `GET /api/v1/finance/periods/{key}/investigation`, gated view:accounting_period). Assembles the window,
   entity statuses, netted journals, events, controls, reconciliations, documents, lineage entry-points — all
   re-projected onto the period instant. The group lock is `POST /api/v1/finance/group-periods/{key}/lock`.
3. **Desk Period view** *(M-Period.3)* — **BUILT** (`Period.tsx`, tab `Period`). Layer-aware; the entity
   close board, journals, events, controls, reconciliations and one-click invoice→CM-PO lineage. `period.spec.ts`
   (e2e) proves the auditor opens 2026-09, sees every section, traces INV-FLOW, and the group lock is gated.

**Acceptance:** an auditor opens 2026-Q2, sees the close status, every journal/event/control/reconciliation/
document in the period, and clicks any invoice through to its CM PO — and cannot lock the group period while
any operating entity is still open.
