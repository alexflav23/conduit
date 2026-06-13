# 12 — Period governance + investigation (`period`)
Status: MISSING · Roles: finance, auditor, ceo, admin (`view:accounting_period`) · Backend: `GET /finance/periods`, `GET /finance/periods/{key}/investigation`, `POST /finance/group-periods/{key}/lock`

## Purpose
The auditor/finance front door to **one accounting period, end to end** — "show me everything that happened in
2026-Q2" — plus the **group close roll-up** (a group period can't lock until every operating entity's period is
locked; ASC 810 coterminous close). Complements the Proof Center's per-invoice walk with a period-wide view.

## Layout
- `PageHead` "Period" + a **period-key picker** (e.g. `2026-Q2`) and an `Investigate` action.
- Header strip: the window (from → to) + a **group-status** `Chip` (open / closed / **locked**).
- Body, top: the **entity close board** — a row per operating entity (name · status chip · closed-at). This is the
  roll-up gate made visible: all-locked ⇒ the group can lock; any open ⇒ lock is refused (name the laggards).
- Body, grid (2-col): **Journals** (netted per account/side, the trial-balance shape) · **Business events**
  (counts per type) · **Controls** (code · pass/fail chip · violations) · **Reconciliations** (type · matched/
  exception · signed-off ✓).
- Footer card: **Documents issued** (chips) + **Lineage entry-points** — each recognised invoice is a clickable
  `AuditRef` that opens its CM-PO walk (the Journal Atlas) in a `Drawer`.

## Components
`PageHead`, period-picker, status `Chip`s, a close-board table, four section `Card`s (journals/events/controls/
recon), `AuditRef` chips for invoices, `Drawer` for the lineage walk, `Money` (mono) for journal amounts.

## Data & layers
Journals show `account`, `side`, `amount` — `amount` is `commercial`/`profitability` layered (collapse for a
volume-only viewer; show a `LayerNote`). Period assignment is a **re-projection of the UTC instant**, never a
stored stamp — so the same period re-slices correctly under a different reporting timezone.

## Actions & states
- **Investigate**: loads all sections for the key. *Empty:* "unknown group period" guidance. *403:* "requires
  view:accounting_period".
- **Lock group period**: refused with a named-laggards message while any operating entity is open (surface as a
  blocking banner, not a toast); succeeds → group-status flips to `locked`. Double-lock = no-op message.

## Design notes
The **roll-up gate is the hero** — make "3 of 4 entities locked, HV-SG still open" unmissable. The investigation
grid should feel like a single auditable surface: every figure is one click from its evidence. Locked = visually
final (a closed vault), open = actionable.
