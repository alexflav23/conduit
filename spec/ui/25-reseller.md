# 25 — Reseller portal (`reseller`)
Status: MISSING (Phase-2, scoped-JWT + rate-limited) · Roles: reseller (external, tightly-scoped grant) · Backend: `/api/v1/reseller/*` (planned), rate-limit middleware (built)

## Purpose
The externally-facing, **scoped** reseller surface (doc 19 §A.1): a reseller signs in with a scoped JWT and sees
**only their own** catalogue-for-me pricing, places orders, and tracks their orders/invoices — everything
scope-walled to their party, layer-projected, and **rate-limited** (their tier degrades before core).

## Layout (a distinct, simplified shell — not the full internal desk)
- A lighter `PageHead` + reseller branding; far fewer nav items than the internal desk.
- **Catalogue-for-me**: the variants + *their* contracted tier prices (no internal cost/margin ever).
- **Place order**: the tier-governed flow (nobody types a price — same 422 rejection of non-tier prices).
- **My orders / my invoices**: their own only; status + documents.

## Components
A reduced shell, catalogue cards with `Money` (their price only), the order flow (reused, scoped), an orders
table — all from the kit but visually distinct (external, calmer).

## Data & layers
A reseller principal carries a **minimal grant set** — `commercial` (their prices) only; `profitability`,
`inter_entity`, others are absent (the wall does the work — no special-casing). Every list is scope-walled to the
reseller's party; cross-reseller data is absent from the payload.

## Actions & states
Place order (tier-governed). **Rate limiting:** over-rate calls get 429 + Retry-After (design a graceful "slow
down" state, not an error). Auth is a scoped JWT (Keycloak, P2.4).

## Design notes
The hero is **trust through scope** — a reseller sees a clean, branded, *their-data-only* view and literally
cannot see internal cost/margin or other resellers (it's absent, not hidden-with-zeros). Distinct from the
internal desk so an external user never feels they're in a back-office tool.
