# 16 — Tax & Customs Engine

Build-grade deep-dive for the **tax & customs determination** subsystem. Same template as 02–05: field-level PostgreSQL schemas, outbox events, pseudocode, state machines, REST contracts, permission/data-layer mappings, an Acceptance block. This document **references and extends** the spine; it does **not** redefine tables already in doc 02. Tables it builds on: `tax_regime`, `tax_registration`, `market`, `entity` (doc 02 §A); `order`/`order_line`/`order_invoice`/`delivery_tranche` (doc 02 §F); `billing_profile`/`party`/`individual_details`/`address` (doc 02 §C); `product_variant.hs_code` (doc 02 §D); `landed_cost_component` (doc 02 §H); `accounting_period`, `exchange_rate`/`fx_rate` (doc 02 §A). It builds on the algorithms in doc 04 (§Pricing, §Orders, §Ledger, §FX), the events in doc 03 (`order.placed`, `dispatch.delivered`, `order.invoiced`, `intercompany.movement.posted`), the access wall in doc 05 (`tax_specialist` role, `commercial`/`profitability`/`inter_entity` layers), the money/rounding discipline in doc 14 (`RoundingPolicy` per `tax_regime`, line-vs-invoice rounding — §1.2), and the intercompany boundary in doc 13 (which **calls** this engine for buy-side import VAT/duty).

Scope: **VAT determination** (place of supply, B2B reverse charge, EU intra-community vs ROW, import VAT, CH/NO non-EU); **US destination sales tax** (per state/county, economic-nexus thresholds) and **Canada GST/HST/PST** (federal + provincial) — these are **multi-level / destination-based and NOT single rates**; **HS / commodity codes**; **Intrastat / EC sales lists**. The critical design point: a **pluggable `TaxProvider` abstraction** behind one clean request/response contract (the **`TaxQuote`**) — the default UK/EU path is **rate-table-driven** off `tax_regime`, while US/CA (and optionally any market) **route to an external tax-calc integration** (Avalara / TaxJar / Stripe Tax) behind the same interface. Year-1 seed = **UK VAT 20 only**; everything else is configuration + the provider for US/CA.

> **What this doc owns vs the rest of the pack.** This subsystem owns **tax determination**: given a set of supply facts (line items + ship-from / ship-to + party tax status), it returns **per-line tax amounts + jurisdiction breakdown + reverse-charge flags**, fully reproducible. It does **not** own pricing (doc 04 §Pricing resolves the ex-tax price *first*, then this engine taxes it), invoicing (doc 02 §F `order_invoice` / the document-generation workstream renders the legal artefact from this engine's result), or ledger posting (doc 04 §Ledger books the `VAT:<entity>` control off `dispatch.delivered`). It is **consumed by** pricing/quote (`/pricing/quote`, doc 06), order placement, invoicing, and the intercompany engine (doc 13 §6, which calls the same `TaxQuote` for import VAT/duty). The boundary is the `TaxQuote` contract in §3.

---

## 1. The model: a quote-driven determination engine

### 1.1 Why a `TaxQuote`, not a rate column

A single `rate_percent` on `tax_regime` is correct for **UK VAT 20** and the other single-rate VAT/GST markets — but it is **structurally wrong** for the markets in doc 02 §A flagged "needs tax engine":

- **US sales tax** is **destination-based and multi-level**: the rate at a ship-to address is `state + county + city + special district`, and liability *only exists* where the seller has **economic nexus** (a per-state sales/transaction threshold). There is no "US rate".
- **Canada** is **federal + provincial**: `GST` (federal 5%) plus either `HST` (harmonised, single combined rate per province) or `GST + PST/QST` (two separate taxes), varying by destination province.
- **EU cross-border** turns on **place-of-supply** rules and **B2B reverse charge** (the *buyer* accounts for the tax, seller charges 0% with a note), distinct from **EU import VAT** (ROW → EU) and **export** (EU → ROW, zero-rated).
- **CH / NO** are **non-EU** VAT jurisdictions reached by **import**, not intra-community supply.

So tax is computed as a **`TaxQuote`**: supply facts in → per-line tax amounts + a **jurisdiction breakdown** (one or more `components`, e.g. `state` + `county`, or `GST` + `PST`) + **reverse-charge** / zero-rate flags out. Every quote is **persisted, versioned and reproducible** (the audit anchor, §7).

### 1.2 The two provider paths behind one interface

```
                         resolveProvider(market, supply facts)
                                       │
          ┌────────────────────────────┴────────────────────────────┐
          ▼                                                          ▼
  RateTableTaxProvider                                       ExternalTaxProvider
  (default: UK/EU/single-rate VAT & GST)                     (US / CA, + optionally any market)
  • drives off tax_regime + place-of-supply rules            • Avalara / TaxJar / Stripe Tax adapter
  • reverse-charge / intra-EU / import / export              • destination rate stack (state/county/city)
  • single jurisdiction component                            • economic-nexus gating
  • zero external dependency, deterministic                  • multi-component jurisdiction breakdown
          └────────────────────────────┬────────────────────────────┘
                                        ▼
                            TaxQuote (per-line amounts +
                            jurisdiction breakdown + flags)
```

Both implement the **same `TaxProvider` interface** (§3.3); the caller never knows which path ran. Routing is **data** (`tax_routing`, §2.4) — flipping a market from rate-table to external (or onboarding a new provider) is configuration, not code. **Year-1: every market routes to `RateTableTaxProvider`; only UK is seeded** (VAT 20).

### 1.3 Where it sits in the order flow

```
quote / order line priced (doc 04 §Pricing) ──► ex-tax unit price + tax_regime hint
                                                     │
                          determineTax(TaxQuoteRequest)  ◄── ship-from/ship-to + party tax status
                                                     │
                       TaxQuote { per-line tax, breakdown, reverse_charge }
                          │                  │                         │
              /pricing/quote (preview)   order.placed (provisional)  dispatch.delivered → order.invoiced
                                                                        │ (the FINAL, recognition-point quote)
                                                                        ▼
                                                          VAT:<entity> ledger control (doc 04 §Ledger)
                                                          order_invoice totals (doc 02 §F)
```

Tax is quoted at **preview** (non-binding), re-quoted at **placement** (provisional), and **re-quoted at delivery** — the **single recognition point** (ASC 606; doc 04 §Orders/§Ledger). The delivery-time quote is the **authoritative** one the invoice and the `VAT` control book against, because place-of-supply facts (final ship-to, nexus state at the time, rates in effect) are only certain at delivery. A tranche invoices per drop (doc 02 §F), so **each tranche gets its own authoritative quote** on its delivery.

---

## 2. Data model (PostgreSQL)

Conventions per doc 00: every table has `id UUID PK DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`, optional `deleted_at`. Money = `NUMERIC(18,4)` + `CHAR(3)`. `→ X` = FK to `X.id`. These extend doc 02 §A — they do **not** redefine `tax_regime`/`tax_registration`/`market`.

### 2.1 `tax_regime` — extends doc 02 §A (additive columns only)

Doc 02 §A: `tax_regime(code PK-unique, rate_percent NUMERIC(7,4), jurisdiction CHAR(2), kind TEXT)`. The `RateTableTaxProvider` and place-of-supply logic need a few additive columns; **existing columns are unchanged**, and the single `rate_percent` still drives single-rate VAT/GST.

| column | type | notes |
|---|---|---|
| code | TEXT UNIQUE NOT NULL | existing (`GB_STANDARD`,`IE_STANDARD`,`AU_STANDARD`,`TAX_FREE`,`REVERSE_CHARGE`,`IMPORT`) |
| rate_percent | NUMERIC(7,4) NOT NULL | existing — the single rate (used by rate-table path) |
| jurisdiction | CHAR(2) | existing — ISO country |
| kind | TEXT NOT NULL | existing — extended enum: `standard`/`reduced`/`zero`/`reverse_charge`/`import`/`export`/`exempt`/`out_of_scope`/`destination` |
| tax_type | TEXT NOT NULL DEFAULT 'VAT' | `VAT`/`GST`/`HST`/`PST`/`QST`/`sales_tax`/`consumption` — aligns with `tax_registration.tax_type` |
| region | TEXT NULL | sub-national region (US state, CA province) for multi-level regimes |
| economic_zone | TEXT NULL | `EU`/`EEA`/`UK`/`ROW`/`NA` — drives intra-community vs import vs export |
| reverse_chargeable | BOOLEAN NOT NULL DEFAULT false | this regime supports B2B reverse charge |
| rounding_policy | TEXT NOT NULL DEFAULT 'line' | `line`/`invoice` — **where VAT rounds** (doc 14 §1.2), per `tax_regime` |
| rounding_mode | TEXT NOT NULL DEFAULT 'HALF_UP' | jurisdictions that mandate otherwise override (doc 14 §1.2) |
| provider | TEXT NOT NULL DEFAULT 'rate_table' | `rate_table`/`external` — which `TaxProvider` services this regime (default routing; overridable by `tax_routing` §2.4) |
| effective_from | DATE NOT NULL DEFAULT '1970-01-01' | rates change — versioned by date |
| effective_to | DATE NULL | |
| status | TEXT NOT NULL DEFAULT 'active' | `active`/`superseded` |

UNIQUE(code, effective_from). Index(jurisdiction, tax_type, effective_from DESC), (kind), (provider). A rate change is a **new dated row** (never an in-place edit), audited and emitted as `tax.regime.changed` (§5) — so the rate in force at any historic `occurred_at` is reproducible (doc 14 §2). **Year-1 seed: `GB_STANDARD` (VAT 20, `kind='standard'`, `tax_type='VAT'`, `economic_zone='UK'`, `rounding_policy='line'`, `provider='rate_table'`), `REVERSE_CHARGE`, `TAX_FREE`, `IMPORT`, `EXPORT`** — everything else is loaded as a market opens.

### 2.2 `tax_registration` — extends doc 02 §A (additive)

Doc 02 §A: `tax_registration(entity_id, tax_type, number, jurisdiction, effective_from, effective_to)`. Additive columns for nexus and registration scope:

| column | type | notes |
|---|---|---|
| entity_id | UUID → entity NOT NULL | existing |
| tax_type | TEXT NOT NULL | existing — `VAT`/`GST`/`sales_tax`/… |
| number | TEXT | existing — VAT/GST registration number |
| jurisdiction | CHAR(2) NOT NULL | existing — country |
| region | TEXT NULL | US state / CA province where registered (sub-national) |
| registration_kind | TEXT NOT NULL DEFAULT 'domestic' | `domestic`/`oss`/`ioss`/`import`/`nexus` (EU One-Stop-Shop, Import-OSS, US economic nexus) |
| collects_tax | BOOLEAN NOT NULL DEFAULT true | false = registered but not collecting (e.g. monitoring nexus) |
| effective_from | DATE | existing |
| effective_to | DATE NULL | existing |

UNIQUE(entity_id, tax_type, jurisdiction, region, effective_from). Index(entity_id, jurisdiction, region). **A registration is the proof the entity has nexus / is obliged to charge** in `(jurisdiction, region)`: the engine charges destination tax **only where a `collects_tax` registration exists** (or the external provider asserts nexus — §2.5). No registration ⇒ no charge (and a nexus alert if a threshold is crossed, §2.5).

### 2.3 `tax_category` — product tax classification

Tax rate can vary by **what** is sold (a charger vs a cable vs a warranty product vs a service). Each `product_variant` maps to a tax category; the rate-table path picks the regime variant, the external path passes a **tax code** (Avalara `taxCode` / Stripe `tax_code`).

| column | type | notes |
|---|---|---|
| code | TEXT UNIQUE NOT NULL | `goods_standard`/`goods_reduced`/`service`/`warranty`/`digital`/`zero_rated` |
| name | TEXT NOT NULL | |
| default_kind | TEXT NOT NULL | maps to `tax_regime.kind` for the rate-table path |
| provider_tax_code | JSONB NULL | per-provider external code, e.g. `{"avalara":"P0000000","stripe":"txcd_99999999"}` |
| hs_chapter_hint | TEXT NULL | informational link to customs (HS) classification |

`product_variant` gains an additive `tax_category_code TEXT → tax_category.code NULL` (default `goods_standard`). Charger SKUs = `goods_standard`; warranty products = `warranty`; services = `service`. Adding a category is a row (no migration).

### 2.4 `tax_routing` — which provider serves a market (config, not code)

The routing rule. Resolves a `(market, tax_type, party tax status)` to a provider; **the only place provider selection lives**. Flipping a market from rate-table to external — or onboarding a second external provider for one region — is a row change.

| column | type | notes |
|---|---|---|
| market_id | UUID → market NULL | null = applies to all markets |
| jurisdiction | CHAR(2) NULL | match country (more specific than market) |
| tax_type | TEXT NULL | `VAT`/`sales_tax`/`GST`/… (null = any) |
| provider | TEXT NOT NULL | `rate_table`/`avalara`/`taxjar`/`stripe_tax` |
| provider_config_ref | TEXT NULL | secret/connection ref (Consul/secrets, doc 01) — never the secret itself |
| priority | INTEGER NOT NULL DEFAULT 100 | lower wins on tie |
| status | TEXT NOT NULL DEFAULT 'active' | `active`/`disabled` |
| effective_from | DATE NOT NULL DEFAULT '1970-01-01' | |
| effective_to | DATE NULL | |

UNIQUE(market_id, jurisdiction, tax_type, effective_from). Index(jurisdiction, tax_type, status). **Seed (year-1):** one row `{market:null, provider:'rate_table', priority:100}` — everything is rate-table. Opening the **US** adds `{jurisdiction:'US', tax_type:'sales_tax', provider:'avalara', priority:10}`; **Canada** adds `{jurisdiction:'CA', tax_type:'GST', provider:'avalara'}`. The resolver (§4.1) picks the most specific active row.

### 2.5 `nexus_profile` — US economic-nexus thresholds & state

Economic nexus is **per state** (and per CA province for PST): once cumulative sales **or** transaction count in a state crosses the threshold over the lookback window, the entity must register and collect. Conduit **tracks** the running total so it can (a) gate charging and (b) **alert before** the threshold is crossed.

| column | type | notes |
|---|---|---|
| entity_id | UUID → entity NOT NULL | the selling entity |
| jurisdiction | CHAR(2) NOT NULL | `US`/`CA` |
| region | TEXT NOT NULL | state / province code |
| threshold_amount | NUMERIC(18,4) NULL | e.g. USD 100,000 |
| threshold_txn_count | INTEGER NULL | e.g. 200 transactions |
| threshold_currency | CHAR(3) NOT NULL DEFAULT 'USD' | |
| lookback | TEXT NOT NULL DEFAULT 'rolling_12m' | `rolling_12m`/`prior_or_current_year` (state-specific) |
| sales_to_date | NUMERIC(18,4) NOT NULL DEFAULT 0 | rolling, maintained by the projection (§4.5) |
| txn_count_to_date | INTEGER NOT NULL DEFAULT 0 | rolling |
| status | TEXT NOT NULL | `monitoring`/`approaching`/`crossed`/`registered` |
| crossed_at | TIMESTAMPTZ NULL | when threshold tripped |
| registration_id | UUID → tax_registration NULL | the registration created once crossed |

UNIQUE(entity_id, jurisdiction, region). Index(status), (entity_id, jurisdiction). A nightly + on-sale projection (§4.5) advances `sales_to_date`/`txn_count_to_date`; at ≥80% it flips `approaching` and emits `tax.nexus.threshold_approaching`; at ≥100% it flips `crossed` and emits `tax.nexus.threshold_crossed` (a finance/tax action: register, then collection begins). **When the provider is `external`, the provider asserts nexus** and `nexus_profile` is the **reconciling shadow** (the engine still records it for the alert + audit); when `rate_table`, `nexus_profile` + `tax_registration` are authoritative.

### 2.6 `tax_quote` — the persisted determination (the audit anchor)

Every determination is persisted **immutable** (append-only; a re-quote inserts a new row and supersedes the prior). This is the document/audit trail: **every tax decision is reproducible** from the request snapshot + the provider + the rates/version in force.

| column | type | notes |
|---|---|---|
| context | TEXT NOT NULL | `quote_preview`/`order_placed`/`invoice`/`intercompany_import` |
| order_id | UUID → order NULL | the order taxed (null for IC-import / standalone) |
| tranche_id | UUID → delivery_tranche NULL | which drop (per-tranche invoice quote) |
| order_invoice_id | UUID → order_invoice NULL | set when this is the authoritative invoice quote |
| intercompany_link_id | UUID → intercompany_link NULL | set for `intercompany_import` (doc 13 §6) |
| entity_id | UUID → entity NOT NULL | selling entity (the registered taxpayer) |
| ship_from_jurisdiction | CHAR(2) NOT NULL | place of supply origin |
| ship_from_region | TEXT NULL | state/province |
| ship_to_jurisdiction | CHAR(2) NOT NULL | destination |
| ship_to_region | TEXT NULL | destination state/province/county key |
| ship_to_postcode | TEXT NULL | drives US county/city stack |
| party_tax_status | TEXT NOT NULL | `consumer`/`business`/`business_with_vat_id`/`exempt` |
| buyer_tax_id | TEXT NULL | buyer VAT/GST number (validates B2B reverse charge) |
| supply_kind | TEXT NOT NULL | resolved: `domestic`/`intra_eu_b2b_reverse`/`intra_eu_b2c`/`import`/`export`/`us_destination`/`ca_federal_provincial`/`out_of_scope` |
| provider | TEXT NOT NULL | `rate_table`/`avalara`/`taxjar`/`stripe_tax` (which path ran) |
| provider_ref | TEXT NULL | external provider transaction/audit ref (the determination_ref) |
| provider_version | TEXT NULL | adapter / provider API version |
| currency | CHAR(3) NOT NULL | |
| total_tax | NUMERIC(18,4) NOT NULL | Σ line tax (after the regime's line-vs-invoice rounding) |
| reverse_charge | BOOLEAN NOT NULL DEFAULT false | buyer accounts for tax; seller charges 0 |
| rounding_policy | TEXT NOT NULL | `line`/`invoice` actually applied (doc 14 §1.2) |
| rates_asof | DATE NOT NULL | the date the rates were resolved as-of (reproducibility) |
| request_snapshot | JSONB NOT NULL | the full `TaxQuoteRequest` (replay input) |
| response_snapshot | JSONB NOT NULL | the full `TaxQuoteResponse` (replay output) |
| superseded_by | UUID → tax_quote NULL | append-only versioning (preview → placed → invoice) |
| determined_at | TIMESTAMPTZ NOT NULL | |

Index(order_id, context, determined_at DESC), (order_invoice_id), (intercompany_link_id), (entity_id, ship_to_jurisdiction, determined_at), (provider, provider_ref). **Reproducibility guarantee:** for the `rate_table` provider, re-running determination over `request_snapshot` with the `tax_regime` rows effective at `rates_asof` re-derives `response_snapshot` **exactly** (asserted by a control, §8). For `external` providers, the `request_snapshot` + `provider_ref` reproduce the call and the persisted response is the retained evidence (the provider is the authority of record; we keep what it returned). `tax_quote` rows are `field_layer_map`'d so tax **amounts** sit on `commercial` and any cost-bearing breakdown stays off `volume`-only principals (§9).

### 2.7 `tax_quote_line` — per-line amounts + jurisdiction breakdown

| column | type | notes |
|---|---|---|
| tax_quote_id | UUID → tax_quote NOT NULL | |
| order_line_id | UUID → order_line NULL | the line taxed |
| product_variant_id | UUID → product_variant NULL | |
| tax_category_code | TEXT → tax_category.code | classification used |
| hs_code | TEXT NULL | from `product_variant.hs_code` (customs / import) |
| taxable_amount | NUMERIC(18,4) NOT NULL | ex-tax base this line was taxed on |
| line_tax_total | NUMERIC(18,4) NOT NULL | Σ of components for the line |
| effective_rate_pct | NUMERIC(9,4) NOT NULL | blended rate (sum of components) for display |
| reverse_charge | BOOLEAN NOT NULL DEFAULT false | per-line (mixed baskets possible) |
| regime_code | TEXT → tax_regime.code NULL | regime applied (rate-table path) |
| components | JSONB NOT NULL | the **jurisdiction breakdown** — see below |

`components` is the multi-level breakdown — an array, one entry per taxing authority:
```json
[ { "level": "state",    "jurisdiction": "US", "region": "CA", "name": "California",     "rate_pct": "6.0000",  "amount": "6.0000",  "tax_type": "sales_tax" },
  { "level": "county",   "jurisdiction": "US", "region": "CA", "name": "Los Angeles",    "rate_pct": "0.2500",  "amount": "0.2500",  "tax_type": "sales_tax" },
  { "level": "district", "jurisdiction": "US", "region": "CA", "name": "LA Metro",       "rate_pct": "2.2500",  "amount": "2.2500",  "tax_type": "sales_tax" } ]
```
For UK/EU a single component (`{level:"national", jurisdiction:"GB", rate_pct:"20.0000", tax_type:"VAT"}`); for Canada two (`{level:"federal", tax_type:"GST", rate_pct:"5.0000"}` + `{level:"provincial", tax_type:"PST", region:"BC", rate_pct:"7.0000"}`) or one for HST provinces. Index(tax_quote_id), (order_line_id). The breakdown is what the invoice line and any tax-return filing render from.

### 2.8 Intrastat / EC sales — `intrastat_declaration`, `ec_sales_line`

EU statistical and recapitulative reporting, fed by the determination + dispatch events (movements of goods between EU member states, and intra-EU B2B supplies). Materialised projections, rebuilt from `tax_quote` + `dispatch.delivered` + `intercompany.movement.posted`.

`intrastat_declaration`: `entity_id → entity`, `jurisdiction CHAR(2)`, `period_key TEXT` (month), `flow TEXT` (`arrival`/`dispatch`), `status TEXT` (`open`/`submitted`), `submitted_at TIMESTAMPTZ NULL`.
`intrastat_line`: `declaration_id → intrastat_declaration`, `hs_code TEXT NOT NULL` (commodity code), `partner_country CHAR(2)`, `nature_of_transaction TEXT`, `net_mass_kg NUMERIC(12,3) NULL`, `invoice_value NUMERIC(18,4)`, `currency CHAR(3)`, `qty INTEGER`. Index(declaration_id, hs_code).
`ec_sales_line` (EC Sales List / recapitulative statement): `entity_id → entity`, `period_key TEXT`, `customer_vat_id TEXT NOT NULL`, `customer_country CHAR(2)`, `indicator TEXT` (`goods`/`triangulation`/`services`), `net_value NUMERIC(18,4)`, `currency CHAR(3)`, `status TEXT`. Index(entity_id, period_key). **Year-1 (UK only, post-Brexit GB):** Intrastat/EC sales are **dormant** (no intra-EU supplies from GB); they activate the moment an EU operating entity opens — config + the projection turning on, no schema change.

---

## 3. The `TaxProvider` abstraction & `TaxQuote` contract

The pluggable boundary. **One interface, two+ implementations**; callers depend only on the interface.

### 3.1 `TaxQuoteRequest` (caller → engine)

```
TaxQuoteRequest {
  context: 'quote_preview' | 'order_placed' | 'invoice' | 'intercompany_import',
  entity_id: UUID,                                   // selling / liable entity (the registered taxpayer)
  ship_from: { jurisdiction: CHAR(2), region?: TEXT },          // place of supply origin (warehouse/location)
  ship_to:   { jurisdiction: CHAR(2), region?: TEXT, postcode?: TEXT },  // destination (drives US county/city stack)
  party_tax_status: 'consumer' | 'business' | 'business_with_vat_id' | 'exempt',
  buyer_tax_id?: TEXT,                               // VAT/GST number → validates B2B reverse charge / intra-EU
  incoterm?: TEXT,                                   // import: who clears customs / customs-value basis (doc 13 §6)
  currency: CHAR(3),
  as_of: DATE,                                       // determination date (rates effective; doc 14 §2)
  lines: [ { ref: TEXT,                              // order_line_id | po line ref
             product_variant_id?: UUID,
             tax_category_code?: TEXT,               // default goods_standard
             hs_code?: TEXT,                         // customs / import (from product_variant.hs_code)
             qty: INTEGER,
             taxable_amount: Money } ],              // ex-tax base (price already resolved by doc 04 §Pricing)
  ref?: { order_id?, tranche_id?, intercompany_link_id? }
}
```

The caller resolves the **ex-tax price first** (doc 04 §Pricing) and hands this engine the `taxable_amount` — pricing and tax are cleanly separated. `ship_from` is the dispatching `location`'s jurisdiction; `ship_to` is the order's `ship_to_address`; `party_tax_status` derives from the buyer party (`individual_details.tax_status` / `billing_profile.tax_registration_number` presence — §4.2).

### 3.2 `TaxQuoteResponse` (engine → caller)

```
TaxQuoteResponse {
  supply_kind: 'domestic' | 'intra_eu_b2b_reverse' | 'intra_eu_b2c' | 'import' | 'export'
             | 'us_destination' | 'ca_federal_provincial' | 'out_of_scope',
  reverse_charge: BOOLEAN,                           // header-level (per-line override in lines[])
  currency: CHAR(3),
  lines: [ { ref: TEXT,
             taxable_amount: Money,
             line_tax_total: Money,
             effective_rate_pct: NUMERIC,
             reverse_charge: BOOLEAN,
             regime_code?: TEXT,                     // rate-table path
             components: [ { level, jurisdiction, region?, name, rate_pct, amount, tax_type } ] } ],  // jurisdiction breakdown
  totals: { taxable: Money, tax: Money },            // after the regime's line-vs-invoice rounding
  rounding_policy: 'line' | 'invoice',               // which boundary applied (doc 14 §1.2)
  duty?: { lines:[{ ref, hs_code, duty_rate_pct, duty_amount }], total: Money },  // import context only (doc 13 §6)
  import_vat?: { amount: Money, recoverable: BOOLEAN },                            // import context only
  determination_ref?: TEXT,                          // provider audit ref (Intrastat/EC-sales lineage)
  provider: TEXT, provider_version: TEXT,
  rates_asof: DATE
}
```

This is **the** contract doc 13 §6 calls for intercompany import VAT/duty (its `TaxQuoteRequest`/`TaxQuoteResponse` are the `context='intercompany_import'` shape of this one — the `duty`/`import_vat` blocks are populated only in that context). Customer-sale contexts leave `duty`/`import_vat` null.

### 3.3 The interface (Scala-shaped pseudocode)

```
trait TaxProvider {
  def name: String
  def quote(req: TaxQuoteRequest): F[TaxQuoteResponse]      // pure-ish; ExternalTaxProvider does I/O
  def supports(market: Market, req: TaxQuoteRequest): Boolean
}

object RateTableTaxProvider extends TaxProvider {           // default: UK/EU/single-rate VAT & GST
  // pure, deterministic, zero external dependency; reads tax_regime/tax_registration
}

class ExternalTaxProvider(adapter: TaxCalcAdapter) extends TaxProvider {  // Avalara / TaxJar / Stripe Tax
  // delegates to the vendor SDK; maps vendor response → TaxQuoteResponse; persists provider_ref
}

trait TaxCalcAdapter {                                       // one per vendor; swappable (doc 01 stack)
  def calculate(req: VendorTaxRequest): F[VendorTaxResponse] // Avalara CreateTransaction / Stripe tax.calculations / TaxJar /v2/taxes
  def commit(ref: String): F[Unit]                           // finalise on invoice (vendor "commit"/"finalize")
  def voidTax(ref: String): F[Unit]                          // on cancel/return
}
```

The **caller** uses only `TaxEngine.determine(req)` (§4.1), which resolves and dispatches to a provider. **Swapping Avalara→Stripe Tax for a region is a new `TaxCalcAdapter` + a `tax_routing` row** — no change to callers, the engine, or the `TaxQuote` schema. An `external` quote is wrapped in a **circuit breaker with a rate-table fallback** flagged for review (a US sale must never silently fail to tax; §4.4).

---

## 4. Determination algorithm (which path / rate applies given supply facts)

### 4.1 Entry point & provider resolution

```
determine(req: TaxQuoteRequest): TaxQuote =
  market   = marketFor(req.entity_id, req.ship_to.jurisdiction)
  facts    = classifySupply(req)                         // §4.2 — place of supply + supply_kind
  provider = resolveProvider(market, req, facts)         // §4.3 — tax_routing
  resp     = provider.quote(req)                         // RateTable (§4.4a) | External (§4.4b)
  persistTaxQuote(req, resp, provider, facts)            // §2.6 immutable, supersedes prior for (order, context)
  updateNexus(req, resp)                                 // §4.5 (US/CA destination)
  feedIntrastatEcSales(req, resp, facts)                 // §4.6 (EU)
  return quote

resolveProvider(market, req, facts):
  row = tax_routing
         WHERE status='active'
           AND (market_id = market.id   OR market_id   IS NULL)
           AND (jurisdiction = req.ship_to.jurisdiction OR jurisdiction IS NULL)
           AND (tax_type = facts.tax_type OR tax_type IS NULL)
           AND effective_from <= req.as_of AND (effective_to IS NULL OR effective_to > req.as_of)
         ORDER BY (market_id IS NOT NULL) DESC, (jurisdiction IS NOT NULL) DESC, priority ASC
         LIMIT 1
  return providerFor(row.provider)        // 'rate_table' → RateTableTaxProvider ; else ExternalTaxProvider(adapter)
```

### 4.2 Place-of-supply classification (the decision the rate-table path encodes)

`classifySupply` is the legal heart of the rate-table path — given the supply facts, which `supply_kind` and which `tax_regime` apply. (The external path mostly defers this to the vendor, but Conduit still classifies for routing, nexus tracking and Intrastat/EC-sales lineage.)

```
classifySupply(req):
  from = req.ship_from.jurisdiction ; to = req.ship_to.jurisdiction
  fromZone = economicZone(from) ; toZone = economicZone(to)     // EU/EEA/UK/ROW/NA from tax_regime.economic_zone
  isBusiness = req.party_tax_status in {business, business_with_vat_id}
  hasValidVatId = req.party_tax_status == 'business_with_vat_id' && validVatId(req.buyer_tax_id, to)

  // --- North America (destination) ---
  if to == 'US': return Facts(supply_kind='us_destination',      tax_type='sales_tax')   // §4.4b external
  if to == 'CA': return Facts(supply_kind='ca_federal_provincial', tax_type='GST')        // §4.4b external (GST+PST/HST)

  // --- domestic (same country) ---
  if from == to:
     return Facts(supply_kind='domestic', regime=standardRegime(to), tax_type=taxTypeOf(to))

  // --- EU intra-community ---
  if fromZone == 'EU' && toZone == 'EU':
     if isBusiness && hasValidVatId:
        return Facts(supply_kind='intra_eu_b2b_reverse', regime='REVERSE_CHARGE', reverse_charge=true)   // 0%, buyer accounts
     else:
        // B2C intra-EU: OSS — destination rate where registered for OSS, else origin (threshold rules)
        return Facts(supply_kind='intra_eu_b2c', regime=ossRegimeFor(req.entity_id, to))

  // --- export: EU/UK → ROW (incl. CH/NO from an EU/UK origin) ---
  if (fromZone in {EU,UK}) && toZone == 'ROW':
     return Facts(supply_kind='export', regime='EXPORT')          // zero-rated, with evidence of export

  // --- import: ROW → EU/UK, or into CH/NO (non-EU) ---
  if toZone in {EU,UK} && fromZone == 'ROW':
     return Facts(supply_kind='import', regime='IMPORT')          // import VAT at destination (doc 13 §6 for IC)
  if to in {'CH','NO'} && from != to:
     return Facts(supply_kind='import', regime=standardRegime(to))// CH/NO non-EU: import VAT into the country

  return Facts(supply_kind='out_of_scope', regime='TAX_FREE')
```

Notes: `validVatId` checks structural validity and (where available) VIES/HMRC online validation — a B2B reverse charge requires a **valid** buyer VAT ID or it falls back to charging destination VAT. CH (8.1%) and NO (25%) are **non-EU**: a supply *into* them from outside is an import; a domestic supply within them is `domestic` at the country's standard rate.

### 4.4a Rate-table computation (UK / EU / single-rate VAT & GST)

```
RateTableTaxProvider.quote(req):
  facts  = classifySupply(req)
  regime = taxRegimeRow(facts.regime, asOf=req.as_of)            // dated row → reproducible (§2.1)
  rp     = RoundingPolicy(boundary=regime.rounding_policy, mode=regime.rounding_mode)   // doc 14 §1.2
  lineResults = []
  for ln in req.lines:
     category = ln.tax_category_code ?? variant(ln).tax_category_code ?? 'goods_standard'
     rate     = effectiveRate(regime, category)                  // regime.rate_percent, adjusted for reduced/zero category
     if facts.reverse_charge || facts.supply_kind in {export, out_of_scope}:
        tax = Money(0, req.currency); rc = facts.reverse_charge
     else:
        rawTax = ln.taxable_amount × rate/100
        tax    = (rp.boundary == 'line') ? round(rawTax, req.currency.minorUnits, rp.mode)
                                          : rawTax        // invoice-boundary: defer rounding to total (below)
        rc = false
     lineResults += Line(ref=ln.ref, taxable=ln.taxable_amount, tax=tax,
                         effective_rate=rate, reverse_charge=rc, regime=regime.code,
                         components=[ Component(level=regime.regionLevel, jurisdiction=regime.jurisdiction,
                                                region=regime.region, rate_pct=rate, amount=tax,
                                                tax_type=regime.tax_type) ])
  // line-vs-invoice rounding (doc 14 §1.2): invoice-boundary rounds the SUM, then re-allocates by largest-remainder
  if rp.boundary == 'invoice':
     totalTax = round(Σ lineResults.rawTax, req.currency.minorUnits, rp.mode)
     reallocate(lineResults, totalTax)                           // largest-remainder so Σ lines == invoice total (doc 14 §1.3)
  return TaxQuoteResponse(supply_kind=facts.supply_kind, reverse_charge=facts.reverse_charge,
                          lines=lineResults, totals={taxable:Σtaxable, tax:Σtax},
                          rounding_policy=regime.rounding_policy, provider='rate_table', rates_asof=req.as_of)
```

**Line-vs-invoice rounding (doc 14 §1.2/§1.3) is per `tax_regime`:** some jurisdictions round VAT **per line** (round each line's tax, then sum), others round **at the invoice total** (sum exact line taxes, round once). The `tax_regime.rounding_policy` (+ `rounding_mode`) drives it; the invoice-boundary case rounds the total then **re-allocates with largest-remainder** so `Σ line tax == invoice tax` exactly (no penny created/lost — the conservation invariant, doc 14 §1.3). UK default = `line`. This resolves the doc 10 §B / doc 14 residual "per-jurisdiction line-vs-total values to load".

### 4.4b External computation (US destination / CA / any externally-routed market)

```
ExternalTaxProvider.quote(req):
  if !nexusGate(req):                                            // §4.5 — no obligation, no charge
     return zeroTax(req, supply_kind=classifySupply(req).supply_kind, reason='no_nexus')
  vReq  = mapToVendor(req)                                       // line tax codes (tax_category.provider_tax_code), ship-to address, customer tax id
  vResp = breaker.protect( adapter.calculate(vReq) )            // circuit breaker; fallback §4.4-fallback
  lines = vResp.lines.map(vl =>
            Line(ref=vl.ref, taxable=vl.taxable, tax=vl.tax, effective_rate=vl.rate, reverse_charge=false,
                 components=vl.jurisdictions.map(j => Component(level=j.level, jurisdiction=j.country,
                              region=j.region, name=j.name, rate_pct=j.rate, amount=j.amount, tax_type=j.taxType))))
  // vendor owns rounding/jurisdiction stack; we record exactly what it returned (retained evidence, §2.6)
  return TaxQuoteResponse(supply_kind=facts.supply_kind, reverse_charge=false, lines=lines,
                          totals=vResp.totals, rounding_policy='invoice',
                          determination_ref=vResp.transactionRef, provider=adapter.name,
                          provider_version=vResp.apiVersion, rates_asof=req.as_of)
```

The external provider returns the **multi-component jurisdiction breakdown** (state + county + city + district for US; federal GST + provincial PST/HST for CA) directly into `components`. Conduit does **not** second-guess the vendor's rates or rounding — it records them as the **retained evidence** and the vendor's `transactionRef` is the audit anchor. On **invoice** (the recognition point), `ExternalTaxProvider` calls `adapter.commit(ref)` so the vendor finalises the transaction for the vendor's own returns filing; on **cancel/return** it calls `adapter.voidTax(ref)`.

**External-provider failure handling (§4.4-fallback):** the breaker, on vendor timeout/5xx, (i) for a `quote_preview` returns a best-effort rate-table estimate flagged `provisional=true`; (ii) for `order_placed`/`invoice` **fails closed** — the order holds (`422 tax_determination_unavailable`) rather than book wrong tax — and raises `tax.quote.failed` for the tax desk. A US/CA sale never silently books zero or guessed tax at the recognition point.

### 4.5 Nexus tracking & gating (US / CA destination)

```
nexusGate(req):                                                  // is the entity obliged to collect in ship_to?
  reg = tax_registration WHERE entity_id=req.entity_id AND jurisdiction=req.ship_to.jurisdiction
          AND (region = req.ship_to.region OR registration_kind='nexus') AND collects_tax
          AND effective range covers req.as_of
  if reg exists: return true                                     // registered ⇒ collect
  // external providers may assert nexus even without our local registration row → trust provider, shadow-record
  return providerAssertsNexus(req)

updateNexus(req, resp):                                          // maintain rolling totals + alerts
  if req.ship_to.jurisdiction not in {US,CA}: return
  np = nexus_profile(entity=req.entity_id, jurisdiction=req.ship_to.jurisdiction, region=req.ship_to.region)
  np.sales_to_date    += resp.totals.taxable (rolling window per np.lookback)
  np.txn_count_to_date += 1
  pct = max(np.sales_to_date/np.threshold_amount, np.txn_count_to_date/np.threshold_txn_count)
  if pct >= 1.0 and np.status != 'crossed':  np.status='crossed';  np.crossed_at=now; emit tax.nexus.threshold_crossed
  elif pct >= 0.8 and np.status=='monitoring': np.status='approaching';                emit tax.nexus.threshold_approaching
```

`tax.nexus.threshold_crossed` is a finance/tax action item: register in the state/province (creating a `tax_registration(registration_kind='nexus', collects_tax=true)`), after which `nexusGate` returns true and collection begins. **Until registered, no tax is charged for that state** (correct — the obligation starts on registration), but the crossing is **alerted and audited** so it is never missed.

### 4.6 Intrastat / EC sales feed (EU)

The determination + `dispatch.delivered` consumer projects EU goods movements and intra-EU B2B supplies into `intrastat_line` (by `hs_code`, partner country, net mass, value) and `ec_sales_line` (by buyer VAT ID, country, net value) for the period. Triggered for `supply_kind in {intra_eu_b2b_reverse, intra_eu_b2c, import, export}` where an EU entity is the declarant. **Dormant in year-1 (UK-only)**; turns on with the first EU operating entity (config + projection enable, no schema change).

---

## 5. Events (extends doc 03)

Topic `conduit.tax` (new aggregate type `tax`) for determination/config; nexus/Intrastat ride it too. Envelope per doc 03 §1; `BACKWARD` compatible; idempotent on `event_id`; partition keys as noted. Custom attributes are **not** carried (tax is typed core — doc 02 §M guardrails).

### `tax.quoted`
key `order_id` (or `intercompany_link_id`) · partition by that key
```
{ tax_quote_id, context, order_id?, tranche_id?, order_invoice_id?, intercompany_link_id?,
  entity_id, ship_from, ship_to, party_tax_status, supply_kind, provider, provider_ref?,
  reverse_charge, currency, total_tax, rounding_policy, rates_asof,
  lines: [ { order_line_id?, taxable_amount, line_tax_total, effective_rate_pct, reverse_charge,
             regime_code?, components:[{level,jurisdiction,region?,rate_pct,amount,tax_type}] } ],
  determined_at }
```
→ **pricing/quote** read-model (preview), **order** (provisional totals on `order_placed`), **invoicing** (the authoritative invoice quote on `dispatch.delivered`), **ledger** `VAT:<entity>` control posting (doc 04 §Ledger), Intrastat/EC-sales projection, audit. Layer note (doc 05 §3): the external/Xero-facing projection carries tax **amounts** (`commercial`) and the breakdown; never any cost/margin field.

### `tax.regime.changed`
key `regime_code` · `{ code, before, after, rate_percent, kind, effective_from, owner_user_id, approved_by }` → rate-table read-model refresh, `tax_quote` reproducibility window, audit (maker-checker, doc 05 §4 — a rate/calendar change is a governed action, doc 14 §4 control-4).

### `tax.routing.changed`
key `market_id` · `{ market_id, jurisdiction, tax_type, provider, before, after, approved_by }` → provider-resolution refresh, audit. (Flipping a market to an external provider is governed config.)

### `tax.nexus.threshold_approaching` / `tax.nexus.threshold_crossed`
key `entity_id` · `{ entity_id, jurisdiction, region, sales_to_date, txn_count_to_date, threshold_amount, threshold_txn_count, status, crossed_at? }` → tax-desk notification, registration workflow, audit.

### `tax.quote.failed`
key `order_id` · `{ order_id, context, provider, ship_to, error, fell_back_to? }` → tax-desk alert + order hold (the fail-closed path, §4.4-fallback).

### `intrastat.declaration.generated` / `ec_sales.generated`
key `entity_id` · `{ entity_id, jurisdiction, period_key, flow?, line_count, total_value, currency }` → reporting/export, audit. (Dormant year-1.)

---

## 6. State machine (the quote lifecycle per order/tranche)

A determination is **not** a single event — it is re-quoted as facts firm up, and finalised at the recognition point.

```
                 (re-quote on line/ship-to/qty change — supersedes prior)
                       ┌───────────────────────────┐
                       ▼                            │
preview ──order placed──► provisional ──dispatch.delivered──► final ──commit──► committed
   │ (non-binding,            │ (on order.placed;             │ (authoritative;        │ (external: adapter.commit;
   │  /pricing/quote)         │  provisional VAT on totals)   │  per tranche on its    │  rate-table: no-op)
   │                          │                               │  delivery — ASC 606)   │
   └── (no order)             └── cancelled ──────────────────┴── return/RMA (doc 09) ─► voided (adapter.voidTax)
```

- **preview** — `context='quote_preview'`, non-binding; powers `/pricing/quote` live as the desk edits lines. Many previews; each supersedes the last for the cart.
- **provisional** — `context='order_placed'`; the order's `subtotal_ex_vat`/`vat_total`/`total_inc_vat` (doc 02 §F) carry this. Re-quoted on every amendment (doc 04 §Orders) — a new ship-to or line re-runs determination and supersedes the prior `tax_quote`.
- **final** — `context='invoice'`, produced **on `dispatch.delivered`** (the single recognition point; doc 04 §Ledger). This is what the `order_invoice` totals and the `VAT:<entity>` ledger control book against. **Per tranche** (each drop invoices separately, doc 02 §F), so one order can have several `final` quotes.
- **committed** — external providers finalise the vendor transaction (`adapter.commit`) so it lands in the vendor's filing; rate-table is a no-op.
- **voided** — on cancel pre-delivery, or on return/RMA (doc 09) post-delivery: external `adapter.voidTax(ref)`; the ledger reverses the `VAT` control (doc 09 reversal).

Invariant: the **invoice and the `VAT` control always reference exactly one `final` `tax_quote`** (`order_invoice_id` set); no invoice issues without a `final` quote; no `final` quote is ever edited (a correction is a new quote + a credit note, doc 09 / document-generation workstream).

---

## 7. The document / audit trail (every tax decision reproducible)

This is the SOX/PCAOB requirement applied to tax (doc 14 §5):

1. **Every determination is persisted immutable** (`tax_quote` + `tax_quote_line`, append-only, superseded never deleted). The `request_snapshot` + `response_snapshot` are the complete input/output.
2. **Reproducible by replay.** For `rate_table`: re-run `determine` over `request_snapshot` with `tax_regime` rows effective at `rates_asof` ⇒ byte-identical `response_snapshot` (the reproducibility control, §8). For `external`: the `request_snapshot` + `provider_ref` reproduce the vendor call, and the persisted `response_snapshot` is the **retained evidence** of what the authority-of-record returned (doc 14 §5.3).
3. **Rates are dated, not edited** (`tax_regime` versioned by `effective_from`; `tax.regime.changed` audited maker-checker) — so the rate in force at any historic `occurred_at` is recoverable, and a reslice (doc 14 §2.2) re-taxes correctly under the right historic rate.
4. **Lineage** (doc 14 §5.1): a reported VAT figure → the `VAT:<entity>` TB transfers → `tax.quoted` event → the `tax_quote` (request/response) → the order/lines/ship-to/party facts → the `tax_regime` version or vendor `provider_ref`. Surfaces in the Auditability Center lineage explorer (doc 14 §6).
5. **Nexus crossings are alerted + audited** (§4.5) — the obligation trail is complete.

`tax_quote` and its lines join the `audit_log` projection for any **manual override** (a tax-specialist overriding a determination is maker-checker, audited, and writes a new superseding quote with `context` annotated — never an in-place edit).

---

## 8. Controls (extends doc 14 §4/§5 — ICFR)

Registered `control` rows with `evidence_query` (re-performable, doc 14 §6):

| control | assertion | type | evidence (re-perform) |
|---|---|---|---|
| Tax-quote reproducibility (rate-table) | valuation, accuracy | detective | re-run `determine` over `tax_quote.request_snapshot` with `tax_regime`@`rates_asof`; must reproduce `response_snapshot` exactly |
| External-quote evidence retained | completeness | detective | every `external` `tax_quote` has a `provider_ref` + non-null `response_snapshot` (retained authority-of-record evidence) |
| VAT conservation (line-vs-invoice) | valuation | detective | `Σ tax_quote_line.line_tax_total == tax_quote.total_tax` under the regime's rounding policy (largest-remainder, doc 14 §1.3) |
| Invoice ↔ final quote tie | existence, cutoff | detective | every `order_invoice` references exactly one `final` `tax_quote`; `order_invoice.vat_total == tax_quote.total_tax` |
| Ledger VAT ↔ quote tie | completeness | detective | `Σ VAT:<entity>` postings for a period == `Σ final tax_quote.total_tax` (non-reverse-charge) for that entity/period |
| Reverse-charge zero-rated correctly | valuation, presentation | detective | every `intra_eu_b2b_reverse` quote has `reverse_charge=true`, `total_tax=0`, a **valid** buyer VAT ID, and the EC-sales line exists |
| Nexus gating | rights_obligations, completeness | detective | no destination tax charged where no `collects_tax` registration / provider-asserted nexus; every crossed threshold has an alert + (eventually) a registration |
| Rate-change governance | presentation | preventive | every `tax_regime`/`tax_routing` change is dated, maker-checker approved, and emitted (`tax.regime.changed`/`tax.routing.changed`) |

These run continuously / at close, write `control_run` rows, and surface in the Auditability Center (doc 14 §6 controls register, lineage explorer, reconciliation dashboard). The **Ledger VAT ↔ quote tie** is registered as a `reconciliation(type)` extension alongside `tb_vs_gl` (doc 02 §N / doc 14 §5.2).

---

## 9. Permissions & data-layer mapping (extends doc 05)

| object_type | sections | layers (view/edit) | who (seed roles, doc 05 §4) |
|---|---|---|---|
| `tax_regime` | `tax_config` | `commercial` (view); edit gated, maker-checker | `tax_specialist` view/propose; **CFO** approve; `finance`/`auditor` view |
| `tax_routing` | `tax_config` | `commercial` (view); edit gated, maker-checker | `tax_specialist` propose; **CFO** approve; `admin` view |
| `tax_registration` | `tax_config` | `commercial` (view) | `tax_specialist` manage; `finance`/`auditor` view |
| `nexus_profile` | `tax_config` | `commercial`/`volume` (units + thresholds) | `tax_specialist`/`finance` view; `auditor` read |
| `tax_quote` / `tax_quote_line` | — | `commercial` (tax amounts + breakdown); `volume` (qty/units only) | `tax_specialist`/`finance`/`customer_service_agent` view; `auditor` read; override = maker-checker |
| `intrastat_*` / `ec_sales_*` | `tax_config` | `commercial` (values) | `tax_specialist`/`finance`; `auditor` read |

`field_layer_map` additions (drive doc 05 §3 projection): `tax_quote.total_tax`, `tax_quote_line.line_tax_total|taxable_amount|components|effective_rate_pct`, `tax_regime.rate_percent`, `intrastat_line.invoice_value`, `ec_sales_line.net_value` → **`commercial`**; `tax_quote_line.qty`/unit counts and `nexus_profile.txn_count_to_date` → **`volume`**; `buyer_tax_id`/`tax_registration_number` → **`pii`** (a tax/VAT number is personal data for a sole trader). So a **`volume`-only** principal sees that a line was taxed and the quantity but **not the tax amount**; a `commercial` principal sees amounts and the jurisdiction breakdown. Tax determination **never touches `profitability`** (cost/margin) — tax is computed off the **ex-tax price**, not cost, so a tax viewer never gains margin visibility. The **`inter_entity`** wall is unchanged: an `intercompany_import` quote's amounts project on `commercial`, but the transfer price / lot cost it was computed on stays `inter_entity` (doc 13 §9) — the tax engine receives `customs_value` as an opaque `taxable_amount`, never the cost decomposition.

**Segregation of duties (maker-checker, doc 05 §4 / doc 14 §4):** `tax_regime` rate changes, `tax_routing` provider changes, and any **manual tax override** on a quote are maker-checker (proposer `tax_specialist` ≠ approver `CFO`). Posting a VAT control into a `locked` `accounting_period` is rejected at the ledger boundary regardless of role (doc 14 §2.4). All audited (`audit_log` + immutable `tax.*` events + the TB `VAT` postings; doc 05 §5).

---

## 10. REST contracts (extends doc 06)

Base `/api/v1`; Keycloak bearer; authorisation per doc 05; money as `{amount,currency}`; layer-projected (tax amounts absent for `volume`-only principals; `buyer_tax_id` absent without `pii`). Standard errors per doc 06; `422 tax_determination_unavailable` when an external provider fails closed at a binding context (§4.4-fallback).

```
## Determination (the engine surface; consumed by pricing/quote, orders, invoicing, intercompany)
POST   /tax/quote               { context, entity_id, ship_from, ship_to, party_tax_status, buyer_tax_id?,
                                  incoterm?, currency, as_of, lines:[{ref, product_variant_id?, tax_category_code?,
                                  hs_code?, qty, taxable_amount}] }
                                  → TaxQuote { supply_kind, reverse_charge, lines:[{ref, line_tax_total, effective_rate_pct,
                                               reverse_charge, regime_code?, components[]}], totals, rounding_policy,
                                               duty?, import_vat?, provider, determination_ref?, rates_asof }
                                  // 200 quoted | 422 tax_determination_unavailable (external fail-closed at binding context)
GET    /tax/quotes?order_id=&context=&status=         → [TaxQuote]   (history incl. superseded; layer-projected)
GET    /tax/quotes/{id}                               → TaxQuote (request_snapshot + response_snapshot; reproducible)
POST   /tax/quotes/{id}/override  { line_overrides:[{ref, regime_code?, reverse_charge?, components?}], reason }
                                  → TaxQuote   // tax_specialist; maker-checker; writes a NEW superseding quote (never edits)

## Config (tax_specialist propose; CFO approve — maker-checker, audited)
GET    /tax/regimes?jurisdiction=&tax_type=&as_of=    → [TaxRegime]
POST   /tax/regimes             { code, rate_percent, jurisdiction, kind, tax_type, region?, economic_zone?,
                                  reverse_chargeable?, rounding_policy, rounding_mode?, provider, effective_from }
                                  → TaxRegime (status=draft)
POST   /tax/regimes/{id}/activate                     → TaxRegime  (CFO; emits tax.regime.changed)
GET    /tax/routing ; POST /tax/routing { market_id?, jurisdiction?, tax_type?, provider, priority }
POST   /tax/routing/{id}/activate                     → TaxRouting  (CFO; emits tax.routing.changed)
GET    /tax/categories ; POST /tax/categories         → TaxCategory
GET    /tax/registrations?entity_id= ; POST /tax/registrations  → TaxRegistration

## Nexus (US/CA economic-nexus monitoring)
GET    /tax/nexus?entity_id=&jurisdiction=&status=    → [NexusProfile]  ({sales_to_date, txn_count_to_date, status, threshold})
POST   /tax/nexus               { entity_id, jurisdiction, region, threshold_amount?, threshold_txn_count?, lookback }
POST   /tax/nexus/{id}/register { tax_registration:{number, effective_from} }   → NexusProfile (status=registered)

## Intrastat / EC sales (dormant year-1; activate per EU entity)
GET    /tax/intrastat?entity_id=&period=&flow=        → IntrastatDeclaration{lines[]}
POST   /tax/intrastat/{id}/submit                     → IntrastatDeclaration (status=submitted)
GET    /tax/ec-sales?entity_id=&period=               → [EcSalesLine]
```

`/pricing/quote` (doc 06) calls `/tax/quote` internally with `context='quote_preview'`; order placement and `dispatch.delivered` call it with `order_placed` / `invoice`; the intercompany engine (doc 13 §6) calls it with `context='intercompany_import'` — all through the same `TaxProvider` boundary.

---

## Acceptance

A subsystem implementation is **done** when:

1. **One interface, two paths, routing is config.** UK/EU/single-rate markets compute through `RateTableTaxProvider`; US and Canada route to `ExternalTaxProvider` purely via a `tax_routing` row; the **caller never knows which ran** and the `TaxQuote` contract is identical. **Year-1 = UK VAT 20 only, all rate-table**; opening the US/CA is a `tax_routing` row + an adapter, no caller/engine/schema change.
2. **Place of supply is correct.** `classifySupply` resolves `domestic`, `intra_eu_b2b_reverse` (valid buyer VAT ID ⇒ 0%, buyer accounts), `intra_eu_b2c`, `export` (zero-rated), `import` (ROW→EU/UK and into CH/NO non-EU) from ship-from / ship-to / party tax status — and an invalid/missing buyer VAT ID falls back to charging destination VAT.
3. **US destination tax is multi-level and nexus-gated.** A US sale returns a **state + county + city/district** `components` breakdown summing to the line tax; tax is charged **only** where a `collects_tax` registration / provider-asserted nexus exists; crossing an economic-nexus threshold flips `nexus_profile` and emits `tax.nexus.threshold_crossed` (alerted, audited) — and **no tax is charged for a state with no registration**.
4. **Canada is federal + provincial.** A CA sale returns GST + PST/QST (two components) or a single HST component by destination province — never a single flat "Canada rate".
5. **Rounding is per `tax_regime`, line-vs-invoice, conserving.** A regime configured `line` rounds each line then sums; one configured `invoice` sums exact line taxes, rounds the total once, and **re-allocates by largest-remainder so `Σ line tax == invoice tax` exactly** (doc 14 §1.3) — proven by the VAT-conservation control.
6. **Determination is reproducible (the audit trail).** Every quote persists `request_snapshot` + `response_snapshot`; re-running a rate-table determination over the snapshot with the `tax_regime` version effective at `rates_asof` reproduces the response **exactly**; an external quote retains the vendor `provider_ref` + response as evidence; a VAT figure drills ledger → `tax.quoted` → `tax_quote` → facts → rate version / provider ref (doc 14 §5.1).
7. **Recognition-point binding.** The **invoice and the `VAT:<entity>` control reference exactly one `final` quote**, produced on `dispatch.delivered` per tranche (ASC 606); no invoice issues without a `final` quote; a cancel/return voids it (external `adapter.voidTax`) and the ledger reverses. An external-provider failure at a binding context **fails closed** (`422`, order holds) — never silently books zero/guessed tax.
8. **Access wall holds.** Tax **amounts** + jurisdiction breakdown project to `commercial`; quantities to `volume`; `buyer_tax_id`/VAT numbers to `pii`; tax determination never exposes `profitability` (cost/margin) and never the `inter_entity` transfer price an `intercompany_import` quote was computed on. Rate, routing and override changes are maker-checker (tax_specialist ≠ CFO); no VAT posts into a `locked` period.
9. **Intercompany boundary.** The engine answers `context='intercompany_import'` (doc 13 §6) with duty into landed cost + import VAT recoverable/capitalised + `determination_ref`, through the **same** `TaxQuote` contract; year-1 domestic UK ← Luxshare-UK has no duty leg.

> Supports **M12** (intercompany + tax/customs) and **M13** (ERP/GL & invoicing); consumed by **pricing/quote** (doc 04 §Pricing, doc 06 `/pricing/quote`), **invoicing** (doc 02 §F `order_invoice`), and the **intercompany** engine (doc 13 §6) — all through the one pluggable `TaxProvider` / `TaxQuote` boundary.
