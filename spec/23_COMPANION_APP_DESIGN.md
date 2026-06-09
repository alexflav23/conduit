# 23 — Conduit Companion App: Flutter Design & Architecture Spec

**Audience:** Claude Design (and the Flutter engineers who land it). **Status:** design spec — *not* an
implementation start. Per the standing instruction, the companion app is **specced now; built only after the
Claude Design pass is complete.** This doc is the **design + architecture** layer; the **screen-by-screen
functional spec is [doc 08](./08_APP_SCREENS.md)** (S1–S22) and remains authoritative for *what each screen does
and which API it binds to*. This doc says *how it is built, how it adapts to iOS/iPad, and what it looks/feels
like* — the Hypervolt way.

> **DECISION — RESOLVED: full Flutter. No React.** The companion app is **100% Flutter**, sharing the Hypervolt
> estate's Flutter conventions and design kit (`../ux`). This closes the open Flutter-vs-React-PWA question in
> CLAUDE.md §5 / doc 22 §7. The React/StyleX **back-office desk** (doc 20) is unaffected — desk = web/React,
> companion = Flutter. The two share *design language and brand tokens*, not code.

---

## 0. The job, in one paragraph

The Conduit companion app is the **field surface** for non-desk roles — `retail_sales_agent` (and account
owners), `customer_service_agent`, `fulfilment_agent` — used one-handed on an **iPhone** in a warehouse or on a
customer site, and as a richer two-pane tool on an **iPad**. It must feel like a **Hypervolt app**: built on the
same Flutter stack and `hypervolt_ui_kit` as the customer/installer apps, but **Conduit-branded** (purple accent)
and **numbers-forward** (units, money, coverage %). It is **offline-tolerant** (patchy field signal), **data-layer
aware** (never shows money a role can't see), and **server-authoritative** (the device proposes; the backend
re-validates). The hardest, highest-value flow is the **weekly forecast capture** (doc 08 §3); the most
iOS-specific are the **camera scan** (serial/QR) and **dispatch** flows.

---

## 1. Built the Hypervolt way — stack & foundation (mirror `../ux`)

The app conforms to the estate's Flutter monorepo conventions (`/Users/flavian/projects/hypervolt/ux`, a Melos
workspace). **Reuse, don't reinvent.**

| Concern | Choice (mirrors `ux`) |
|---|---|
| Flutter / Dart | **Flutter 3.38.x (pin via `.fvmrc`)**, Dart ^3.9 |
| Workspace | A new **`apps/conduit_companion`** in the `ux` Melos workspace (so it shares packages), **or** a standalone repo that depends on the published `hypervolt_ui_kit` + auth/api packages. Prefer the workspace — it's how the estate builds. |
| Design kit | **`hypervolt_ui_kit`** (tokens, `HypervoltTheme`, `HypeColors`, `HypeCard`, `HypeListTile`, `Button`, `Spacing`, `ScrollableScaffold`, `Responsiveness`, `AdaptiveTwoColumnSliver`, `ScreenSize`) — **as a dependency**, with a Conduit theme variant (§3). |
| State | **`flutter_bloc`** (+ `hydrated_bloc` for persisted state) over repositories that expose **`Stream`s** for reads and `Future` commands; `equatable` value objects + `copyWith`; `rxdart` where a `BehaviorSubject` fits. |
| Routing | **`go_router`** — declarative, nested navigators, redirect guard for auth; routes split per feature module; `rootNavigatorKey` for sheets/dialogs. |
| Networking | **`dio`** with an `AuthorizationInterceptor`; a hand-written **typed `ConduitApiClient`** wrapping responses (the estate doesn't codegen Dart clients — it wraps Dio). *Conduit improvement:* generate the Dart models from Conduit's OpenAPI where practical, but keep the Dio call layer hand-written to match house style. |
| Auth | **Keycloak** via the estate `auth_keycloak`/`auth_repository` pattern (OAuth2, token refresh in the interceptor, `AuthStatus` stream); **biometric/PIN unlock** on resume; tokens in **Hive** (encrypted box). |
| Local store / offline | **Hive** (draft + outbox queue + last-known cache) + **`hydrated_bloc`** (UI state like theme/scenario). |
| i18n | **`intl` + ARB**, `flutter gen-l10n`. Conduit needs the **full 15-locale set incl. CJK + Thai** (doc 08 §0) — a superset of the `ux` 9; fonts must cover Japanese + Thai + European diacritics. `context.l10n.<key>`. |
| Lint / format | **`very_good_analysis`**, `page_width: 100`, exclude `*.g.dart` — identical to `ux/analysis_options.yaml`. |
| Testing | **`bloc_test` + `mocktail`** (BLoCs/repos), **widget tests** with pump helpers, **golden tests** for the design-system widgets and the key adaptive layouts (compact vs expanded), `integration_test` for the forecast + order-place + offline-sync happy paths. |
| Bootstrap / DI | The `ux` `bootstrap()` pattern: init Hive → build Dio + interceptors → repositories → `MultiBlocProvider`/`RepositoryProvider` → `runApp`. `main_development.dart` / `main_production.dart` flavors. |
| CI / build | Codemagic for IPA/APK (as `ux`); `melos run generate / format-verify / test-all`. |

**House Dart conventions** (from `ux`): files snake_case, classes PascalCase, routes kebab-case, `lib/src/<feature>/{bloc,view,widgets,models}`, `app/router` split by feature, dartdoc `{@template}/{@macro}`, `EquatableConfig.stringify = true`.

---

## 2. Relationship to the existing specs

- **doc 08** — the screens (S1–S22), their data bindings and states. **Authoritative for behaviour.** This doc does not restate them; it adds the design-system, adaptive-layout and architecture layer and references screens by their S-number.
- **doc 05** — data layers. The app renders **only what the server projects** (money collapses when absent). §4 below makes this a widget contract.
- **doc 22** — the design handoff front-door (desk + companion). doc 22 §7 flagged the companion decision as OPEN; it is now **resolved to Flutter** (this doc). Brand tokens are shared desk↔companion so a single Conduit design language spans both.
- **doc 06 / 02 / 04** — API/data/logic truth. Don't invent endpoints; §7 lists the genuine **backend gaps** the app needs filled.

---

## 3. Brand & theming — Conduit purple over the Hypervolt kit

The Hypervolt customer app's primary is **green (#42B882)**; **Conduit's accent is `#962DFF` (purple)** (CLAUDE.md
§5, doc 20/08). The companion app therefore ships a **Conduit theme variant of `HypervoltTheme`**, not a fork:

- **Reuse the kit's machinery** — `HypeColors`/`ColorScheme.fromSeed`, the surface-container elevation levels, the
  `WidgetColor` (normal/hover/active/focus) states, dark-mode plumbing, the `Spacing` 8dp grid, Material-2021
  typography with accessible text scaling (clamp ~0.85–1.6 for a dense operator app).
- **Re-seed with Conduit purple** — `seedColor = #962DFF`; keep the kit's **semantic status palette consistent
  with the desk and doc 08**: `ok` green = on-track/matched, `warn` amber = attention, `danger` red =
  blocked/exception/over-limit. Status is the *only* place colour carries meaning; everything else is the neutral
  dark scale + the purple accent.
- **Dark mode first-class and default** (field use, glare); light mode supported. Theme persists via the
  `UserPreferencesRepository.themeMode` stream (the `ux` pattern).
- **Numerals-forward** — tabular/lining figures so unit and money columns align; large legible totals; the content
  *is* the numbers. Money always flows through `MoneyOrHidden` (§4).
- Deliver the Conduit tokens as a small `conduit_theme` layer (or a theme extension) **on top of**
  `hypervolt_ui_kit`, mirrored 1:1 into the Figma library the design pass produces (so desk + companion share one
  token source — doc 22 §6).

---

## 4. iOS / iPad adaptive design (the core of this spec)

One Flutter codebase, **three layout classes** driven by `hypervolt_ui_kit`'s `ScreenSize` / `Responsiveness`
breakpoints (width-based, **not** device-idiom-based — critical for iPad multitasking, see below):

| Class | Width | Typical device context | Navigation | Content |
|---|---|---|---|---|
| **Compact** | `< 640` | iPhone portrait (all sizes), iPad Slide-Over | **Bottom `NavigationBar`** (role-gated tabs), single-column, push navigation | one pane; detail = full-screen push; sticky `SubmitBar` above the keyboard + home-indicator safe area |
| **Medium** | `640–960` | iPhone landscape, iPad portrait, iPad Split-View ½ | **`NavigationRail`** (collapsed) | single wide column or a light two-pane where it helps |
| **Expanded** | `≥ 960` | iPad landscape / full-screen, iPad Pro | **`NavigationRail`** (extended) + **`AdaptiveTwoColumnSliver` master-detail** | list/board on the left, detail/editor on the right; no full-screen push for detail |

**Same widgets across classes** — the screen composes from the kit and re-flows; we do **not** build separate
phone/tablet screens (doc 08 §13: "one Flutter codebase; no platform-specific screens").

**iOS / iPadOS specifics the design must honour:**
- **Safe areas** — respect notch, Dynamic Island, and the home indicator; the sticky `SubmitBar` and bottom nav
  sit above the home-indicator inset; full-bleed headers extend under the status bar intentionally.
- **Dynamic Type** — honour the OS text-size setting (clamped via the kit's accessible text-scaler); layouts must
  not break at the largest step — numeric keypads and `SubmitBar` totals stay visible.
- **iPad multitasking** — Slide Over / Split View hand the app **arbitrary widths**; never branch on
  `Platform.isIPad`/device idiom — branch on the **width class** so a half-width iPad renders the compact layout
  correctly. Support **external keyboard** (tab order, ⌘-shortcuts for power capture) and **pointer/trackpad**
  hover states on iPad.
- **Gestures & feel** — native iOS swipe-back on pushed routes; platform scroll physics (`BouncingScrollPhysics`);
  pull-to-refresh on every list; **haptics** on submit/scan-success/exception-hold (light/medium impact).
- **Biometrics** — Face ID / Touch ID unlock on resume (`local_auth`), gated by the Keycloak session.
- **Camera / scanner** — serial/QR scan (S18, S20) uses the camera with a clear permission pre-prompt; a manual
  type-in fallback always exists; torch toggle for warehouse low-light.
- **Orientation** — capture-heavy phone screens (S4 forecast, S14 order) may **lock portrait** for thumb reach;
  iPad is **unlocked** (landscape is the two-pane home).
- **Push** — APNs registration + a notification-tap deep-link map (S21) — see the backend gap in §7.
- **Offline at the OS level** — graceful on `NSURLErrorNotConnectedToInternet`; the offline banner + queue (§5) is
  always-on infrastructure, not an error state.

**Touch & reach:** minimum 48dp targets (`Spacing.s48`); primary actions in the thumb arc; destructive/rare
actions out of it; the numeric **keypad** for forecast/quantity entry is large, fast, and one-handed.

**Per-flow adaptive treatment** (the screens that change most across classes — full list in doc 08):
- **Home (S2)** — compact: stacked action cards; expanded: a 2-up card grid + a persistent "what needs me" rail.
- **Forecast cycle → entry (S3→S4)** — compact: account list → push to the per-account SKU keypad; **expanded:
  two-pane** (account list left, live SKU entry right) so an owner rips through their book without losing context.
  The `ScenarioStepper` (P20/P50/P80) and sticky unit-total `SubmitBar` are constant.
- **Pipeline (S12)** — compact: vertical stage-grouped list; expanded: horizontal **kanban** columns.
- **Order create (S14)** — compact: stepped (party → lines → review); expanded: line-builder left, **live
  quote/commission/ADLP panel** right (recomputes as lines change).
- **Lookup/Scan & Fulfil (S18, S20)** — camera scanner full-bleed on phone; on iPad, scanner + the
  serial/genealogy or dispatch detail side-by-side.
- **Order/Account/Serial detail (S10, S15, S18)** — expanded shows them in the right pane of their list; compact
  pushes full-screen.

---

## 5. Architecture & the field realities

**Layered like `ux`:** `view` (widgets) → `bloc` (per feature) → `repository` (streams + commands) →
`ConduitApiClient` (Dio) / local stores (Hive). No business logic in widgets; no API calls in widgets.

**Offline & sync — the field contract (doc 08 §0):**
- **Draft + outbox queue in Hive.** Forecast drafts (S4) and order drafts (S14) persist locally and **queue** when
  offline; a persistent **`OfflineBanner`** shows pending count; on reconnect the queue drains and the
  **server re-validates** (pricing, ADLP, credit, allocation) — **server wins**, surfaced as a clear post-sync
  `SyncToast`/diff, never a silent overwrite.
- **Idempotency is mandatory** — every queued mutation carries a **client-generated idempotency key** so a
  redelivered submission is a no-op server-side (this is a backend gap, §7). Mirrors Conduit's own at-least-once /
  deterministic-id philosophy (the outbox, doc 03).
- **Read screens cache last-known** with an **"as of <time>" stamp**; never present stale data as live.
- **Conflict surfacing** — if the server rejects on sync (price moved, account reassigned, stock gone), show the
  **before/after diff** and the action needed, as an actionable banner (not a vanishing toast).

**Data-layer awareness (doc 05) as a widget contract:** the response omits layers the principal lacks; the UI
renders **only what arrives**. `MoneyOrHidden` collapses to *nothing* when its value is absent (no `£0`, no blank,
no greyed lock). A `volume`-only user sees units/coverage and **no money widget anywhere** (acceptance, doc 08
§13). `LayerGuard` shows children only if the layer key is present — a layout helper, never the authority.

**Server-authoritative:** the device never decides pricing, ADLP band, credit, allocation, or approval. The
companion app **cannot approve** an ADLP exception (CEO-on-desk only, S16) — it assembles justification and shows
status. All authorisation is server-enforced; the UI mirrors it for layout and surfaces `403/409/422` clearly.

**i18n:** 15 locales incl. CJK + Thai; user-preference locale for the app, per-account `preferred_locale` for
customer-facing content (quotes, product names); all numbers/currency/dates per locale; pseudo-loc in CI to catch
truncation; no RTL in scope.

---

## 6. Component kit (Conduit additions over `hypervolt_ui_kit`)

Reuse the kit's `HypeCard`, `HypeListTile`, `Button`, `ScrollableScaffold`, `Surface`, `Banner`, `BaseSheet`,
`SlidingSegmentedControl`, `ProgressBar`, `Shimmer`, `TitleBar`. Add the **Conduit-specific, layer-aware**
components (named in doc 08 §0, designed against the kit):
`AccountCard`, `ScenarioStepper` (built on `SlidingSegmentedControl`), `SkuQtyRow` (numeric keypad/stepper),
`MoneyOrHidden`, `UnitsBadge`, `StatusChip` (order/deal/forecast/exception states — one colour mapping shared with
the desk), `CoverageBar` (forecast vs pipeline vs shipped vs activated), `SubmitBar` (sticky, safe-area + keyboard
aware), `OfflineBanner`, `SyncToast`, `ScanButton` (camera), `ConfirmSheet`. Each needs its full state matrix
(default / loading-skeleton / empty / error / **offline-queued** / **layer-absent**) — built as a Figma library
component in the design pass (doc 22).

---

## 7. Backend features the companion app needs (gaps to build)

The field flows' *domain logic* mostly exists (H6Q M11, orders M4, pricing M3, commission M5, warranty M8,
returns M9b, dispatch/allocation M6, serials/activation M7–M8). What's missing is the **field-facing edge** — REST
surfaces, the mobile push pipeline, and the offline-safety primitive. **Spec now; build per their milestone (not
before the design pass).**

1. **Offline idempotency keys (P0 — the offline story depends on it).** Accept a client-supplied
   `Idempotency-Key` (header or body field) on the field mutations — `POST /orders`, `POST /h6q/my-forecasts/{id}/submit`,
   `POST /adlp/exceptions/{id}/submit`, `POST /warranty/claims`, `POST /orders/{id}/refund`,
   `POST /orders/{id}/dispatch` — so a queued submission replayed on reconnect is a guaranteed no-op (dedupe row +
   return the original result). Conduit already has the deterministic-id pattern internally (outbox); this exposes
   it at the edge.

2. **Push notifications (P0 for S21).** A **device-registration** endpoint (`POST /devices` — token, platform,
   user) and an **APNs/FCM dispatch** path that consumes the existing notification events (the `notification`
   domain + H6Q notifications already exist) and pushes to registered devices, with a **deep-link payload** (screen
   + entity id) the app routes on tap. None of the device/push layer exists today.

3. **Field-facing REST surfaces to confirm/expose.** doc 08 binds to these; several have **no route file** yet —
   verify and expose with field scoping (`me`/own) + data-layer projection:
   - **Commission read** — `GET /commission/entries?agent_id=me&period=`, `GET /commission/statements/{me}` (S7–S8). *No `commission` route file found.*
   - **Catalogue browse** — `GET /catalogue?channel_id=&market_id=&currency=` (S4, S17). *No `catalogue` route file* (pricing quote exists in `PricingRoutes`).
   - **Warranty claim** — `POST /warranty/claims`, and the serial/warranty read for S18. *Domain exists (M8); no route file.*
   - **Returns / RMA + refund** — `POST` to raise an RMA and the refund path for S19. *Domain exists (M9b); confirm the field surface.*
   - **Allocate / dispatch** — `POST /orders/{id}/allocate`, `POST /orders/{id}/dispatch` (S20). *Domain exists (M6); confirm route exposure + the serial-scan contract (422 if a serialised line lacks scanned serials).*
   - **Serial lookup** — `GET /serials/{serial}`, `GET /batches/{batch_no}/serials` (S18). *Confirm.*
   - **My-forecasts** — `GET /h6q/my-forecasts`, `POST …/submit` (S3–S5). *Present (H6QRoutes).*

4. **A mobile hydration endpoint (P1, optimisation).** One scoped call to seed Home (S2) — forecast-due counts,
   own commission summary, orders-needing-attention, coverage — so the dashboard is one round-trip on a cold,
   flaky connection rather than 4–5.

5. **Notification feed read (P1).** `GET /notifications?user=me` for the in-app list/badge (S21), backing the
   push pipeline above. *Notification domain exists; confirm the read endpoint + unread/ack semantics.*

6. **Reseller / scoped service tokens (P2, later).** If any companion flows run as a service principal; otherwise
   per-user Keycloak is sufficient. (doc 19 §B.1 reseller signing material.)

> These are net-new **edge** features, not new subsystems — each is a thin REST/consumer layer over logic that
> already exists and is tested. They are the difference between "the engine can do it" and "a phone can call it."

---

## 8. Deliverables, acceptance & build order

**Design-pass deliverables (Claude Design, doc 22 workflow — Figma + the kit):**
1. The **Conduit theme variant** (purple over `hypervolt_ui_kit`) as Figma variables/modes mirroring the Dart tokens.
2. The **component kit** (§6) with full state matrices, including the **compact ↔ expanded** variants of each.
3. **Adaptive screen designs** for doc 08's S1–S22 at **all three width classes**, with the iOS/iPad treatments in §4 (safe areas, Dynamic Type at max step, scanner, two-pane).
4. A short **design-system README** for the Flutter engineers (token usage, breakpoints, component index).

**Acceptance (the app is "done the Hypervolt way" when):**
- It depends on `hypervolt_ui_kit` and reads identical to a `ux` app in structure, lint, and patterns.
- Every screen renders correctly compact (iPhone portrait), medium (iPad ½/landscape phone) and expanded (iPad
  landscape), including under iPad Split View at arbitrary widths and at the largest Dynamic Type step.
- The **forecast loop works fully offline** and reconciles on sync (server-wins, diff shown); new SKUs appear
  without an app update.
- A `volume`-only user sees **no money widget anywhere**; an out-of-band discount visibly holds the order
  (`pending_ceo`) and zeroes that line's commission preview; the app **cannot approve** it.
- Dark mode is first-class; CJK/Thai render with correct fonts; biometric unlock + camera scan + push deep-links work on a real iPhone and iPad.

**Build order (after the design pass — do NOT start before it):**
1. Foundation: Melos app + `hypervolt_ui_kit` + Conduit theme + bootstrap/DI + auth (S1–S2) + the offline/queue +
   layer-aware widget kit.
2. **Forecast loop (S3–S6)** — highest-frequency, highest-value; proves offline + adaptive two-pane.
3. Commission (S7–S8).
4. Accounts/CRM (S9–S11) + Deals (S12).
5. Orders + quote (S13–S17).
6. Lookup/Scan (S18) → role surfaces: Service (S19), Fulfil (S20).
7. Notifications/profile (S21–S22).
> Thread the **backend gaps (§7)** ahead of the screens that need them (idempotency + push + the missing REST
> surfaces first).

---

## 9. Open sub-decisions (resolve in/with the design pass)

- **Workspace vs standalone repo** — strongly prefer joining the `ux` Melos workspace (`apps/conduit_companion`)
  so `hypervolt_ui_kit` + auth/api packages are shared source, not published artifacts. Confirm with the estate
  Flutter owners.
- **Web target** — Flutter builds web too; the companion is **iOS/iPad-first**, Android second, web only if a
  field-on-laptop case appears (the desk already covers web). Not a launch target.
- **Generated Dart models from OpenAPI** — adopt for DTOs (single source of truth with the backend) while keeping
  the Dio call layer hand-written (house style). Confirm tooling fit.
