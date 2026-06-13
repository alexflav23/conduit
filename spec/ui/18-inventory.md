# 18 — Inventory / ATP / dispatch / carriers (`inventory`)
Status: MISSING · Roles: fulfilment_agent, admin (`view/edit:inventory`) · Backend: InventoryRepo (stock/locations/serials), DispatchService, ATP, carrier adapters

## Purpose
The operational stock + fulfilment surface (doc 07 M6): available-to-promise per variant/location, serial-level
stock, and dispatch (allocate serials → ship → deliver) across carriers — the physical side that the ledger and
genealogy mirror.

## Layout
- `PageHead` "Inventory" + a location/variant filter.
- **ATP board**: per variant × location — on-hand, allocated, available (ATP) — the promiseable number is the hero.
- **Serial view**: serials by status (in_stock / allocated / dispatched / delivered / returned), with batch +
  landed cost (layered).
- **Dispatch worklist**: orders ready to ship → allocate serials → dispatch (carrier, tracking) → deliver;
  a serialised line cannot ship without its serials (the invariant — surface it).

## Components
`PageHead`, ATP matrix, serial table with status `Chip`s, a dispatch `Drawer` (serial picker + carrier), `Money`
for landed cost (profitability-layered), carrier/tracking chips.

## Data & layers
On-hand/allocated/ATP are `volume`; `unit_landed_cost` is `profitability` (collapse). Stock is scope-filtered by
entity/market/location.

## Actions & states
Allocate / dispatch / deliver. *Blocked:* serialised line without serials → cannot dispatch (disable + explain).
Concurrent-allocation safety (no double-allocating a serial) — reflect the race-safe outcome.

## Design notes
The hero is **ATP** — "how many can I promise, where." Make serial status flow legible (a unit's journey
in_stock → allocated → dispatched → delivered). Dispatch should feel fast (keyboard serial-scan friendly,
nodding to the companion app's camera scan).
