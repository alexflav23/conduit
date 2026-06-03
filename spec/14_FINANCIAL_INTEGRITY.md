# 14 — Financial Integrity: Money, Time & Controls (US GAAP / SOX / PCAOB-ready)

This is the doc that makes the numbers defensible. It specifies **pixel-perfect money math**, an explicit **time/period model** (because time-bound rollups move with timezone), the **US GAAP** treatments, the **SOX (ICFR) and PCAOB** controls baked in from day one, and the **Auditability Center** — the in-product surface that demonstrates, to an auditor, that all of the above is true and re-performable.

Design stance: **the ledger is the truth, the truth is exact integers, and every figure in every report traces back to it.** Everything here serves that.

---

## 1. Typed money & quantities (no floats, ever)

### 1.1 Types
- **No `Double`/`Float` touches money or quantity. Anywhere. Ever.** Lint/CI rule rejects floating-point in financial modules.
- **`Money`** — a currency-tagged decimal: `Money(amount: BigDecimal, currency: Currency)`.
  - `Currency` = ISO 4217 (`code`, `minorUnits`, `defaultRounding`). Stored as `NUMERIC(18,4)` + `CHAR(3)` (conventions, doc 00); 4 dp holds sub-cent unit prices; presentation rounds to `minorUnits` (2 for USD/EUR/GBP, **0 for JPY**).
  - **Cross-currency arithmetic is a type error.** `usd + eur` does not compile/throws; the only way across currencies is an explicit `convert(rate)` that records the rate + source + rounding.
  - Scalar ops (`× qty`, `× rate`, `× pct`) take an explicit `RoundingPolicy`; the rounding **mode and the boundary** are recorded, not implied.
- **`Squants`** for physical quantities — `Energy` (kWh), `Power` (kW), `ElectricCurrent` (A), `Length` (cable m). Directly relevant to EV-charging product data and any energy-derived billing; gives compile-time unit safety (can't add kWh to kW). Squants is for **dimensional quantities**; `Money` is a purpose-built financial type (Squants' own money model is too loose on rounding/allocation/FX for our needs) — we use each where it's strongest.

### 1.2 Rounding policy (explicit, per boundary, per jurisdiction)
`RoundingPolicy` is a first-class, configurable object — not a scattered `.setScale`:
- Default **HALF_UP** for commercial amounts; jurisdictions that mandate otherwise (and **line-vs-invoice-total** VAT rounding) are configured per `tax_regime` (resolves doc 10 decision "money/VAT rounding per jurisdiction").
- Rounding happens **only at defined boundaries** (line total, invoice total, FX conversion, period posting) and each rounding event is **traceable** (the pre/post values are reconstructable).

### 1.3 Conservation (the penny problem)
When an amount is split across lines, tranches, periods, or commission splits, **Σ parts == whole, exactly**. Allocation uses **largest-remainder**: distribute floor shares, then hand the leftover minor units to the largest fractional remainders. No penny is ever created or lost. This is enforced as a property (§5.4).

```scala
def allocate(total: Money, weights: Seq[BigDecimal]): Seq[Money] = {
  val raw    = weights.map(w => total * (w / weights.sum))          // exact ratio
  val floored= raw.map(_.roundDown(total.currency.minorUnits))      // floor to minor units
  val deficit= total - floored.sum                                   // leftover minor units (>= 0)
  // hand the leftover units, one at a time, to the largest fractional remainders
  distributeRemainder(floored, raw, deficit)
}    // invariant: result.sum == total, always
```

### 1.4 FX is explicit and provenanced
- `exchange_rate(base, quote, rate NUMERIC(18,8), as_of DATE, rate_type, source, captured_at)` — a versioned spot register. **Luxshare always bills USD** (doc 04 §FX); landed cost uses the spot rate or a designated `fx_hedge` contracted rate.
- Every conversion records `(rate, rate_type spot|hedge, source, as_of)` on the resulting amount. Consolidation to **USD presentation** under **ASC 830** (functional-currency translation; CTA to equity — §3.1). No conversion is ever implicit or unprovenanced.

### 1.5 TigerBeetle = exact by construction
TB stores **u128 integer minor units, per-currency ledgers** (doc 01). There is no float in the ledger; debits == credits or the transfer is rejected. App `Money` maps 1:1 to TB integer minor units for that currency. The sub-ledger is therefore exact at the atom; reporting rounds only at presentation, and the rounding is recorded.

---

## 2. Time & period model (why reports move, and how hard it is to change)

### 2.1 The instant is immutable; the period is a projection
- **Every timestamp is stored as a UTC instant** (`TIMESTAMPTZ`). Events carry UTC `occurred_at`. This never changes and is the audit anchor.
- **Period assignment is a separate, explicit decision**, parameterised by a **reporting timezone + fiscal calendar**. A transaction's day/week/month/quarter is computed as `occurred_at AT TIME ZONE :reporting_tz`, then mapped through the fiscal calendar. It is **not** baked irreversibly into the row.

```sql
-- the canonical monthly bucket, group calendar:
date_trunc('month', occurred_at AT TIME ZONE :group_reporting_tz)
-- the SAME events, sliced for the UK statutory entity:
date_trunc('month', occurred_at AT TIME ZONE 'Europe/London')
```

### 2.2 How easy is it to reslice by timezone? — two honest answers
- **Mechanically: easy.** Because the system is event-driven with an immutable, replayable log (and TB transfers carry business `occurred_at` in linked metadata), reslicing under a different TZ/calendar is a **re-projection**, not a migration. We keep a denormalised `period_key` on reporting rows for speed, but it is *derived* — drop and recompute it from the UTC instants and you have the new slice.
- **As an accounting decision: governed.** Changing the **canonical group reporting TZ/calendar** moves cutoff transactions between periods and breaks comparability with prior closes. So it is a **controlled change** (CFO-approved, documented, comparatives restated where material), even though the compute is cheap. We surface a **"preview reslice"** so the impact (which transactions move periods) is visible *before* anyone commits — see §6.

### 2.3 TigerBeetle and timezones
You **do not** bucket inside TB. TB is exact integer truth with its own nanosecond timestamps used for ordering, not accounting. The accounting period comes from **our** business `occurred_at` (UTC), carried in the transfer's linked record / `user_data`. The reporting projection (Postgres reporting schema / OLAP) does all TZ/period bucketing off that. Rule: **never derive a fiscal period from a TB internal timestamp.**

### 2.4 Reporting cadence (consolidating in USD)
| Cadence | Scope | Mechanism | Locked? |
|---|---|---|---|
| **Live / intraday** | Operational analytics — coverage, pipeline, sell-through, commission preview, OTD | event-driven projections | no |
| **Daily flash** | Management snapshot (revenue/units/margin to date) | nightly projection over UTC instants, group TZ | no |
| **Monthly close** | **GAAP period** — revenue (ASC 606), COGS, warranty release, accruals, FX translation | close checklist → reconciliations → **period lock** | **yes** |
| **Quarterly** | Hardened close + commission true-up (doc 04 §Commission) | as monthly + extra controls/review (PCAOB/SEC cadence — Nasdaq-bound) | **yes** |
| **Annual** | Audited financials | external audit over locked periods + replay | **yes** |
| **Statutory (per entity)** | Local-entity close in **local TZ**, then consolidated to USD | per-entity projection + elimination | **yes** |

`accounting_period(entity_id, scope day|month|quarter|year, period_key, reporting_tz, status open|closed|locked, closed_by, closed_at)`. **No posting to a `locked` period**; a late item posts to the current open period, or—if material to the closed period—via a controlled **prior-period adjustment** (maker-checker + CFO approval, fully audited).

---

## 3. US GAAP treatments (where they live)
| Area | Standard | Treatment | Spec ref |
|---|---|---|---|
| Revenue | **ASC 606** | recognised on transfer of control = **delivery**, per tranche; invoice auto-issued on delivery, never before | doc 04 §Ledger, §Orders |
| Inventory cost | ASC 330 | **specific-identification** batch landed cost; **no weighted-average** | doc 04 §Inventory, doc 02 §G |
| Warranty | **ASC 460** / loss contingencies | per-unit provision at activation, straight-line release, consolidated exposure, retro backfill | doc 04 §Warranty |
| FX / translation | **ASC 830** | functional currency per entity; translate to **USD presentation**; CTA to equity; hedge designation | §1.4, doc 04 §FX |
| Intercompany | consolidation | transfer pricing off batch cost; **elimination** on consolidation | doc 04 §Intercompany, doc 10 (13_) |
| Returns | ASC 606 (variable consideration) | reversal at batch cost + commission claw on RMA | doc 09 (planned) |

These are not new decisions — this table is the GAAP index so an auditor can see, per assertion, where the treatment is implemented.

---

## 4. SOX / ICFR — controls designed in (not bolted on)
**SOX §404 (ICFR)** requires controls over financial reporting that *exist* and *operate effectively*. Built in from the start:

1. **Segregation of duties / maker-checker** — already enforced for order amendment, stock count/transfer/write-off, ADLP exceptions, pricing-rule activation; **extended here** to: journal/manual adjustments, period close & lock, FX-rate entry, credit-limit changes, prior-period adjustments. Requester ≠ approver, system-enforced.
2. **Immutable audit trail** — append-only `audit_log` + TB immutability + the retained event log. Admin cannot edit audit data (doc 05).
3. **Access control / least privilege** — deny-by-default, data-layer projection, scoped roles (doc 05); a new read-only **`auditor`** role (§5.1).
4. **Change management** — schema migrations, pricing changes, `property_definition` changes, and tax-rate/calendar config are versioned, approved, and logged (doc 02 §M, doc 04 §Pricing).
5. **Reconciliation controls** — automated, scheduled, exception-flagged (§5.2).
6. **Period-close controls** — close checklist, cutoff, and **lock** preventing back-posting (§2.4).
7. **Completeness** — gapless event sequence numbers per stream + outbox guarantees (doc 01/03); a missing sequence is a detective alarm.

### `control` (the control register)
`code`, `name`, `objective`, `assertion TEXT[]` (existence/completeness/valuation/cutoff/rights&obligations/presentation), `type` (`preventive`/`detective`), `frequency` (`continuous`/`daily`/`monthly`/`quarterly`), `automated BOOLEAN`, `owner_user_id`, `evidence_query TEXT` (how the system re-performs/evidences it), `status`. Each control names the **assertion** it supports and **how it is evidenced**, so testing operating-effectiveness is a query, not an email thread.

---

## 5. PCAOB-grade auditability (re-performable, complete, retained)
PCAOB **AS 2201** (ICFR audit) and **AS 1215** (audit documentation) mean the auditor must test controls and re-perform balances, with complete, retained evidence. The system is built to be *tested*, not just *trusted*.

### 5.1 Lineage / drill-down (re-performance)
Any reported figure → its constituent **TB transfers** → the **business events** that caused them → the **source documents** (order, dispatch, GRN, invoice, RMA). Because the chain is `event → outbox → ledger posting → projection`, every aggregate is decomposable to atoms and **re-performable by replay**. The Auditability Center exposes this as a click-through (§6).

### 5.2 Reconciliation engine
`reconciliation(type, period_id, scope, expected, actual, variance, status open|matched|exception, signed_off_by)` — automated ties:
- **TB sub-ledger ↔ GL** (must tie to the penny — §1.5),
- **GL ↔ Xero** (the fed accounting consumer),
- **inventory sub-ledger ↔ physical counts** (stock ops, doc 04),
- **AR ↔ open invoices by payer** (doc 02 §C bill-to).
Exceptions are worked and signed off before a period locks.

### 5.3 Evidence & retention
Immutable, time-stamped, indefinitely retained (event log + TB + `audit_log`); WORM-style export for auditors. Retention/erasure tension (GDPR DSAR vs audit retention) is handled by the PII-erasure strategy (doc 01 §3a — crypto-erase PII, retain the financial skeleton) — flagged for the security doc (doc 10 §D).

### 5.4 Tested integrity (the math is proven, not asserted)
Property-based tests (ScalaCheck) ship with the financial core and run in CI:
- **conservation:** `allocate(total, w).sum == total` for all inputs;
- **no float:** money paths reject/round explicitly; no binary-float representation error reaches a stored value;
- **FX round-trip & provenance:** every converted amount carries a resolvable rate;
- **ledger balance:** every posting set has `Σ debits == Σ credits` per currency;
- **period immutability:** a posting to a locked period is rejected.
These tests are themselves audit evidence of valuation/accuracy controls.

---

## 6. The Auditability Center (in-product surface)
A back-office surface (and a read-only **auditor portal**) that makes the above *visible and demonstrable* — the part of the portal that shows, plainly, how the numbers are made trustworthy. Screens:

1. **Controls register** — every `control`, its assertion, owner, frequency, last-run result (pass/exception), and a "re-perform now" action that runs `evidence_query` live.
2. **Reconciliation dashboard** — TB↔GL↔Xero, inventory, AR ties per period; green/exception; drill to the un-matched items; sign-off trail.
3. **Period-close board** — open/closed/locked periods per entity, the close checklist with sign-offs, and the lock state (with who/when).
4. **Lineage explorer** — pick any reported number → transfers → events → documents; "replay to re-derive" to prove re-performance.
5. **Money integrity panel** — active `RoundingPolicy` per jurisdiction/boundary, live conservation checks, FX-rate provenance browser.
6. **Time / period panel** — the canonical reporting TZ + fiscal calendar, and **"preview reslice"**: choose another TZ and see exactly which transactions change period *before* committing (the §2.2 governance tool).
7. **Audit-log & evidence export** — searchable append-only trail; signed, time-stamped evidence packs for the external auditor.

Roles: a read-only **`auditor`** sees financial-truth + lineage + controls layers, edits nothing; finance/CFO own close, lock, FX entry and prior-period adjustments under maker-checker.

---

## 7. Where this lands in the build
This is **Phase-1 foundational** — the `Money`/`Currency`/`Squants` types, `RoundingPolicy`, `exchange_rate`, UTC-instant + period-projection discipline, TB integer mapping, the `control`/`reconciliation`/`accounting_period` tables, and the property-based test harness are part of the **ledger/foundation milestone (M2/M3)**, not a later add-on — controls retrofitted after go-live are exactly what PCAOB findings are made of. The Auditability Center screens follow once the close + reconciliation engine exist (a back-office milestone). See doc 07 for the milestone edits and doc 10 for the security/erasure and tax-rounding items that finish the picture.
