# 05 — Supply window (`supply`)
Status: COVERED ✅ (refresh) · Roles: supply, procurement, ceo · Backend: `GET /h6q/suppliers|supply/commitments|proposals|warnings`, `POST …/approve`

## Purpose
The contract-manufacturer supply horizon (doc 20 D11/D12): Volex/Luxshare parallel lanes, the **commitment
ladder** (firm/flex/indicative zones), auto-PO proposals (with a human gate), and divergence warnings when
frozen-window demand moves against a firm PO.

## Layout
- `PageHead` "Supply window" + CM picker.
- **Commitment ladder**: a horizon band with **zones** (firm / flex / indicative) as the core visual; version +
  reason (calendar vs forecast_deviation) visible.
- **Auto-PO proposals**: proposed orders with headroom context → **Approve** (the human gate on the proposer).
- **Divergence warnings**: frozen-window demand changes vs the firm PO — a warning, never a silent drop.

## Components
`PageHead`, the commitment ladder with `ZoneTag`s, a proposals table + approve, a warnings list, `Money` (PO value).

## Data & layers
Quantities/zones are `volume`; PO value is `commercial`; CM/entity context may be `inter_entity` (collapse).

## Actions & states
Approve a proposal (human gate). *Warning state:* divergence is loud (never dropped). *Empty:* "no commitments."

## Design notes
The hero is the **zone horizon** — firm (committed) → flex → indicative across time. Approval is a deliberate
human gate on the auto-proposer; divergence warnings protect the frozen window.
