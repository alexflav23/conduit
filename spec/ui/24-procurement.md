# 24 — Procurement entity (principal/LRD, flash-title) (`procurement`)
Status: MISSING · Roles: finance, ceo, procurement (`view:inter_entity`/`procurement`) · Backend: ProcurementCatalogue, IntercompanyService (flash-title matched journals), entity structure

## Purpose
The procurement-entity structure (doc 28): the SG **principal / LRD** topology, the central **catalogue** the
group buys through, and **flash-title** matched journals (title flashes through the principal at dispatch, the
markup booked, fully unwound on void/return). The "how the group actually procures" map.

## Layout
- `PageHead` "Procurement".
- **Entity structure**: the principal ↔ operating-entity (LRD) graph — who buys, who holds title, the functional
  currencies.
- **Central catalogue**: the governed catalogue (maker proposes → checker activates → v2 supersedes), per-variant
  transfer terms.
- **Flash-title ledger**: per dispatch — the matched principal/operating legs, the uplift, and the unwind on
  return/void (the legs net to zero on a full void).

## Components
`PageHead`, an entity-structure diagram, a catalogue table with governance `Chip`s, a flash-title journal view
with `AuditRef` to TB, `Money` (uplift/margin — inter_entity/profitability).

## Data & layers
The whole surface is `inter_entity`-walled (absent without the layer). Catalogue identity is `volume`; transfer
terms + uplift are `inter_entity`. Governance is maker-checker (self-activation blocked).

## Design notes
The hero is **flash-title** made visible — title moving through the principal at the instant of dispatch, the
markup booked, and (critically) **unwound to exactly zero** on a void/return. Show the matched pair in lockstep;
the conservation (nets to zero) is the proof the structure is sound.
