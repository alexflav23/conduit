# 10 — Remaining to Plan (backlog)

What is **not yet** in the pack, so nothing lives only in conversation. Grouped by kind; each item notes scope, priority, and what it blocks. Items here are deliberately deferred — the spine (00–07) plus the app (08) are buildable without them, but several are required before specific milestones or before go-live.

Legend — **P1** = blocks go-live or a Phase-2/3 milestone · **P2** = needed for completeness/scale · **P3** = polish. "Blocks" = the milestone in 07 that needs it.

---

## A. Deep-dive documents to write (same template as 02–05)

| Doc | Covers | Priority | Blocks |
|---|---|---|---|
| **09_RETURNS** | First-class returns/RMA: types (full unit, part-only/component, multi-unit, DOA, warranty replacement, goodwill), lifecycle state machine, disposition routing (restock/refurbish/scrap/return-to-supplier), serial lifecycle + genealogy, ledger reversal at batch cost, commission claw, replacement-order issuance, maker-checker | **P1** | M9b |
| **11_CRM** | Flexible **party/role model** now in doc 02 §C (one `party`, data-driven `party_type`, attachable `billing_profile`/`credit_profile`; installers/wholesalers/branches/individuals as parties; partner roles; per-branch billing/credit/stats — CEF pattern). Deep-dive covers: deal pipelines + stages, deal→order conversion, account-history projection, ownership model, party **merge/dedupe**, promote-to-billable validation policy per jurisdiction/type, and **consignment-stock-at-branch** (if real) | P2 | M4 depth |
| **12_H6Q** | The forecasting subsystem at full depth (it has outgrown the §): weekly cycle engine, versioned submissions, bottom-up rollup, branch/agent aggregation, scenario toggles, Hyperview source, accuracy, board layout | P2 | M11 depth |
| **13_INTERCOMPANY_TAX** | Procurement chain (operating ← Singapore ← Luxshare), transfer-pricing methods + documentation, elimination, import VAT/duty — joins with §B tax engine | P2 | M12 |

---

## B. Parked functional areas (genuinely absent, not just thin)

| Area | Scope | Priority | Blocks |
|---|---|---|---|
| **Migration & cutover runbook** | Field-level source→target mapping (MRPeasy / Ghost Busters / Athena), opening-balance derivation into TigerBeetle, idempotent backfill, dual-run reconciliation, validation, rollback. **Biggest practical risk — cannot go live without it.** ✅ **Specced in [18_MIGRATION_CUTOVER](18_MIGRATION_CUTOVER.md).** | **P1** | M10 |
| **Tax & customs engine** | VAT determination (place of supply, B2B reverse charge, EU vs ROW, import VAT), **US destination sales tax (per-state/county, economic nexus) and Canada GST/HST/PST**, CH/NO non-EU, HS/commodity codes, Intrastat / EC sales lists. Multi-jurisdiction and legally exact — **almost certainly a tax-calc integration (Avalara / TaxJar / Stripe Tax)** rather than hand-rolled rates, especially for US/CA. | **P1** | M12/M13 |
| **Localization / i18n** | Full localization across the supported locales (15 languages incl. CJK + Thai): app strings (Flutter ARB), `product_translation`, localized document templates, per-locale number/currency/date formatting, locale fallback chain. Reference data (`market`/`currency`/`locale`) is seeded in doc 02 §A. | **P1** | M14 / documents |
| **Document generation** | Invoices, credit notes, proformas, packing lists, **commercial invoices for customs**, statements: templates **per locale + per jurisdiction legal content**, numbering schemes, PDF. Legally required artifacts. | **P1** | M13 |
| **Back-office desk spec (React/TS)** | Screen spec (like doc 08) for the desk: pricing governance/ADLP, permission builder, Deal Desk + CEO approval, full H6Q board, ledger/finance views, supply planning, admin, **and the Auditability Center (doc 14 §6 — controls register, reconciliation dashboard, period-close board, lineage explorer, money/time panels, audit export)**. Roughly half the UI and the more complex half. | **P1** | Phase 2–3 UI |
| **Notifications model** | Channels (push/email/in-app), templates, per-user preferences, digests; the consumer that turns events into notifications. | P2 | M14 |
| **Search model** | What is searchable (orders, accounts, serials, deals), indexing strategy, scoping/projection of results. | P2 | — |
| **Reporting & exports + Horizons feed** | Standard reports, export formats, layer-respecting; the exact units→revenue→COGS→GP feed contract into Horizons. | P2 | M13/M14 |

---

## C. Flow-level gaps inside existing modules (data exists, behaviour unspecced)

> These are now tracked as **S5 (depth & flow backlog)** in [36_SHADOW_MODE_PLAN](36_SHADOW_MODE_PLAN.md) — spec-first per owning module, built when that module's live stream lands.

| Item | Scope | Priority | Blocks |
|---|---|---|---|
| **Warranty claim lifecycle** | raise → assess → approve → repair/replace/refund → close; replacement unit starts its own warranty (provision register exists; the *claim* flow doesn't) | P2 | M8 depth |
| **Kits / bundles** | kit pricing, assemble/disassemble stock, kit serialisation, BOM relief | P2 | M3/M6 |
| **Blanket / standing agreements** | header-level call-off **above** orders (vs the current line-level `delivery_tranche`) — confirm whether this pattern is real for you; additive if so | P2 | M4 |
| **Allocation-priority policy** | rule for who gets stock when short (by date, order age, customer tier, channel) — currently "configurable", not defined | P2 | M6 |
| **Catalogue lifecycle** | NPI/new-product introduction, SKU supersession/EOL, ongoing `mrp_sku` mapping maintenance | P3 | M3 |

---

## D. Non-functional / cross-cutting (needed for "Nasdaq-grade")

| Item | Scope | Priority |
|---|---|---|
| **NFR doc** | SLAs/latency budgets, throughput, availability, RPO/RTO, scale assumptions (orders/day, events/sec, units under management), retention/archival. ✅ **Specced in [19_NFR_SECURITY_OPS](19_NFR_SECURITY_OPS.md) Part A.** | **P1** |
| **Security beyond auth** | secrets management, encryption at rest/in transit, **GDPR erasure/DSAR procedure** (PII-in-log strategy flagged in 01 §3a; the *procedure* isn't written), rate limiting, threat model, **SOX controls documentation**. ✅ **Specced in [19_NFR_SECURITY_OPS](19_NFR_SECURITY_OPS.md) Part B.** | **P1** |
| **Ops / observability / DR** | alerting strategy; the **DLQ-replay and projection-rebuild runbooks** (mechanism specced in 01/03, runbook isn't); backup/restore for Postgres + TigerBeetle + Pulsar; environments, release process, feature flags, CI migration-safety. ✅ **Specced in [19_NFR_SECURITY_OPS](19_NFR_SECURITY_OPS.md) Part C.** | **P1** |

---

## E. Open decisions to collect ("hand me the list" — none change the architecture)

| # | Decision | Needed by |
|---|---|---|
| 8b | Warranty release **curve** (straight-line default vs failure-rate) + claim-workflow depth | M8 |
| 11b | Markets/currencies/locales **supplied & seeded** (doc 02 §A, 23 markets). Residual: the **legal entity names + tax-registration numbers** per country (year-1 needs only UK) | M1 |
| 12b | Final **sub-channel + segment** seed list | M3 |
| 13b | Which **named accounts** get their own H6Q line (scenario cuts); Hyperview-vs-manual precedence default | M11 |
| 14b | **Field → data-layer** membership map + exact layer defaults per seed role | M2 |
| — | **Per-integration contracts** (inbound): HubSpot/MRPeasy/Xero/placement field maps + Rhenus webhook schema. ✅ **Specced in [37_INTEGRATION_CONTRACTS](37_INTEGRATION_CONTRACTS.md)** (shadow-mode inbound). *Outbound* mappings (HubSpot replication, Hyperview payload) remain deferred (post-takeover). | M13/M14 |
| — | **Money/VAT rounding** — policy now defined (doc 14 §1.2: explicit `RoundingPolicy` per boundary, configurable per `tax_regime`); residual is the per-jurisdiction line-vs-total values to load | M3/M13 |
| — | **Glossary** (one page — channel vs market vs entity, sell-in/sell-through, ADLP, coverage, tranche) | anytime |

---

## Suggested sequencing of the backlog

1. **Before go-live, in parallel with the spine build:** Migration runbook (B), NFR + Security + Ops/DR (D), Tax/customs engine (B), Document generation (B). These are the launch-blockers.
2. **As Phase 2/3 modules land:** 09_RETURNS (P1, M9b), back-office desk spec (B), then the CRM / H6Q / Intercompany deep-dives (A).
3. **Decisions (E)** collected in one finance/ops pass — each is owned by you/the team, not by spec work.
4. **Flow gaps (C)** folded into their owning module as those modules are built.

> The architecture does not change for any item above — these are depth, surface, ops, and data, layered onto the spine in 00–08.
