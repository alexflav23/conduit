# 34 — Phase-2 implementation plan (multi-market + external surface)

**Scope (CEO, 2026-06-13):** the back-office is feature-complete and the M-Ingest shadow dual-run is built;
this plans the **Phase-2** work that takes Conduit from "UK-only, internal, Google-auth" to "multi-market,
externally-integrated, reseller-facing." Six workstreams (P2.1–P2.6). The **Flutter companion (M14)** is
explicitly out of this plan (separate `ux` repo, needs the design pass).

> House rule for all of Phase-2: build behind a **seam + fixture** so the code lands and is tested now, and the
> **live wiring lights up when credentials arrive** — exactly how the M-Ingest connectors were built. Each item
> notes the external credential/provider it ultimately needs.

---

## P2.1 — 15-locale i18n (#131)  ·  effort: M  ·  external: none
**Goal:** the desk + customer-facing strings render in the full 15-locale set (incl. CJK + Thai); the back end
stays locale-aware where it already is (documents carry `locale`; money widgets already collapse by data-layer).
**State:** desk is greenfield — no i18next, no `locales/`. Document rendering already resolves by `locale`.
**Slices (test-first):**
1. i18next + react-i18next wired into the Vite/StyleX app; `<LocaleProvider>`; locale from the route prefix
   (React Router v6 locale-prefixed routes) with a fallback chain; persisted in session.
2. The namespace + key extraction: move the desk's hard-coded strings into `locales/<lng>/<ns>.json`; a Vitest
   guard that every key in the base locale (`en`) exists in every other locale (no missing-key drift).
3. Seed the 15 locales (en, then the market set incl. de/fr/es/it/nl/pl/sv/da/no + ja/zh/ko + th); machine-draft
   then human-review; `Intl` number/date/currency formatting per locale (CJK/Thai digit + date forms).
4. RTL-readiness check (none in the 15 today, but the layout must not assume LTR-only); a Playwright run per a
   representative non-Latin locale (ja) proving the desk renders.
**Acceptance:** switching locale re-renders every surface; no missing-key; numbers/dates/money format per locale;
the key-parity Vitest gate is green and CI-enforced.

## P2.2 — External tax adapters (#132)  ·  effort: M  ·  external: Avalara/TaxJar/Stripe-Tax creds
**Goal:** for nexus-gated jurisdictions (US/CA at scale), route tax determination to an external calc engine;
the rate-table stays the default everywhere else.
**State:** the seam already exists — `TaxProvider` (the adapter interface), `RateTableTaxEngine` (default impl),
`TaxDeterminationService`, `TaxAdminRepo`, effective-dated `tax_rate` (doc 16). The external path is "a
`tax_routing` row + an adapter away" (memory: tax-engine-design).
**Slices (test-first):**
1. `tax_routing` table (jurisdiction → provider) + the router in `TaxDeterminationService` selecting
   rate-table vs external by routing row (default rate-table).
2. `AvalaraTaxProvider` (and/or TaxJar) implementing `TaxProvider` behind an `AvalaraApi` seam (ember + the
   API key), fixture-tested against canned AvaTax responses — quote ⇒ line/jurisdiction breakdown into the
   existing tax-quote shape. **No-float; Money throughout.**
3. Fallback + reconciliation: on provider error, fail closed (reject, don't silently rate-table a nexus
   jurisdiction); a control reconciling provider-returned tax vs posted VAT/sales-tax.
4. Nexus gating: a `nexus_profile` check so the external path only fires where we have nexus.
**Acceptance:** a US ZIP quote routes to the provider and returns state+county+district to the penny (fixture);
UK stays rate-table; provider-down fails closed; the tax-reconciliation control ties.

## P2.3 — Per-jurisdiction document templates (#133)  ·  effort: M  ·  external: none (legal content review)
**Goal:** invoices/credit-notes/proformas/customs docs carry the correct **per-locale + per-jurisdiction** legal
content (VAT wording, reverse-charge notices, customs declarations).
**State:** resolution already exists — `document_template (document_type, jurisdiction, locale, status,
effective_from)` + the FOP renderer; today only the UK/en templates are seeded.
**Slices (test-first):**
1. Template authoring per (document_type × jurisdiction × locale) for the year-1 + roadmap markets; the
   resolver already picks the most-specific effective template — seed the matrix.
2. The required legal fields per jurisdiction (reverse-charge statement, VAT/GST id, EORI/customs for exports)
   as render-model gates (a finalise gate rejects a doc missing a jurisdiction-required field).
3. Determinism preserved (byte-stable render per the M13-Docs proof); a test per representative jurisdiction.
**Deps:** P2.1 (locales) for the locale axis. **Acceptance:** a DE invoice renders German + the reverse-charge
notice; a US export carries the customs declaration; a missing required field blocks finalise; render is byte-stable.

## P2.4 — Keycloak federation (#135)  ·  effort: M  ·  external: a Keycloak realm
**Goal:** production auth moves from Google-only to **Keycloak OIDC with Google as an IdP inside Keycloak**;
the doc-05 policy layer stays *after* JWT verification.
**State:** `GoogleTokenVerifier` + `AuthService` today (Google ID-token verify, `hd=hypervolt.co.uk`). No JWKS/
Keycloak verifier. Terraform already reserves `<env>/keycloak-configuration/conduit-api/*`.
**Slices (test-first):**
1. `KeycloakJwtVerifier` (auth0 `jwks-rsa` + `java-jwt` against the realm's JWKS certs — the Athena pattern):
   verify signature, issuer, audience, expiry; map `sub` → the Conduit principal. Fixture-tested with a
   generated keypair + JWKS (no live Keycloak).
2. `AuthService` selects verifier by token issuer (Keycloak realm vs Google) so both work during cutover; dev
   `dev:<id>` tokens stay non-prod-only.
3. Desk sign-in via Keycloak (OIDC redirect) behind a flag; Google remains the IdP federated *inside* Keycloak,
   so the user experience is unchanged.
**Deps:** none hard (Google works meanwhile). **Acceptance:** a Keycloak-issued JWT verifies against the JWKS
and resolves a principal with its grants; an expired/wrong-audience token is rejected; Google still works.

## P2.5 — Reseller API + rate limiting (#134)  ·  effort: L  ·  external: reseller tenant model
**Goal:** a scoped, externally-facing **reseller API** (doc 19 §A.1) with **per-principal rate limiting +
job-admission** (§B.4), so a reseller tier degrades before core.
**State:** none — no `/reseller/*` routes, no limiter.
**Slices (test-first):**
1. **Rate limiter first (independent, reusable):** a per-principal token-bucket tapir interceptor (429 +
   `Retry-After`) + a bulk/export job-admission queue; covers the whole API, not just reseller. Property-tested
   (bucket never exceeds capacity; refills correctly).
2. Reseller **scoped-JWT** issuance + a `reseller`/`reseller_token` model; the policy layer already scopes by
   entity/market/channel/sector — a reseller principal is just a tightly-scoped grant set.
3. The reseller surface itself: a read-mostly subset (catalogue/pricing-for-me, place-order, my-orders,
   my-invoices) under `/api/v1/reseller/*`, scope-walled to the reseller's own data, layer-projected.
**Deps:** P2.4 (scoped JWT issuance is cleaner on Keycloak) — but the limiter (slice 1) is independent and can
land first. **Acceptance:** over-rate calls get 429 before core is touched; a reseller sees only its own
catalogue/orders; cross-reseller access is absent from the payload.

## P2.6 — Notification channels (#136)  ·  effort: M  ·  external: SES/FCM (email/push) creds
**Goal:** turn business events into **push / email / in-app** notifications.
**State:** `NotificationRepo` + the model exist (NotificationSuite); the event spine is there; **no sender**.
**Slices (test-first):**
1. A `NotificationConsumer` subscribing the relevant `conduit.*` events → a `notification` row (in-app, already
   modelled) — idempotent on event_id. In-app needs no external provider, so it lands first + fully tested.
2. Channel senders behind a `NotificationChannel` seam: `EmailChannel` (SES) + `PushChannel` (FCM), fixture-
   tested; per-user channel preferences; **shadow-mode aware** (muted in the dual-run via `ShadowGuard`, since
   email/push are outbound).
3. Templated, localised notification content (reuses P2.1 i18n).
**Deps:** P2.1 (localised content), ShadowGuard (already built). **Acceptance:** a qualifying event creates an
in-app notification idempotently; email/push dispatch through the seam (fixture); outbound channels are muted in shadow.

---

## Sequencing & roadmap
A dependency-aware order (each is independently shippable; this minimises rework):

1. **P2.1 i18n** — foundational for P2.3 + P2.6 content; desk-side, no creds. *Start here.*
2. **P2.4 Keycloak verifier** — auth foundation; cleaner reseller JWTs later; fixture-testable now.
3. **P2.2 tax adapters** — independent backend; unblocks US/CA money correctness.
4. **P2.3 doc templates** — after i18n (locale axis); legal-content review in parallel.
5. **P2.5 reseller API + rate limiting** — limiter slice independent (can land any time); reseller surface after P2.4.
6. **P2.6 notifications** — in-app slice independent; email/push after P2.1 content + provider creds.

| # | Workstream | Effort | External dep | Blocks nothing critical for |
|---|---|---|---|---|
| P2.1 | i18n | M | — | UK go-live (UK is en) |
| P2.2 | tax adapters | M | Avalara/TaxJar key | UK go-live (UK rate-table) |
| P2.3 | doc templates | M | legal review | UK go-live (UK template seeded) |
| P2.4 | Keycloak | M | Keycloak realm | UK go-live (Google works) |
| P2.5 | reseller API + rate-limit | L | reseller model | UK go-live (internal-only) |
| P2.6 | notifications | M | SES/FCM | UK go-live (nice-to-have) |

**None of Phase-2 blocks a UK back-office go-live** — it is the multi-market + external-surface expansion. Each
workstream is built seam-first/fixture-tested so it lands green before its credentials exist, and is enabled by
config/creds at rollout. Estimated total: ~5 M-efforts + 1 L — comparable in size to the M-Ingest engine just shipped.
