# 08 — Documents (`docs`)
Status: COVERED ✅ (refresh) · Roles: finance, sales, admin · Backend: `GET /documents?invoice_no|order_id`, `GET /documents/{id}/pdf`, `POST /invoices/{no}/void`

## Purpose
The document surface (doc 17): search + retrieve the WORM (object-locked, immutable) fiscal documents — invoices,
credit notes, statements, commercial invoices — and issue voids/credit-notes/refunds as **paired reversing
documents** (never a delete).

## Layout
- `PageHead` "Documents" + search by invoice/order.
- **Results table**: number · type · status · entity · date → **download PDF** (served from the sealed store —
  surface immutability, "final, sealed").
- **Void / credit-note / refund** flow: kind + reason → issues the *reversing* document (a paired-document flow,
  the original stays).

## Components
`PageHead`, results table, a download affordance with a "sealed/WORM" cue, the void flow as a paired-document
action, status `Chip`s.

## Data & layers
Document totals are `commercial`. The void never deletes — design the original + reversal as a linked pair.

## Actions & states
Search · download · void/credit/refund. *Immutability:* a finalised document is read-only ("sealed") — make that
visual. *Void:* shows the resulting reversing document.

## Design notes
The hero is **WORM trust** — these are sealed legal records; the UI language should convey permanence (sealed,
final), and corrections as new paired documents, never edits.
