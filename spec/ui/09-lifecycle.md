# 09 — Lifecycle (`lifecycle`)
Status: COVERED ✅ (refresh) · Roles: finance, auditor, ops · Backend: `GET /orders/{id}/lifecycle`

## Purpose
The event-sourced order reconstruction (doc 20 D21): replay an order's full life from the immutable event log —
placed → priced → dispatched → invoiced → recognised → (returned/voided) — each event with its origin
(user / consumer / relay). The "what exactly happened to this order, and who/what did it" tool.

## Layout
- `PageHead` "Lifecycle" + an order id.
- A **true timeline**: each event as a node (type · when · **origin chip** user/consumer/relay), grouped into the
  collection cycles it belongs to.
- Each event expands to its payload / the figures it moved (`AuditRef` to the ledger where money).

## Components
`PageHead`, a vertical event timeline, origin `Chip`s, `AuditRef` to transfers, `Money` where relevant.

## Data & layers
Event identity is `volume`; money figures within are layered. The reconstruction must be complete (the
auditability promise — the order is rebuildable from events alone).

## Actions & states
Load by order id · expand an event. *Empty:* "unknown order." Origin chips make human vs machine causation clear.

## Design notes
The hero is **the timeline as truth** — an order's life, honestly reconstructed, with origin attribution. This is
where "prove what happened" becomes a scroll, not a query.
