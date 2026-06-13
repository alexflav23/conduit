# 11 — Tax (`tax`)
Status: COVERED ✅ (refresh) · Roles: finance, tax_specialist (propose), ceo/CFO (activate) · Backend: `POST /tax/quote`, `GET/POST /tax/rates`, `…/activate`, `GET /tax/routing|selling-entities|vat/exposure`

## Purpose
The tax surface (doc 16): an explainable **determination tester** (the "why this rate" panel), the effective-dated
**rate-table admin** (maker-checker, never edit-in-place), and routing / selling-entities / VAT-exposure.

## Layout
- `PageHead` "Tax".
- **Determination tester**: from/to · region · postcode · party-status · VAT id · amount/currency → quote →
  **total · reverse-charge flag · supply kind (domestic/IC/export) · per-component rates** — an *explainable*
  result, the "why this rate" panel (not just a number).
- **Rate-table admin**: rates list; propose → activate (effective-dated, maker-checker).
- **Routing + selling entities + VAT exposure**: the jurisdiction→provider routing, the seller-of-record map,
  the VAT exposure board + remittance.

## Components
`PageHead`, the determination form + an explainable result panel (components table, supply-kind + reverse-charge
chips), a rate-table admin with propose/activate governance `Chip`s, the exposure board, `Money` (mono).

## Data & layers
Rates/components are `commercial`; selling-entity/routing may touch `inter_entity` (collapse). Rate changes are
maker-checker (the proposer can't self-activate).

## Actions & states
Quote (test) · propose/activate a rate. *Explainability:* always show *why* (supply kind, reverse-charge,
component breakdown). *Maker-checker:* self-activation blocked.

## Design notes
The hero is **explainability** — a US ZIP shows state+county+district; a UK B2B shows the single VAT line; an EU
B2B shows reverse-charge — each with the *reasoning*, not just the total. Rate governance (effective-dated,
two-person) must feel deliberate.
