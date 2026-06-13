# 01 — Order Desk (`order`)
Status: COVERED ✅ (refresh) · Roles: sales agents, deal_desk · Backend: `POST /pricing/quote`, `POST /orders`

## Purpose
The order-capture console (doc 20 D2/D12): a keyboard-first multi-line entry where **the price is never typed** —
every line binds to a governed tier (doc 24); quote-before-place is the invariant; a non-tier price is rejected
(422) as guidance, not a failure.

## Layout
- `PageHead` "Order Desk".
- A **multi-line grid** (SKU · qty · unit price[resolved] · ADLP category) — Enter adds a line, fixed Tab order.
- **Quote** panel: resolved ex-VAT · VAT · total-inc-VAT · the tier + **ADLP category** as first-class `Chip`s.
- **Place**: success = order number + status `Chip`; a `pending_ceo` hold is visually loud (it blocks fulfilment).

## Components
`PageHead`, a line grid, the quote panel with tier/ADLP chips, `Money` (mono), place button, status chip.

## Data & layers
Prices are `commercial`. The resolved tier + ADLP category are the hero — show *why* this price. Credit-blocked
parties can't place (surface the block).

## Actions & states
Quote (before place) · Place. **Non-tier rejection (422):** design as guidance — "nearest tier: …" — not a toast
error. *pending_ceo:* loud hold state. *Empty/loading/error* per the kit's data states.

## Design notes
Keyboard-fast or it fails (this is the agent's daily ceremony). The 422 tier-rejection is a *feature* — make it
teach. The ADLP category + resolved tier are first-class, never hidden behind the total.
