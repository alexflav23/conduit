# 20 — Batch / landed-cost / serial genealogy (`batch`)
Status: MISSING · Roles: finance, fulfilment, admin (`view:batch`) · Backend: LotBatchRepo, Genealogy, serial_unit / unit_lifecycle_event

## Purpose
The traceability spine (doc 07 M7): every serial → its lot/batch → landed cost (specific-identification, never
weighted-average) → the order it shipped on → the customer; and backward, a batch → all its serials. The recall /
warranty / cost-of-a-specific-unit answer.

## Layout
- `PageHead` "Batch & Genealogy" + a serial **or** batch lookup.
- **Serial → genealogy**: a vertical chain — serial → batch (with `landed_unit_cost`) → PO/CM → order → customer →
  activation → warranty; each node an `AuditRef`.
- **Batch → roster**: a batch's landed-cost breakdown (unit + freight + duty) and the full list of its serials
  with current status — the recall list.
- A `unit_lifecycle_event` timeline per serial (received → allocated → dispatched → activated → returned → …).

## Components
`PageHead`, a genealogy chain (linked nodes), a batch roster table, `Money` (landed cost — profitability),
status `Chip`s, the lifecycle timeline.

## Data & layers
Serial/batch identity + status are `volume`; `landed_unit_cost` and the cost breakdown are `profitability`
(collapse). Genealogy is bidirectional and must reconstruct fully (the auditability promise).

## Design notes
The hero is **bidirectional traceability** — type a serial, see its whole life and exact cost; type a batch, see
every unit it became. Make specific-identification visible (this unit cost £X, from this lot — not an average).
This is the recall + warranty-claim power tool.
