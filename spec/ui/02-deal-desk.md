# 02 — Deal Desk (ADLP exceptions) (`dealdesk`)
Status: COVERED ✅ (refresh) · Roles: deal_desk (maker), ceo (checker) · Backend: `GET /adlp/exceptions`, `…/{id}/submit`, `…/{id}/decision`

## Purpose
The governed price-exception workflow (doc 20 D5/D6, doc 24): a price below the tier band is a **price-tier
request** (maker-checker → CEO), never an ad-hoc number. The decision IS the activation — approving releases +
re-quotes the held orders.

## Layout
- `PageHead` "Deal Desk".
- **Pending queue** (the worklist): exceptions `pending_ceo`, sorted by age + deviation.
- **Exception detail** `Drawer`: list price · band · requested · **deviation vs band (the hero metric)** · status.
- **Narrative** (maker): a structured justification form (volume / denomination / strategic / notes) with a
  completeness affordance.
- **Decision** (checker): approve/reject — the confirm makes the consequence explicit (releases held orders).

## Components
`PageHead`, queue table, detail `Drawer`, the deviation hero metric, a structured narrative form, decision
controls, `Money`, status `Chip`s.

## Data & layers
Prices/deviation are `commercial`; any margin context is `profitability`. Maker ≠ checker enforced.

## Actions & states
Submit narrative (maker) → Decide (checker). The decision's downstream effect (held orders release + re-quote)
must be shown in the confirm. *Empty:* "no pending exceptions."

## Design notes
The **deviation vs band** is THE number — design it as the hero. Make the maker-checker hand-off and the
release-on-approve consequence unmistakable.
