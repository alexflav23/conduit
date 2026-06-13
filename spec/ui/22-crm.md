# 22 — CRM: parties / contacts / deals / pipeline (`crm`)
Status: MISSING · Roles: sales, deal_desk, finance, admin (`view:party`/`deal`) · Backend: PartyRepo (party/contact), deal/pipeline, credit/billing profiles

## Purpose
The customer master + sales pipeline (doc 07 M4, doc 11): the canonical **party** (organisation/individual, the
doc-02 unification of company/customer), its contacts, billing/credit profiles, and the deal pipeline that feeds
H6Q. The single source of "who we sell to."

## Layout
- `PageHead` "CRM" + party search/filter (by market/channel/sector/segment).
- **Party list** → `Drawer`: identity (legal/display name, type, roles `{forecastable,…}`), scope tags
  (market/channel/**sector**), contacts, billing profile, **credit** (terms, limit, block status), account manager.
- **Pipeline** view: deals by stage (the order-book substrate); weighted value; won/lost; feeds the forecast.
- Consignment awareness (doc 11): ownership vs sell-through for branch/consignment parties.

## Components
`PageHead`, party table + `Drawer`, scope/role `Chip`s (incl. sector — the new access axis), a pipeline/kanban,
`Money` (credit limit, deal value — commercial), contact list, PII-aware contact fields.

## Data & layers
Party identity is `volume`; credit limit + deal value are `commercial`; contact email/phone are **`pii`**
(collapse to a tombstone for viewers without it; respects DSAR crypto-shred). Rows are scope-filtered by
market/channel/sector — "UK-wholesale-energy" sees only those parties.

## Actions & states
Create/edit party (governed), set credit terms (maker-checker for limits), advance a deal stage. Credit-block
state must be loud (it blocks order placement). *PII:* erased contacts show «erased», never raw.

## Design notes
The hero is the **party as the hub** — everything (orders, credit, pipeline, sell-through) hangs off it. Make the
scope tags (market/channel/sector) first-class (they drive the access wall). PII handling must feel respectful +
compliant (collapsed, not redacted-looking).
