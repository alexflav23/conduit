# 22 — Design Handoff Pack (for Claude Design)

**Audience:** Claude Design (working with the Figma MCP + the `conduit-desk` codebase), and the front-end
engineers who will land the result. **Status:** ready to pick up — nothing here is blocked on more backend work.

This is the **single front door** for the visual/design pass on Conduit's UI. It does **not** restate the
screen-by-screen specs (those already exist and are authoritative — see §1). It does the things those docs
deliberately leave open: it consolidates the design language, **authors the design-system contract** the code
only stubs today, inventories **what is already built vs. what must be designed**, fixes the **non-negotiable
UX invariants** that fall out of the access/finance model, and defines the **Figma↔StyleX↔Code-Connect
workflow**, deliverables, and acceptance bar.

> **Start with [doc 27 — UI Feature Map](./27_UI_FEATURE_MAP.md):** the page-by-page, feature-by-feature map
> of the desk **as built** — every control by testid, every API binding, every required state, with a real
> screenshot of each page against the live API in `design-assets/desk/`. It is the concrete contract this
> pack previously lacked.
>
> **Read order for a designer:** doc 27 (the concrete map) → this doc → doc 20 (desk screens) → doc 20-H6Q (the deepest board) →
> doc 08 (companion app) → doc 05 (data layers, because they change what every screen is *allowed* to render).
> Everything binds to the live API; **there is no mock data** — the desk already talks to a running backend.

---

## 0. The job, in one paragraph

Conduit's backend and a **functional-prototype desk** are built and green (doc 15: Phases 1–3 through M13
complete). The desk proves every flow end-to-end against the real API, but it is **unstyled by design** — a flat
tab bar, a hand-typed auth-token box, one 7-colour token file, no component library. The design job is to turn
that proven substrate into the **operator console** doc 20 describes: a dense-but-calm, dark-first, keyboard-fast
back-office product with a real design system, a real component kit, and screens that honour the data-layer wall
and the maker-checker spine **visibly**. The bar (from doc 20-H6Q): *extremely readable, extremely functional,
faithful to the immutable ledger* — and then beautiful, in that order.

This is **not** a greenfield brand exercise. Conduit is a citizen of the Hypervolt estate (CLAUDE.md): accent
`#962DFF`, dark-mode first, the hyperstore Vite + React 19 + StyleX stack. Stay inside it.

---

## 1. The authoritative specs (don't duplicate — bind to these)

| Doc | What it gives the designer | Treat as |
|---|---|---|
| **20 — BACKOFFICE_DESK** | Desk screen-by-screen **D1–D22** (purpose · role · entry · layout · data → API · actions · states), nav map, a named 25-component kit, design-language §0, cross-cutting acceptance §10, build order §11 | **Primary screen spec.** Every desk screen's behaviour and data binding. |
| **20-H6Q — H6Q_DESK_DESIGN_SPEC** | The deepest board (coverage drill, scenario toggles, by-agent reconciliation) at function-first fidelity | The hardest layout; the design template for data-dense boards. |
| **08 — APP_SCREENS** | The **companion app** screens (field roles) — already written "to hand to Claude Design" | Companion-app screen spec. **Scope-gated** — see §7. |
| **05 — ACCESS_CONTROL** | Data layers (`volume`/`commercial`/`profitability`/`commission`/`inter_entity`/`treasury`/`pii`), scope, the projection algorithm | **Law.** It decides what each screen may render at all. §4 below distils the UI consequence. |
| **14 — FINANCIAL_INTEGRITY** §6 | The Auditability Center surface (lineage, controls, reconciliation, period close, money/time panels) | The D15–D22 behaviour + why each control exists. |
| **21 — PLATFORM_SERVICES** | Notifications, global search, reporting/exports, **i18n across 15 locales** (incl. CJK + Thai) | Cross-cutting furniture + localisation rules. |
| **06 — API** | The REST contracts every screen binds to | Field/endpoint truth — don't invent beyond it. The TS client is **generated** from the OpenAPI tapir emits. |

If a screen detail is missing here, it is in doc 20 or doc 08. If a *field* is missing there, it is in doc 06 /
doc 02. **Do not invent fields or endpoints.**

---

## 2. Current build state — what to redesign vs. design net-new

The desk lives in `conduit-desk/` (Vite + React 19 + StyleX + TypeScript; run it per §8 to see reality, then
screenshot it). It is a **functional prototype**: `App.tsx` is a flat row of pill tabs + a manual auth-token
input; each tab is a single component that inlines its own StyleX and talks to the live API. There is **no
shared component library, no router, no themes, no design tokens beyond 7 colours.**

**Built today (functional prototype — redesign in place, keep the data wiring):**

| Tab (file) | Covers (doc 20 / 08) | Fidelity |
|---|---|---|
| Order Desk (`OrderDesk.tsx`) | quote → place card (app S-flow / desk seam) | prototype |
| Deal Desk (`DealDesk.tsx`) | D5 exception queue + justification | prototype |
| H6Q (`H6Q.tsx`) | D7 coverage board + capture | prototype (see 20-H6Q) |
| Flow (`Flow.tsx`) | demand→revenue waterfall (M11-I) | prototype |
| Supply (`SupplyWindow.tsx`) | D11 replenishment / time-fences | prototype |
| Shelf (`Shelf.tsx`) | per-account serial consumption (M11-L) | prototype |
| Finance (`Finance.tsx`) | D9–D10 P&L / AR-aging / credit terms | prototype |
| Documents (`Documents.tsx`) | D-docs list + download | prototype |
| Lifecycle (`Lifecycle.tsx`) | order collection ledger timeline (M13-Void.5) | prototype |
| Audit (`Auditability.tsx`) | D15–D18 controls / recon / close / lineage | prototype |
| Tax (`Tax.tsx`) | tax quote tester + rate admin + nexus + VAT exposure/remittance + selling-entity | prototype |

**Specified but not built at all (design net-new):** the **desk shell** (D1–D2: Keycloak login, persistent
role-gated left nav rail, top context bar = entity·market·period·scenario, ⌘K command palette, notification
bell, "viewing as <role> · layers" chip, global search); **Pricing & ADLP** (D3–D4) with the inter-entity layer
wall + versioning/DiffView + ApprovalBar; **CEO Approval** (D6); **Permission builder** (D13–D14); the read-only
**Auditor portal** (D22); and the **money-integrity / time-reslice / evidence-export** panels (D19–D21).

> The functional tabs are the *seed* (doc 20 §11.1 calls `conduit-desk/` "the seed this builds on"). Keep their
> API calls and data shapes; replace their chrome and layout with the real shell + component kit.

---

## 3. The design-system contract (author this — the code only stubs it)

`conduit-desk/src/styles/tokens.stylex.ts` today is **only**:

```
accent #962DFF · bg #0a0b15 · surface #15172a · border #2a2d44
text #e8e8f0 · muted #9aa0b4 · ok #30d158 · warn #ff9f0a
```

That is the seed palette and it is correct — **keep these values**. The system to design around it:

**3.1 Colour (extend `colors` via `stylex.defineVars`).** Add the missing semantic tokens doc 20 §0 calls for:
- `danger` (red) — blocked / exception / over-limit / mismatch. **This is referenced throughout doc 20 and is
  missing from the token file — add it first.** Suggest `#ff453a` family; confirm against AA on `surface`.
- `accentMuted` / `accentSubtle` (accent at low emphasis for hovers, selected rows, focus rings).
- Status set, used **consistently** (doc 08/20): `ok` green = matched/on-track, `warn` amber = attention,
  `danger` red = blocked/exception. Status is the *only* place colour carries meaning — everywhere else is the
  neutral dark scale + accent.
- Surface elevation steps (`bg` → `surface` → a raised `surface2` for drawers/menus/modals) and a `overlay`
  scrim token.
- Text scale: `text` (primary), `muted` (secondary), add `faint` (tertiary/placeholder) and `onAccent` (`#fff`).

**3.2 Themes (`stylex.createTheme`).** Dark is first-class and default. CLAUDE.md §5 names a **`pro`** theme
(`styles/themes/{dark,pro}.stylex.ts`) — design dark + pro; a light theme is optional and lower priority. Themes
re-map the `defineVars` tokens; components must never hard-code a hex (the prototype currently does — fix as you
componentise).

**3.3 The rest of the token system (none exists yet — author all of it):**
- **Typography** — a clean grotesk/sans, **numerals-forward** (money, units, coverage % are the content; prefer
  tabular/lining figures so columns align). Define a type scale (display / h1–h3 / body / mono-numeric / caption)
  as tokens; CJK + Thai must fall back gracefully (see §4 i18n).
- **Spacing** — a single spacing scale token set (e.g. 2/4/8/12/16/24/32). The desk is **dense** — tighter than a
  marketing site, calmer than a trading terminal.
- **Radius** — cards 12–14px (doc 20), controls smaller; one token set.
- **Elevation / shadow** — restrained; dark UIs lean on surface-step + border, not big shadows.
- **Motion** — durations/easings as tokens; subtle. Boards re-query and recompute live (no spinners-of-doom);
  prefer skeletons + stale-stamps over blocking loaders.
- **Z-index** — a token ladder (base → sticky headers → drawers → command palette → modals → toasts).
- **Focus** — a single visible focus-ring token; **keyboard-first means focus states are not optional** (§4).

**3.4 StyleX authoring rules (house style — CLAUDE.md §5, hyperstore).** `const styles = stylex.create({...})`
+ `{...stylex.props(styles.x)}`; tokens via `defineVars`; themes via `createTheme`. Copy hyperstore's dual-plugin
`vite.config.ts` (babel dev plugin + rollup build plugin) — it is load-bearing for CSS extraction and already in
place in `conduit-desk`. No inline hex once tokens exist.

---

## 4. Non-negotiable UX invariants (these come from the model, not taste)

These are **acceptance**, not suggestion. Most are spelled out in doc 20 §0 / §10 and doc 05 — collected here so
nothing is missed in the visual pass.

1. **The data-layer wall is rendered by ABSENCE, not by hiding.** The server projects out layers the principal
   lacks; the response *does not contain* them. A `volume`-only H6Q viewer sees units / coverage % / sell-through
   and **no money column at all** — not `£0`, not a blank cell, not a greyed lock icon. The column is gone. A Deal
   Desk user without `inter_entity` never receives inter-entity price rows — they are absent, with at most a quiet
   "inter-entity pricing is layer-restricted" note. **Design every money/margin/inter-entity affordance to
   collapse cleanly when its data is absent.** The `MoneyOrHidden` component is the contract; design its
   "hidden" state as *nothing rendered*, and design tables that lose whole columns without looking broken.
2. **No UI control is the authority.** Every approve/edit/sign-off is server-enforced. The UI mirrors the server
   projection for *layout only*; it disables or omits what the principal can't do and surfaces `403/409/422` as
   **clear, actionable banners — never vanishing toasts** for financial/control actions.
3. **Maker-checker is visible and self-blocking.** Pricing activation, ADLP decision, period close/lock, PPA,
   manual journal, FX-rate entry, credit-limit change, stock adjust/transfer/write-off, returns approval — all
   two-person. The `ApprovalBar` must show proposer + required approver, **disable the approve control for the
   proposer with a tooltip** (the server rejects self-approval; surface it pre-emptively), capture a memo, and
   show the resulting immutable **audit-reference chip**.
4. **Period & lock context is always legible.** A global reporting context (entity · reporting TZ · fiscal
   calendar · period) parameterises every board/report; changing it **re-projects** (never migrates). `locked`
   periods are read-only and **badged** wherever a period-bound figure shows.
5. **Localisation, 15 locales incl. CJK + Thai; consolidation = USD.** No hard-coded strings; numbers/currency/
   dates per locale; per-entity statutory views in the entity's currency/TZ, the consolidated/group view in USD.
   Type tokens must degrade to CJK/Thai-capable fallbacks (the document side already does this — doc 17 / VAT
   memo). **No RTL in scope.**
6. **Keyboard-first.** ⌘K command palette to jump screen/record; every table arrow-navigable; shift bulk-select;
   forms tab-ordered; order capture is provably <60s keyboard-only (doc 07 M4). Visible focus is mandatory.
7. **Every governed figure is reconstructable.** A figure drills figure → TB transfers → events → documents →
   "replay to re-derive" (D18 LedgerDrill). Design the drill as a first-class, repeatable pattern.
8. **States are not an afterthought.** Every table/board/screen has explicit **loading (skeleton) · empty ·
   error · stale-stamp** states, plus the layer-stripped and locked-period variants. Design them as part of each
   component, not as a TODO.
9. **Accessibility:** target **WCAG 2.1 AA** contrast on the dark theme (verify `danger`/`warn`/`ok` and accent
   against `surface`); semantic roles for tables/dialogs; the palette and nav are reachable and announced.

---

## 5. The component kit (the real deliverable)

Doc 20 §0 names ~25 reusable components; **none exist as shared components yet** (the prototype inlines
everything). Designing this kit *is* the core of the handoff — screens are compositions of it. Each needs its
anatomy, variants, and the full **state matrix** (default / hover / focus / disabled / loading / empty / error /
**layer-absent** / **locked** / **pending-approval**).

**Foundational chrome:** `AppShell` (left nav rail role-gated + top context bar + work area), `EntityPeriodPicker`
(the global context control), `CommandPalette` (⌘K), `NavRail`, `NotificationBell`, `GlobalSearch`,
`ViewingAsChip` ("viewing as <role> · layers: …").

**Data & money:** `DataTable` (virtualised, sortable, column-pick, keyboard-nav, **layer-aware columns**, CSV/XLSX
export), `MoneyOrHidden` (collapses to nothing when absent — the layer-wall primitive), `UnitsBadge`,
`CoverageBar` (forecast vs pipeline vs shipped vs activated), `StatusChip` (order/deal/exception/period/recon/
control states — one consistent colour mapping), `LayerGuard` (renders children only if a layer key is present —
*layout helper, not an authz gate*), `StaleStamp`, `EmptyState`, `ErrorState`.

**Governance & audit:** `ApprovalBar` (proposer/approver/memo, disabled-for-self), `MemoComposer`, `DiffView`
(before/after for versioned rules / amendments / reslice preview), `VersionTimeline`, `AuditRefChip`,
`LedgerDrill` (figure → transfers → events → documents → replay), `ReconRow`, `ControlCard`,
`EvidenceExportSheet`.

**Navigation within boards:** `DrillBreadcrumb` (channel→sub-channel→segment→customer→branch / by-agent),
`DrawerDetail` (right-hand detail pane), `ScenarioToggle` (P20/P50/P80 + ex-account toggles).

> Build the kit **design-system-first** (doc 20 §11.1 builds the shell + kit before screens). Use the Figma
> library workflow (§6) so each component exists once in Figma, maps to its React component via Code Connect, and
> screens are assembled from instances — not redrawn.

---

## 6. Working method — Figma ↔ StyleX ↔ Code Connect

The Figma MCP is connected. The intended loop:

1. **Library first.** Author the token system (§3) and the component kit (§5) as a Figma library that mirrors the
   StyleX tokens 1:1 (`defineVars` ↔ Figma variables; `createTheme` dark/pro ↔ Figma modes). Skill:
   `/figma-generate-library`.
2. **Design from the real app.** Translate the existing screens/flows into designs grounded in the running desk
   (screenshot it first, §8) and doc 20. Skill: `/figma-generate-design`. Bind to the real data shapes from doc 06
   — design the *states*, not lorem.
3. **Code Connect both ways.** Map Figma components ↔ the React components in `conduit-desk/src` so design and code
   don't drift. Skill: `/figma-code-connect`. Invoke `/figma-use` before any `use_figma` call.
4. **Types stay generated.** The TS API client is generated from Conduit's OpenAPI (tapir emits it) — **never
   hand-write response types** (CLAUDE.md §5). Components bind to generated types.
5. **Verify in-browser.** The desk has Playwright e2e + Chrome DevTools MCP available; visual changes are checked
   against the live app, not in isolation.

**House stack constraints (don't fight them):** Vite 8 / React 19 / TS 5.8 / StyleX 0.18 / yarn; React Router v6
(locale-prefixed routes — not yet added to the prototype); React Query v5 for server state; React Hook Form for
forms; axios. Copy hyperstore's dual-plugin `vite.config.ts`.

---

## 7. Scope & open decisions

- **In scope, ready now: the back-office desk** (doc 20, D1–D22) — design system, component kit, shell, and all
  desk screens. This is the first and primary UI and is fully unblocked.
- **Companion app (doc 08) — DECISION RESOLVED: full Flutter** (no React). Its design + adaptive-architecture spec
  is **[doc 23](./23_COMPANION_APP_DESIGN.md)**: it's built the Hypervolt way on `hypervolt_ui_kit` (the estate
  Flutter kit in `~/projects/hypervolt/ux`) with a **Conduit purple `#962DFF` theme variant**, iOS/iPad-first.
  **The desk design system you build here should share its brand tokens with the companion** (one Conduit token
  source, mirrored into both the StyleX/Figma desk library and the Flutter `hypervolt_ui_kit` theme) so desk and
  companion are one design language across two stacks. The companion's visual build still waits for this design
  pass; doc 23 is the brief for it.
- **Out of scope:** inventing screens, fields, or endpoints beyond docs 20/08/06/02; RTL; marketing/site design.
- **The "beautiful layer" is now in scope** — doc 20-H6Q explicitly deferred colour-systems/motion/illustration
  to *after* function-validation, and function is validated. This pack is the green light for that layer.

---

## 8. See the real thing first

Before designing, run the desk and screenshot every built tab — design against reality, not imagination.

```
# backend + seeded data + desk dev server + Playwright (one script):
conduit-desk/run-e2e.sh           # boots api on local pg, seeds, serves vite, runs e2e
# or just the desk dev server against a running backend:
cd conduit-desk && yarn && yarn start     # vite dev server
```

Auth in the prototype is a hand-typed token box (`dev:agent-e2e`); the designed shell replaces this with the
Keycloak OIDC flow (D1). The 12 tabs in `App.tsx` are the current surface — capture each, in its loaded, empty,
and (where reachable) layer-stripped states.

---

## 9. Deliverables & acceptance for the design pass

**Deliverables:**
1. The **design-system** — full token set (§3) authored as StyleX `defineVars` + `createTheme` dark/pro, mirrored
   as a Figma library.
2. The **component kit** (§5) — each component designed with its full state matrix, in Figma + Code-Connected to
   `conduit-desk/src`.
3. The **desk shell** (D1–D2) and the **screen designs** for D3–D22 (and a redesign of the 11 prototype tabs),
   composed from the kit, bound to the real API data shapes.
4. A short **design-system README** in `conduit-desk/` (token usage, theme switching, component index) so
   engineers can implement without re-reading Figma.

**Acceptance (the design is "done" when):**
- Every screen in doc 20 §10's cross-cutting list holds **in the visual design**: the layer wall renders by
  absence (no `£0`/blank/greyed for a missing layer); maker-checker shows proposer/approver with approve
  disabled-for-self + audit-ref; locked periods are badged read-only; `403/409/422` are actionable banners.
- The kit covers all named components with full state matrices (incl. layer-absent and locked).
- Dark + pro themes pass WCAG AA contrast on status + accent colours.
- CJK/Thai render with correct fallback fonts; consolidated views show USD, statutory views the entity currency.
- Components are Code-Connected; types are OpenAPI-generated; the running desk matches the designs in a Chrome/
  Playwright check.

---

> **Status:** this pack is complete and ready to hand over. It is authored for pickup; per the current request it
> does **not** itself start the Figma/design build. The functional substrate (docs 05/06/08/14/20/20-H6Q/21 + the
> running `conduit-desk`) is in place; this doc is the bridge from that substrate to the designed product.

---

## Addendum (June 2026) — forecast surfaces added since this pack was authored

M-Forecast (doc 26) landed three H6Q-board surfaces after the inventory above was written. They bind to the
same live API and belong to the doc 20-H6Q board's design scope:

1. **Per-channel accuracy matrix** — channels × eval quarters, each cell a one-step-ahead WAPE; the selected
   policy (model or blend) is named per row. Dense, scannable, the credibility spine of the board.
2. **Nowcast strip** — the OPEN quarter as closed-months-actual + remaining-months-model = projected close.
   The partial quarter is the product, not a caveat; actual and model segments must read differently at a glance.
3. **Forward curve + TAM overlay** — next two quarters' model forecast with the UK BEV registration trajectory
   (SMMT, exogenous series) as the demand-ceiling context line.

Same invariants apply: data-layer aware (money collapses when hidden), model provenance always visible
(`model_version` names the policy), no number without its origin.
