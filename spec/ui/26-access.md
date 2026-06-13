# 26 — Access / permission builder (`access`)
Status: MISSING · Roles: admin (`view/create/edit:role`) · Backend: AccessRoutes / AdminRepo (roles, permissions, grants), PolicyEngine, FieldLayerMap

## Purpose
The HubSpot-style permission builder (doc 05): assign permissions **per CRUD action × object type**, scoped to
**sectors and geographies** (and channels). E.g. "give someone UK-Wholesale-Energy *view* but nothing else."
Plus the data-layer grants (which money layers a role sees) and the maker-checker invariant (edit ⊆ view).

## Layout
- `PageHead` "Access".
- **Role list** → `Drawer`/editor: the permission matrix — rows = object types (order, party, price_rule,
  rma, …), columns = actions (view/create/edit/approve/delete/export); cells toggle the grant.
- **Scope axes** per grant: market(geo) ∧ channel ∧ **sector** (∧ = AND; empty = unconstrained) — the
  "UK-Wholesale-Energy" composition, as multi-select chips.
- **Data layers** per view grant: which of `volume/commercial/profitability/commission/inter_entity/pii` the role
  sees (drives the collapse-not-zero wall).
- **User assignment**: assign a role to a user, with optional per-user scope narrowing.

## Components
`PageHead`, a permission matrix (object × action toggles), scope-axis multi-selects (market/channel/sector),
data-layer checkboxes, a user-assignment panel, a "view-as" preview (see the desk as this role).

## Data & layers
This screen *defines* the wall, so it's admin-only. Enforce **edit ⊆ view** (a role can't get an edit it can't
view — surface the rule, block the toggle). Changes take effect on the next request (revocation is immediate).

## Actions & states
Create/edit role, set permissions + scope + layers, assign users. **edit⊆view** guard blocks invalid combos.
A **view-as** preview is the killer affordance — "what will this role actually see?" before you save.

## Design notes
The hero is **compose-a-role visually** — the matrix × scope-axes × layers, with a live "view-as" preview so the
admin sees the exact desk that role gets. Make "UK-Wholesale-Energy view-only" buildable in seconds. This is the
governance control room — clear, deliberate, with the edit⊆view rule visible, not hidden.
