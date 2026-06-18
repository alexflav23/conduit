# 38 — Interactive user manual & training helpbook

**Purpose.** Conduit's feature surface is now large (≈30 desk screens across 6 domains + the engines behind
them). This specs an **in-app, interactive user manual** — a living helpbook built into the desk — that doubles
as the **training curriculum** for onboarding senior teams (and, later, the wider business) ahead of takeover.

**Stance (governed by [[no-fake-data-constraint]]):** the manual documents **what Conduit actually does**, screen
by screen, honestly marking what is live vs. in-progress vs. shadow-only. It is **anti-rot by construction** — a
chapter is keyed to a real desk route, and a test fails the build if a screen has no chapter (and vice-versa), so
the manual cannot drift from the product. Content is git-versioned and reviewed in the **same PR** as the feature.

Relationship: [`20_BACKOFFICE_DESK`](./20_BACKOFFICE_DESK.md) is the *screen spec* (what to build); [`27_UI_FEATURE_MAP`](./27_UI_FEATURE_MAP.md) maps features to screens; **this is the *user-facing explanation* of those screens** + the training paths over them.

---

## 1. What "interactive" means here (scope, in build order)

1. **Reference helpbook (M-Help.1)** — a `Help` screen in the desk: a searchable, sectioned manual with one
   **chapter per feature** (mapped to the nav), each: overview · who it's for · key concepts · step-by-step tasks
   · related screens · **status badge** (live / partial / shadow-only). The backbone everything else hangs off.
2. **Contextual help (M-Help.2)** — a `?` affordance on every feature screen that deep-links to that screen's
   chapter (`/help/<chapterId>`); `⌘K` resolves help chapters alongside screens/customers (extend the palette).
3. **Guided tours (M-Help.3)** — opt-in step-throughs that highlight real UI elements in sequence ("place a
   compliant order", "close a period", "triage a quarantined inbound row"). Non-mutating by default; a tour that
   demonstrates a write runs against the dev/shadow projection only.
4. **Training curriculum + export (M-Help.4)** — role-based **learning paths** (ordered chapter sequences) with a
   "mark complete" tracker; a printable/exportable PDF of any path or the whole book for offline/onboarding decks.

---

## 2. Architecture (fits the hyperstore/desk stack)

- **Home:** a new `help` tab + `Help` page in `conduit-desk/` (added to `PAGES`/`GROUPS` like any screen), route
  `/help` and `/help/:chapter`. No new infra — same React Router v6 + StyleX/desk.css + kit (`PageHead`, `Card`).
- **Content model:** structured TypeScript modules under `conduit-desk/src/help/content/` — **one file per chapter**,
  bundled with the app (no DB, no fetch; works offline; reviewable as code). Shape:
  ```ts
  interface ManualChapter {
    id: string;            // stable slug, e.g. 'order-desk'
    route: TabId | null;   // the desk screen it documents (null = a concept chapter)
    section: string;       // matches the nav group: 'Commerce' | 'Forecasting' | …
    title: string;
    audience: Role[];      // who this is for (drives training paths + role filtering)
    status: 'live' | 'partial' | 'shadow' | 'planned';  // honesty badge (no-fake-data)
    summary: string;       // one-paragraph "what this is"
    concepts?: { term: string; def: string }[];         // key vocabulary
    tasks?: { title: string; steps: string[]; note?: string }[];  // step-by-step how-to
    related?: TabId[];     // cross-links
    seeAlso?: string[];    // spec doc refs for the curious (e.g. 'doc 14 §1')
  }
  ```
- **Index + search:** an in-memory index over all chapters (title · summary · concepts · task titles); client-side
  fuzzy search (same approach as the `⌘K` palette). Grouped by `section` to mirror the nav exactly.
- **Rendering:** a small dependency-free renderer (prose + bullet/step lists + concept tables + status badge +
  related-screen chips). No MDX/markdown dep to keep the build lean; rich blocks are structured fields.

---

## 3. Anti-rot governance (the load-bearing rule)

- **Route↔chapter parity test (Vitest):** every `TabId` in `PAGES` must have a `ManualChapter` with that `route`,
  and every chapter's `route` must exist. A new screen with no chapter (or a deleted screen with a stale chapter)
  **fails CI**. This is the same key-parity discipline as i18n (doc 34 P2.1).
- **Status honesty:** the `status` badge is mandatory and reviewed; a chapter describing an unbuilt path must say
  `planned`/`shadow`, never imply it works. Ties to the no-fake-data rule.
- **PR rule:** a feature PR that adds/changes a screen updates its chapter in the same PR (checklist item).
- **`seeAlso`** links the chapter to its spec section so the manual and the spec stay mutually referenced.

---

## 4. Chapter outline (one per screen + concept chapters)

Mirrors the desk nav exactly (the parity test enforces it). **Concept chapters** (`route: null`) come first as the
"how Conduit thinks" primer; the rest are 1:1 with screens.

**Primer — concepts (read first):**
- `getting-started` — the desk shell, nav, context bar (entity·market·period·scenario), `⌘K`, roles.
- `data-layers` — the layer wall (volume/commercial/profitability/commission/inter_entity/pii); why money can be *absent*, not hidden.
- `event-ledger-model` — events → outbox → Pulsar → consumers; the immutable TigerBeetle ledger; replay/audit.
- `golden-record` — the MDM topology: master `party`, source links, serial→owner, order golden record (CO-ref).
- `money-fx-integrity` — typed Money, no float, rounding policy, specific-id costing, FX spot vs hedged.
- `shadow-mode` — what shadow mode is, inbound-never-lost, no outbound, the dual-run review loop (doc 33/36).

**Commerce:** `order-desk` · `deal-desk` · `pricing` · `returns` · `crm` · `reseller-portal`
**Demand / Forecasting:** `demand-h6q` · `flow` · `supply-window` · `shelf` · `forecast-engine` · `forecast-runs`
**Supply & Traceability:** `inventory` · `purchasing` · `batch-genealogy` · `activations` · `warranty-rma`
**Finance:** `finance` · `backlog` · `commission` · `documents` · `lifecycle` · `tax`
**Group:** `intercompany` · `procurement` · `fx-hedging`
**Governance & Admin:** `auditability` · `period` · `sync` · `shadow-validation` · `proof-center` · `access` · `notifications`

Each screen chapter answers: *what it shows · who uses it · the key concepts/vocabulary · the common tasks
(step-by-step) · what's governed (approvals/maker-checker) · related screens · current status.*

---

## 5. Training curriculum (learning paths)

Ordered chapter sequences per role, with a local "mark complete" tracker. Paths reuse chapters, never duplicate.
- **CEO / exec:** getting-started → data-layers → golden-record → demand-h6q → finance → deal-desk (CEO approval) → auditability.
- **Finance:** getting-started → money-fx-integrity → event-ledger-model → finance → documents → tax → period → auditability → shadow-validation.
- **Sales / commercial:** getting-started → data-layers → crm → order-desk → pricing → deal-desk → commission → demand-h6q.
- **Ops / supply:** getting-started → inventory → purchasing → batch-genealogy → activations → warranty-rma → flow/supply-window.
- **Auditor:** getting-started → event-ledger-model → auditability → proof-center → period → lineage.
- **Shadow dual-run owner (now):** shadow-mode → sync → shadow-validation → the inbox/quarantine flow (doc 20 §9b).

Export: any path (or the whole book) renders to a printable PDF for onboarding decks / offline training.

---

## 6. Build slices (test-first; tracked)

- [ ] **M-Help.1 — shell + model + reference chapters.** `Help` screen + `ManualChapter` model + the index/search +
  the renderer + the **route↔chapter parity test**; author the 6 concept chapters + a first vertical (Commerce: order-desk,
  crm, pricing) to prove the pattern. **Accept:** every nav tab resolves (parity test green); search finds a chapter by
  concept; a chapter renders with its status badge.
- [ ] **M-Help.2 — contextual help + palette.** A `?` on each screen → `/help/<chapter>`; `⌘K` resolves chapters.
  **Accept:** the `?` on Order Desk opens the order-desk chapter; `⌘K "place order"` lands on the task.
- [ ] **M-Help.3 — guided tours.** A tour runner highlighting real elements; 3 flagship tours (place an order; close a
  period; triage a quarantined inbound row). **Accept:** a tour steps through the real UI; write-demoing tours touch only dev/shadow.
- [ ] **M-Help.4 — curriculum + export.** Role learning paths + completion tracker + PDF export. **Accept:** a role path
  renders in order, tracks completion, and exports to PDF.
- [ ] **M-Help.content — fill all ~30 screen chapters** (the bulk; one per screen, reviewed against the live UI).

**Status:** M-Help.1 scaffolding in progress (this session). The rest tracked here + in [`36_SHADOW_MODE_PLAN`](./36_SHADOW_MODE_PLAN.md) is feature work, not shadow-track, so it runs in parallel.
