# 19 — Purchasing / receiving / stock ops (`purchasing`)
Status: MISSING · Roles: procurement, admin (`create/edit/approve:po`, `edit:stock_op`) · Backend: PurchasingService (PO/receiving/GRN), StockOpsService (maker-checker), inbound tranches

## Purpose
The supply-in side (doc 07 M9): purchase orders to the contract manufacturers (Volex/Luxshare), receiving against
them (GRN → auto-allocate to stock at batch landed cost), and governed **stock operations** (cycle-count /
transfer / write-off) under **maker-checker** — every adjustment immutably logged and ledger-posted.

## Layout
- `PageHead` "Purchasing".
- **PO list** → `Drawer`: lines, expected vs received, the inbound-tranche schedule (per-tranche freight →
  conserving landed cost), commitment ladder link.
- **Receiving**: book a GRN against a PO line → serials/qty land in stock at the rolled-forward landed cost.
- **Stock ops** worklist: cycle-count / transfer / write-off, each **proposed → approved** (maker ≠ checker),
  with the reason + the immutable log entry + the ledger transfer it posted.

## Components
`PageHead`, PO table + `Drawer`, a tranche/landed-cost panel, a GRN form, a stock-op maker-checker queue with
status `Chip`s, `Money` (landed cost — profitability), `AuditRef` to the stock_movement + ledger.

## Data & layers
PO qty/dates are `volume`; PO value + landed cost are `commercial`/`profitability`. Inter-CM/entity context may be
`inter_entity` (collapse). Maker-checker: the proposer can't approve their own stock op (SoD).

## Actions & states
Create PO · receive (GRN) · propose/approve stock op. *SoD:* self-approval blocked. *Immutability:* an approved
adjustment is logged, never edited (corrections are new ops). Show received-vs-expected variance loudly.

## Design notes
The hero is **maker-checker governance** — a stock write-off is money leaving the books, so the two-person flow
must feel deliberate. The inbound-tranche landed-cost roll-forward is the subtle accuracy story (freight
conserves into unit cost). Receiving should be quick + scan-friendly.
