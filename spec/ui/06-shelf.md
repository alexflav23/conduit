# 06 — Shelf: per-account stock (`shelf`)
Status: COVERED ✅ (refresh) · Roles: sales, supply, finance · Backend: `GET /h6q/shelf`

## Purpose
The real-time per-account stock picture (doc 20 D11): shipped → activated → on-shelf per account, with **runway
days** and a measured **reorder point** — the actionable "who crosses reorder next" view for the field/account team.

## Layout
- `PageHead` "Shelf" + load.
- **Shelf board**: a row per account — shipped · activated · on-shelf · **runway days** · reorder point; the
  actionable column is *who crosses reorder next* (sort by it).
- Per-account drill: the sell-in vs sell-through detail (consignment-aware — placed ≠ drawn).

## Components
`PageHead`, the shelf board table, a runway/`LoadBar` per account, status `Chip`s for at-risk accounts.

## Data & layers
Quantities/runway are `volume` (operational). Scope-filtered by the viewer's market/channel/sector.

## Actions & states
Load · sort by runway. *Empty:* "no shelf data." *At-risk:* highlight accounts crossing reorder.

## Design notes
The hero is **runway → reorder** — turn raw stock into "act on these accounts now." Make sell-in vs sell-through
honest for consignment branches (drawn, not placed, is the sale).
