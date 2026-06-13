# 14 — Proof Center (`proof`)
Status: MISSING · Roles: finance, auditor, ceo (`view:proof_center`); tamper needs `manage:proof_center` + non-prod · Backend: `GET /proof/laws`, `POST /proof/controls/{code}/run`, `GET /proof/trial-balance/{entity}`, `GET /proof/journal/{invoiceNo}`, `GET /proof/asc606/{orderId}`, `POST /proof/tamper/{kind}` + `/tamper-restore`

## Purpose
The **interactive formal proof** (doc 31) the CTO/auditor uses to *convince themselves* the books are sound: the
law register that re-runs its controls live (green is earned, never cached), the per-invoice ASC-606 walkthrough,
the journal walk with browser-recomputed conservation, and a tamper sandbox that breaks the books and watches a
control name the break — then restores to green.

## Layout — four sub-pages (left sub-nav or segmented control)
1. **Laws** — the register (doc 30 L1–L14): each law as a `Card` (statement · mechanism · origin-bug · pins).
   Each pin is a **Run** button → re-performs the control → live pass/fail `Chip` on the pin (green earned now).
2. **ASC-606 walk** — enter an order → the five steps as a vertical stepper; step 5 shows the recognition; an
   `inter_entity`-walled principal/LRD overlay appears only for viewers with that layer (absent otherwise).
3. **Journal walk** — enter an invoice → its DR/CR legs (`Money`, mono) with a **conservation strip recomputed
   in the browser** (Σdebits == Σcredits → "balanced") + the Journal Atlas links to the CM PO.
4. **Tamper sandbox** (admin, non-prod) — buttons: delete-leg / orphan-transfer / strip-reversal → the control
   (`CTRL-LINEAGE-CLOSURE`) flips to **fail** and names the break → **Restore** → back to green.

## Components
`Card` per law, per-pin Run + result `Chip`, a stepper for ASC-606, a DR/CR ledger table with a conservation
strip, `AuditRef` to the CM PO, the tamper control panel with a live control-status chip.

## Data & layers
The principal/LRD ASC-606 overlay + flash-title legs are `inter_entity` — **absent** for finance (no overlay,
not a zeroed one). Money is mono/right-aligned. Trial balance proves Σdebits == Σcredits per entity.

## Actions & states
- **Run control**: re-performs on click; the pin shows pass/fail + violation count. Never show a cached green.
- **Tamper**: double-gated — `manage:proof_center` AND non-prod; in prod the endpoint *does not exist* (the
  surface must communicate "unavailable in production," not error). Finance (view-only) sees the sandbox is not
  theirs to operate.

## Design notes
This is the **showpiece** — the screen you demo to an auditor. The hero moment is **corrupt → the control names
it → restore → green**, performed live. Make the conservation strip and the live-re-run feel like proof, not
decoration: earned green, with the timestamp of the run. Gradient accents welcome here (it's the hero), data
stays mono and calm.
