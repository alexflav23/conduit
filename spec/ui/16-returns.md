# 16 — Returns / RMA (`returns`)
Status: MISSING · Roles: customer_service_agent (raise), fulfilment_agent (assess/receive/disposition), finance (refund/credit_note), admin/ceo (approve) · Backend: `POST /orders/{id}/returns`, `GET /returns`, `GET /returns/{id}`, `POST /returns/{id}/{assess,approve,receive,disposition,refund}`

## Purpose
The full RMA lifecycle (doc 09): raise → assess → **approve (maker ≠ checker)** → receive → disposition → refund,
per return type (full_unit / part_only / DOA / warranty_replacement / goodwill), each with its own money + stock
+ commission consequences. Money reverses at the unit's specific batch landed cost; serials never silently
re-enter sellable stock.

## Layout
- `PageHead` "Returns" + a **status-filtered worklist** (raised / assessed / approved / received / dispositioned
  / refunded), sortable by age.
- A `Drawer` per RMA: header (type · scope · reason · status chip) → **lifecycle timeline** (the return.* events) →
  lines (serial · grade · disposition) → credit note → replacement order.
- The transition actions are stage-gated buttons: Assess (grade), Approve (with memo, **self-approval blocked →
  403**), Receive, Disposition (restock/refurbish/scrap — restock blocked for non-A-grade/activated → 422),
  Refund (credit_memo/stripe).

## Components
`PageHead`, worklist table, `Drawer` with a lifecycle timeline, status `Chip`s, `Money` (refund/credit — layered),
disposition selector, an SoD-aware approve affordance.

## Data & layers
`refund_amount` is `commercial`; `unit_landed_cost` is `profitability`; commission claw is `commission` — each
collapses for a viewer lacking the layer. The disposition restock-rejection (422) is a **feature** — design it as
guidance ("non-A-grade → refurbish or scrap"), not a failure.

## Actions & states
Stage machine: refund-before-approve / disposition-before-receive / double-refund all rejected (409) — disable
the out-of-order buttons. **Approve** shows the SoD rule (the raiser can't approve). Disposition/refund are
recorded as commands and effected by the consumer (the UI shows "requested" → "done").

## Design notes
The hero is the **lifecycle timeline** — a return is a story (raised by X, approved by Y, restocked, refunded).
Make maker-checker visible (who did what), and the money/stock consequences explicit at each step.
