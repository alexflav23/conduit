# 23 — Intercompany: TP / FX / hedges / true-ups (`intercompany`)
Status: MISSING · Roles: finance, ceo, treasury (`view:inter_entity` — walled) · Backend: IntercompanyService, TpPolicyService, IcRemeasurementService, IcSettlementService, HedgeValuationService, IcTrueUpService

## Purpose
The inter-entity finance surface (doc 28 §5, the most layer-walled screen): transfer-pricing policy (cost-plus,
maker-checker), the IC pair lifecycle (flash-title at dispatch → ASC-830 remeasure → settle), **hedge** valuation
+ per-market performance (ASC-815, Reg S-K 305), and §482 true-ups. The treasury/CFO cockpit.

## Layout
- `PageHead` "Intercompany".
- **TP policy**: the governed transfer-price tiers (propose → CFO approve, SoD); validity windows.
- **IC pair ledger**: per dispatch — operating/principal legs in lockstep, the markup, remeasurement deltas
  (ASC 830, native→functional at spot), settlement state (open/settled).
- **Hedge book**: each hedge (pair, contracted rate, validity), cumulative MTM, **per-market performance** (the
  Reg S-K 305 view), designation (economic / cash-flow with doc-ref gate).
- **True-ups**: §482 period true-up proposals → approve → the IC pair posted.

## Components
`PageHead`, TP policy table (maker-checker `Chip`s), IC pair ledger with `AuditRef` to TB, a hedge performance
chart per market, `Money` (mono, multi-currency with the rate stamped), FX-rate provenance chips (spot/hedge/asof).

## Data & layers
**Everything here is `inter_entity`** — the entire screen is absent for a viewer without that layer (the wall is
total, not a zeroed view). Multi-currency money shows the native amount + the rate + source. Hedge designation
gates (cash-flow/net-investment require contemporaneous doc-ref).

## Design notes
The hero is the **two-truths structure** — operating entity + principal in lockstep, the markup flowing, FX
remeasuring through earnings, hedges offsetting per market. This is a CFO/auditor screen: every figure carries
its rate provenance and drills to TB. Calm, dense, mono — the gradient stays out of the numbers.
