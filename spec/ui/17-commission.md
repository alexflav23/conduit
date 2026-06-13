# 17 — Commission (`commission`)
Status: MISSING · Roles: finance, ceo (`view:commission`), agent (own statement) · Backend: commission_entry (accrual/post/claw), rebate scheme/accrual, `GET /finance/*`

## Purpose
The two-phase commission engine (doc 04 §Commission, doc 24 rebates): **accrue → post → claw**, scheme-resolved
(validity windows, channel/country/team), with retrospective volume **rebates** as ASC-606 variable
consideration (accrue ≠ apply, true-up). The agent sees their statement; finance sees the whole book and the
ledger anchoring.

## Layout
- `PageHead` "Commission".
- **Agent statement** (own scope): earnings by order/period, status (accrued / posted / clawed), running total.
- **Finance book**: all agents × period; the two-phase state per entry; **claws** (returns) shown as reversing
  entries in the *current* period (the prior period stays as reported).
- **Rebate accrual** panel: per scheme, the accrued-vs-expected rebate (commitment floor, bidirectional true-up),
  each figure an `AuditRef` to its TigerBeetle transfer.

## Components
`PageHead`, statement table, two-phase status `Chip`s, `Money` (mono), `AuditRef` to the ledger, a rebate
accrual card with the accrue/apply distinction.

## Data & layers
Everything here is `commission`-layered (collapse entirely for a viewer without it); `basis_amount`/margin is
`profitability`. An agent sees only `own` scope; finance sees `all`.

## Actions & states
Read-mostly (commission is event-driven: order → accrue, dispatch → post, return → claw). Statement export.
*Empty:* "no commission this period." A claw must be unmistakable (money coming back).

## Design notes
The hero is the **two-phase lifecycle** made honest — accrued (not yet earned) vs posted (earned on dispatch) vs
clawed (returned) — so an agent trusts the number and finance can defend it. The rebate accrue-vs-apply is the
subtle ASC-606 story; surface the true-up direction clearly.
