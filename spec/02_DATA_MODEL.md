# 02 — Data Model (PostgreSQL)

Conventions (per 00): every table has `id UUID PK DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`, optional `deleted_at TIMESTAMPTZ`. Money = `NUMERIC(18,4)` + `CHAR(3)` currency. Those common columns are omitted from the tables below; only domain columns are listed. `→ X` denotes a FK to `X.id`.

---

## A. Organisation, Tax, FX

### `entity` — legal operating entity
Entities, their currencies and tax registrations are **fully data-driven and customisable** — Conduit must run both the year-1 simple setup and a future multi-tier group without schema change (see *procurement topology* below).
| column | type | constraints | notes |
|---|---|---|---|
| name | TEXT | NOT NULL | |
| jurisdiction | CHAR(2) | NOT NULL | ISO country |
| functional_currency | CHAR(3) | NOT NULL | |
| entity_type | TEXT | NOT NULL | `operating`/`holding`/`procurement_hub` |
| group_parent_id | UUID | → entity NULL | ownership |
| procurement_parent_id | UUID | → entity NULL | who this entity buys from (intercompany source) |
| status | TEXT | NOT NULL DEFAULT 'active' | |

> **Procurement topology (configurable).** The buying chain is expressed via `procurement_parent_id` + `transfer_price_policy` (doc §I), per hop. Target group: operating-market entities ← **Singapore** procurement hub ← **Luxshare**. **Year-1 ("pre-global") mode:** a single hop — the **UK** entity buys from a **Luxshare UK** supplier entity; minimal intercompany. Switching to the multi-tier chain is configuration (add the Singapore hub entity + transfer-price policies), not a migration. Conduit is the system that makes the year-1 setup workable and the later topology a config change.

### `tax_registration`
entity_id → entity; `tax_type TEXT` (`VAT`/`GST`); `number TEXT`; `jurisdiction CHAR(2)`; `effective_from DATE`, `effective_to DATE NULL`. UNIQUE(entity_id, tax_type, jurisdiction, effective_from). Fully editable (admin) — new markets/registrations are data.

### `tax_regime` (from Athena, generalised)
`code TEXT PK-unique` (`GB_STANDARD`,`IE_STANDARD`,`AU_STANDARD`,`TAX_FREE`,`REVERSE_CHARGE`,`IMPORT`); `rate_percent NUMERIC(7,4) NOT NULL`; `jurisdiction CHAR(2)`; `kind TEXT` (`standard`/`zero`/`reverse_charge`/`import`).

### `fx_rate`
`from_ccy CHAR(3)`, `to_ccy CHAR(3)`, `rate NUMERIC(18,8) NOT NULL`, `rate_type TEXT NOT NULL` (`spot`/`period_close`/`budget`), `effective_date DATE NOT NULL`, `source TEXT`. UNIQUE(from_ccy,to_ccy,rate_type,effective_date). Index(from_ccy,to_ccy,effective_date DESC). **Group presentation currency = USD** (translation target for consolidated reporting).

### `fx_hedge` — first-class hedge register
Represents hedges against specific currency pairs with validity windows; **its own admin permission set** (doc 05 §treasury), and it feeds **consolidated reporting** and the FX rate applied to cost (doc 04 §FX).
| column | type | notes |
|---|---|---|
| pair_from | CHAR(3) NOT NULL | e.g. `USD` |
| pair_to | CHAR(3) NOT NULL | e.g. `GBP` |
| instrument | TEXT NOT NULL | `forward`/`option`/`swap`/`collar` |
| contracted_rate | NUMERIC(18,8) NOT NULL | the hedged rate |
| notional | NUMERIC(18,4) NOT NULL | in `pair_from` |
| notional_used | NUMERIC(18,4) DEFAULT 0 | designated/consumed against purchases |
| entity_id | UUID → entity | hedging entity / operating market |
| valid_from | TIMESTAMPTZ NOT NULL | window |
| valid_to | TIMESTAMPTZ NOT NULL | window |
| status | TEXT DEFAULT 'active' | `active`/`closed`/`expired` |
| counterparty | TEXT | bank/desk |
| reference | TEXT | external contract id |

A `lot_batch.hedge_ref` designating a hedge takes the hedge's `contracted_rate` as the FX applied to that lot's USD cost (doc 04 §FX); consolidated reporting uses the hedge register to translate exposure to USD.

### `market`
`code TEXT`, `name TEXT`, `jurisdiction CHAR(2)`, `currency CHAR(3) NOT NULL`, `default_locale TEXT`, `tax_model TEXT` (`vat`/`gst`/`us_sales_tax`/`export`). **Market ≠ entity.** Each market maps to one operating entity (the pattern in §A `entity`); currencies/locales/tax are data.

**Seed — supported markets (derived from the locale list; one entity + tax registration + functional currency per country, added as each opens):**

| Market | Cur | Tax model (seed rate*) | Locales (lang) |
|---|---|---|---|
| United States 🇺🇸 | USD | **us_sales_tax** (destination, per state — needs tax engine) | en, es |
| Canada 🇨🇦 | CAD | **gst/hst+pst** (federal+provincial — tax engine) | en, fr |
| United Kingdom 🇬🇧 | GBP | vat 20 | en |
| Germany 🇩🇪 | EUR | vat 19 | de, en |
| Austria 🇦🇹 | EUR | vat 20 | de, en |
| Switzerland 🇨🇭 | CHF | vat 8.1 (non-EU) | de, fr |
| Netherlands 🇳🇱 | EUR | vat 21 | nl, en |
| Belgium 🇧🇪 | EUR | vat 21 | nl, fr |
| Ireland 🇮🇪 | EUR | vat 23 | en, ga |
| France 🇫🇷 | EUR | vat 20 | fr, en |
| Spain 🇪🇸 | EUR | vat 21 | es, en |
| Italy 🇮🇹 | EUR | vat 22 | it, en |
| Portugal 🇵🇹 | EUR | vat 23 | pt, en |
| Poland 🇵🇱 | PLN | vat 23 | pl, en |
| Norway 🇳🇴 | NOK | vat 25 (non-EU/EEA) | no, en |
| Sweden 🇸🇪 | SEK | vat 25 | sv, en |
| Denmark 🇩🇰 | DKK | vat 25 | da, en |
| Finland 🇫🇮 | EUR | vat 25.5 | fi, en |
| Japan 🇯🇵 | JPY | consumption 10 | ja, en |
| Australia 🇦🇺 | AUD | gst 10 | en |
| New Zealand 🇳🇿 | NZD | gst 15 | en |
| Thailand 🇹🇭 | THB | vat 7 | th, en |
| International 🌍 | USD/EUR | export / reverse-charge | en, fr, de, es, it, nl |

\* indicative seed rates — confirmed/maintained as `tax_regime` data (rates change). **US sales tax and Canada GST/HST/PST are not single rates** — destination-based and multi-level; they require the tax engine and almost certainly a tax-calc integration (e.g. Avalara/TaxJar/Stripe Tax) — see doc 10 §B. EU intra-community, CH/NO (non-EU), and `International` (export/reverse-charge) also route through the tax engine. **Year-1 "pre-global" seed = UK only** (GBP, VAT 20, en), buying from Luxshare-UK; everything above is the configured roadmap, switched on per market as it opens.

### `currency`
`code CHAR(3) PK` (USD, CAD, GBP, EUR, CHF, PLN, NOK, SEK, DKK, JPY, AUD, NZD, THB), `name TEXT`, `minor_units INT` (e.g. JPY=0), `rounding TEXT`. Money is always amount + currency; **group presentation currency = USD**. (Typed `Money`/rounding spec: doc 14 §1.)

### `exchange_rate` (provenanced spot register; doc 14 §1.4)
`base CHAR(3)`, `quote CHAR(3)`, `rate NUMERIC(18,8)`, `rate_type TEXT` (`spot`/`hedge`/`closing`/`average`), `as_of DATE`, `source TEXT`, `captured_at TIMESTAMPTZ`. UNIQUE(base, quote, rate_type, as_of). Every conversion records which row it used; hedged lots use `fx_hedge.contracted_rate` instead.

### `accounting_period` (close & lock; doc 14 §2.4)
`entity_id UUID → entity`, `scope TEXT` (`day`/`month`/`quarter`/`year`), `period_key TEXT` (e.g. `2027-Q1`), `reporting_tz TEXT`, `status TEXT` (`open`/`closed`/`locked`), `closed_by UUID NULL`, `closed_at TIMESTAMPTZ NULL`. **No posting to a `locked` period** (enforced at the ledger boundary); late/material items use a controlled prior-period adjustment (maker-checker + CFO).

### `locale`
Conduit is multi-locale (the app and customer-facing documents render in the customer's language; numbers/dates/currency format per locale).
`code TEXT PK` (BCP-47, e.g. `en-GB`,`de-DE`,`fr-CA`,`ga-IE`,`ja-JP`,`th-TH`), `language CHAR(2)`, `region CHAR(2)`, `name TEXT`, `enabled BOOLEAN`. **Languages in scope:** en, es, fr, de, nl, ga, it, pt, pl, no, sv, da, fi, ja, th. Scripts include CJK (ja) and Thai — the app and document fonts must cover them.

### `channel`
`code TEXT`, `name TEXT`, `parent_channel_id UUID → channel NULL`, `market_id UUID → market NULL` (a channel may be market-specific, e.g. retail per geography). **Seed (from H6Q):** `retail`, `installer`, `distributor`, `energy`, `automotive`; sub-channels `energy.octopus`, `energy.ovo`, `retail.inbound`, `retail.checkout`, and **retail per market** (`retail.uk`, `retail.ie`, `retail.au`) each with its own agents (#13). **Channels/segments are runtime data:** adding a category or channel is attributing customer accounts to it in the UI — no code, no migration (#12). H6Q rolls up by whatever channel an account is attributed to.

---

## B. Identity & Access (detail in doc 05)

### `app_user`
`keycloak_id TEXT UNIQUE NOT NULL`, `name TEXT`, `email CITEXT UNIQUE`, `status TEXT DEFAULT 'active'`, `team_id UUID → team NULL`.

### `role` (permission set)
`name TEXT UNIQUE`, `description TEXT`, `is_preset BOOLEAN DEFAULT false`.

### `permission`
role_id → role; `object_type TEXT NOT NULL`; `action TEXT NOT NULL` (`view`/`edit`/`create`/`delete`/`approve`/`export`); `section TEXT NULL` (field-group, e.g. `inter_entity_pricing`); `viewable_layers TEXT[] NOT NULL DEFAULT '{}'`; `editable_layers TEXT[] NOT NULL DEFAULT '{}'`; `data_breadth TEXT NOT NULL DEFAULT 'scoped'` (`all`/`team`/`own`/`scoped`).

### `role_assignment`
user_id → app_user; role_id → role; `scope_entities UUID[]`; `scope_markets UUID[]`; `scope_channels UUID[]`; `breadth_override TEXT NULL`.

### `data_layer`
`code TEXT UNIQUE` (`volume`,`commercial`,`profitability`,`commission`,`pii`,`inter_entity`), `name TEXT`.

### `field_layer_map`
`object_type TEXT`, `field TEXT`, `data_layer_id → data_layer`. UNIQUE(object_type, field). Drives server-side response projection (doc 05 §Projection).

### `team`
`name TEXT`, `member_user_ids UUID[]`.

---

## C. CRM — parties, roles & hierarchy (flexible, not hardcoded)

**Principle.** We transact with many kinds of party — individuals, **installers**, wholesalers and their **branches**, distributors, energy partners, fleets, OEMs. We do **not** model each as its own hardcoded object. There is **one `party`**; its **classification is data** (`party_type`), and **capabilities are attachable role profiles**. A party becomes a **billing entity** by attaching a valid `billing_profile` — not by being a special subclass. The flexibility is in *which parties hold which roles*; the billing/tax/credit data stays in **defined, auditable schemas** (rigour where SOX/GAAP needs it — money never gets a freeform bag). Adding a new kind of party (or making an existing one billable) is configuration + attaching a profile, not a schema change.

### `party_type` (data-driven classification)
`code TEXT PK` (`individual`,`installer`,`wholesaler`,`branch`,`distributor`,`energy_partner`,`fleet`,`oem`,`other`), `name`, `is_organization BOOLEAN`, `required_profiles TEXT[]` (governance — e.g. a `wholesaler` requires billing + credit before it can trade). New kinds are rows, not code.

### `party` (anyone we transact with)
| column | type | notes |
|---|---|---|
| display_name | TEXT NOT NULL | |
| legal_name | TEXT NULL | orgs |
| party_type | TEXT → party_type | data-driven kind |
| is_organization | BOOLEAN NOT NULL | |
| parent_party_id | UUID → party NULL | hierarchy (master → division → **branch**) for any org |
| status | TEXT DEFAULT 'active' | `active`/`on_hold`/`closed` |
| default_entity_id | UUID → entity | servicing Hypervolt entity |
| channel_id, market_id | UUID | |
| segment | TEXT NULL | H6Q rollup |
| pricing_tier_id | UUID → pricing_tier NULL | (may inherit from parent) |
| preferred_locale | TEXT → locale.code NULL | document/comms language |
| roles | TEXT[] NOT NULL DEFAULT '{}' | capability tags (`customer`,`bill_to`,`ship_to`,`installer`,`reseller`,`forecastable`) aligned to attached profiles |
| customer_po_required | BOOLEAN DEFAULT false | |
| account_manager_user_id | UUID → app_user NULL | per node (a branch has its own) |
| owner_user_id | UUID → app_user NULL | |
| external_refs | JSONB | e.g. UFE installer id, HubSpot id |

Indexes: `(party_type)`, `(parent_party_id)`, `lower(display_name)`, `(channel_id, market_id)`, `(segment)`, `(account_manager_user_id)`, GIN(`roles`).

### `individual_details` (person-party fields)
party_id → party; `first_name`,`last_name`,`email CITEXT`,`phone`,`phone_country`,`marketing_consent BOOLEAN`,`tax_status TEXT` (`consumer`/`business`). Present when the party is a person (retail buyer, sole trader).

### `billing_profile` — the capability that makes a party billable
| column | type | notes |
|---|---|---|
| party_id | UUID → party | |
| billing_name | TEXT NOT NULL | legal invoice name |
| bill_to_address_id | UUID → address NOT NULL | |
| tax_registration_number | TEXT NULL | **required where the jurisdiction mandates** (validation policy) |
| tax_regime_default | TEXT → tax_regime.code NOT NULL | |
| currency | CHAR(3) NOT NULL | |
| payment_terms_days | INTEGER NOT NULL | |
| invoice_locale | TEXT → locale.code | |
| e_invoicing_id | TEXT NULL | PEPPOL etc. |
| bills_to_party_id | UUID → party NULL | **central billing** — branch delegates to the payer (the master holds the profile) |
| status | TEXT DEFAULT 'active' | |

**A party can be invoiced iff it resolves to a complete, valid `billing_profile`** (its own, or via `bills_to_party_id`). The required-field policy is enforced at *promote-to-billable* time and can vary by jurisdiction/`party_type` — but the storage is this fixed schema, not freeform.

### `credit_profile`
party_id → party; `credit_limit NUMERIC(18,4)`,`currency CHAR(3)`,`terms_days INT`,`policy TEXT` (`warn`/`block`),`scope TEXT` (`self`/`shared_with_parent`). Attach when the party trades on credit. (Shared scope draws on the parent/master pool — doc 04 §Credit.)

### `contact` (people at a party — per node/branch)
party_id → party; `first_name`,`last_name`,`role TEXT`,`email CITEXT`,`phone`,`phone_country`,`is_primary BOOLEAN`,`marketing_consent BOOLEAN`. Index(party_id), Index(lower(email)).

### `address`
`owner_type TEXT` (`party`), `owner_id UUID → party`; `type TEXT` (`billing`/`ship_to`/`registered`); `line1`,`line2`,`city`,`region`,`postcode`,`country CHAR(2)`; `is_default BOOLEAN`.

### `pricing_tier`
`code TEXT UNIQUE`, `name TEXT`. (A node may inherit the parent's tier or hold its own.)

> **This supersedes the earlier `company` / `individual_customer` tables.** They are now `party` rows distinguished by `party_type` + attached profiles. **Throughout the rest of the pack, `company`/`company_id` = a `party` of an organization type; `individual_customer`/`individual_id` = a `party` of type `individual`; `branch_company_id` = a branch `party`.** Partner roles on orders (sold-to/bill-to/ship-to/payer) are `party` references; per-branch stats, contacts and credit attribute to the transacting node and roll up via `parent_party_id` (CEF: master party + branch parties, branch `bills_to` master, credit `scope='shared_with_parent'`). **Installers** are simply parties of type `installer`: the same installer can be a non-billing activation/referral party *and*, once they buy from us, a billing party — by attaching a `billing_profile`; their UFE installer identity links via `external_refs`.

### `deal`
| column | type | notes |
|---|---|---|
| company_id | UUID → company NOT NULL | |
| primary_contact_id | UUID → contact NULL | |
| pipeline_id | UUID → pipeline NOT NULL | |
| stage_id | UUID → pipeline_stage NOT NULL | |
| owner_user_id | UUID → app_user | sales agent |
| name | TEXT | |
| value | NUMERIC(18,4) | expected |
| currency | CHAR(3) | |
| expected_close | DATE | |
| volume_p20/p50/p80 | INTEGER | scenario volumes (feed H6Q) |
| status | TEXT | `open`/`won`/`lost` |
| won_order_id | UUID → order NULL | set on close-won |
| channel_id, market_id, entity_id | UUID | scope |

### `pipeline`, `pipeline_stage`
`pipeline`: `name`, `channel_id`. `pipeline_stage`: pipeline_id → pipeline, `name`, `position INT`, `probability_pct NUMERIC(5,2)` (deal-stage weight for H6Q). 

### `deal_line` 
deal_id → deal; product_variant_id → product_variant; `qty INTEGER`; `unit_price NUMERIC(18,4)`; `currency CHAR(3)`.

### `activity` (account history timeline)
`subject_type TEXT` (`company`/`contact`/`deal`), `subject_id UUID`, `kind TEXT` (`note`/`call`/`email`/`event`/`system`), `body TEXT`, `actor_user_id UUID`, `occurred_at TIMESTAMPTZ`, `event_id UUID NULL` (when projected from the event stream). Index(subject_type,subject_id,occurred_at DESC).

---

## D. Catalogue & Variants

### `product_family`
`code TEXT UNIQUE`, `name TEXT` (`Home 2.2`,`Home 3.0`,`Home 3 Pro`).

### `product_variant`
| column | type | notes |
|---|---|---|
| family_id | UUID → product_family | |
| sku | TEXT UNIQUE NOT NULL | canonical (retail) SKU |
| trade_sku | TEXT NULL | trade variant code |
| mrp_sku | TEXT NULL | maps to manufacturing/MRP |
| length_m | NUMERIC(4,1) | 5 / 7.5 / 10 |
| colour | TEXT | `space_grey`/`ultra_black`/`ultra_white` |
| connector_type | TEXT | `type1`/`type2` |
| generation | TEXT NOT NULL | `v2`/`v3` (drives activation/serial logic) |
| is_serialised | BOOLEAN NOT NULL | |
| is_kit | BOOLEAN DEFAULT false | |
| uom | TEXT DEFAULT 'each' | |
| hs_code | TEXT | customs |
| std_cost | NUMERIC(18,4) | reference; actual cost is per-batch |
| warranty_months | INTEGER | |
| status | TEXT DEFAULT 'active' | |

Non-charger SKUs (cables, adapters, drilling template, warranty products) are variants with `is_serialised=false`.

### `kit_component`
kit_variant_id → product_variant; component_variant_id → product_variant; `qty INTEGER`.

### `product_translation` — localized catalogue
product_variant_id → product_variant (or product_family_id); `locale TEXT → locale.code`; `display_name TEXT`; `description TEXT`. UNIQUE(product_variant_id, locale). Customer-facing surfaces (app, documents, quotes) render the variant in the customer's `preferred_locale`, falling back to the market default then English. (Marketing copy stays on the website; this is the operational name/description.)

---

## E. Pricing & ADLP (supersedes Athena `product/bundle/price`)

### `price_rule` — the ADLP table (runtime, versioned, governed)
| column | type | notes |
|---|---|---|
| surface | TEXT NOT NULL | `customer` / `inter_entity` |
| product_variant_id | UUID → product_variant NULL | |
| bundle_id | UUID → product_variant NULL | (kit) |
| channel_id | UUID → channel NULL | customer surface |
| market_id | UUID → market NULL | customer surface |
| entity_id | UUID → entity NULL | |
| currency | CHAR(3) NOT NULL | |
| tax_regime | TEXT → tax_regime.code | |
| authorised_price | NUMERIC(18,4) NOT NULL | ex-VAT list |
| max_discount_pct | NUMERIC(5,2) NOT NULL DEFAULT 0 | ADLP band |
| min_qty | INTEGER DEFAULT 1 | volume break |
| from_entity_id / to_entity_id | UUID → entity NULL | inter_entity surface |
| tp_method | TEXT NULL | `cost_plus`/`resale_minus`/`fixed` |
| tp_markup_pct | NUMERIC(7,4) NULL | |
| version | INTEGER NOT NULL | |
| effective_from | TIMESTAMPTZ NOT NULL | |
| effective_to | TIMESTAMPTZ NULL | |
| status | TEXT NOT NULL | `draft`/`active`/`superseded` |
| owner_user_id | UUID | |
| approved_by | UUID NULL | CEO for governed changes |

Resolution index: `(surface, product_variant_id, channel_id, market_id, currency, status, effective_from DESC)`. **`surface` is `field_layer_map`'d so `inter_entity` rows project only to the `inter_entity` layer** (doc 05).

### `pricing_change_log`
Append-only: price_rule_id, before JSONB, after JSONB, actor, approved_by, occurred_at. (Also emitted as `PriceRuleChanged`.)

### `rebate`
company_id/agent_id, `budget_ref TEXT`, `type TEXT`, `basis TEXT`, `amount NUMERIC(18,4)`, `currency CHAR(3)`, `status TEXT`, `performance_link TEXT`, `hubspot_ref TEXT`, `approved_by UUID NULL`.

### `adlp_exception`
| column | type | notes |
|---|---|---|
| order_id | UUID → order NULL | |
| order_line_id | UUID → order_line NULL | |
| requested_price | NUMERIC(18,4) | |
| requested_discount_pct | NUMERIC(5,2) | |
| justification | TEXT | |
| volume_expectation | INTEGER | |
| volume_denomination | TEXT | `P80`/`P50`/`P20` |
| strategic_importance | TEXT | |
| doc_refs | JSONB | attachments |
| margin_assessment | JSONB | deal-desk computed |
| status | TEXT NOT NULL | `draft`/`pending_ceo`/`approved`/`rejected` |
| approved_by | UUID NULL | CEO only |
| approval_memo_ref | TEXT NULL | immutable memo |
| decided_at | TIMESTAMPTZ NULL | |

---

## F. Orders & Fulfilment

### `order`
| column | type | notes |
|---|---|---|
| order_no | TEXT UNIQUE NOT NULL | human ref |
| type | TEXT NOT NULL | `retail`/`trade`/`reseller`/`agent`/`intercompany` |
| entity_id | UUID → entity NOT NULL | selling entity |
| **sold_to_party_id** | UUID → party NOT NULL | ordering party — the **branch** for a wholesaler PO, or an individual |
| **bill_to_party_id** | UUID → party NOT NULL | payer/invoiced party — resolves via `sold_to`'s `billing_profile.bills_to_party_id` (master for central billing); = sold-to if self-billed |
| contact_id | UUID → contact NULL | branch contact who placed it |
| **customer_po_number** | TEXT NULL | the customer's/branch's PO ref (required if `party.customer_po_required`) |
| deal_id | UUID → deal NULL | |
| agent_id | UUID → sales_agent NULL | |
| channel_id, market_id | UUID | |
| status | TEXT NOT NULL | state machine (doc 04 §Orders) |
| adlp_category | TEXT | `standard`/`exception` |
| ship_to_address_id | UUID → address NULL | consignee — the **branch** delivery address |
| txn_currency | CHAR(3) NOT NULL | |
| functional_currency | CHAR(3) NOT NULL | |
| fx_rate | NUMERIC(18,8) | |
| subtotal_ex_vat | NUMERIC(18,4) | |
| vat_total | NUMERIC(18,4) | |
| total_inc_vat | NUMERIC(18,4) | |
| payment_method | TEXT | `stripe`/`credit`/`invoice` |
| stripe_payment_intent | TEXT NULL | retail |
| order_date | TIMESTAMPTZ | |
| requested_delivery | DATE | |
| created_by | UUID | |
| tb_transfer_id | NUMERIC(39,0) NULL | commitment posting |

Exactly one party fills sold-to (an organization branch or an individual). **Partner roles** (sold-to / bill-to / ship-to / payer) are `party` references and can differ: a CEF *branch* is sold-to + ship-to, CEF *master* is bill-to/payer; AR posts to the **bill-to**, while stats/coverage/sell-through attribute to the **sold-to** node. Indexes: `order_no`, `(sold_to_party_id, order_date DESC)`, `(bill_to_party_id, order_date DESC)`, `(status)`, `(channel_id, market_id, order_date)`, `(customer_po_number)`.

### `order_line`
| column | type | notes |
|---|---|---|
| order_id | UUID → order | |
| product_variant_id | UUID → product_variant | |
| qty | INTEGER NOT NULL | total ordered; = Σ tranche qty when scheduled |
| unit_price_ex_vat | NUMERIC(18,4) | resolved from ADLP |
| discount_pct | NUMERIC(5,2) DEFAULT 0 | |
| tax_regime | TEXT → tax_regime.code | |
| vat_amount | NUMERIC(18,4) | |
| line_total_inc_vat | NUMERIC(18,4) | |
| price_rule_id | UUID → price_rule NULL | provenance |
| adlp_category | TEXT | per-line |
| qty_allocated | INTEGER DEFAULT 0 | rolls up from tranches |
| qty_dispatched | INTEGER DEFAULT 0 | rolls up from tranches |
| is_scheduled | BOOLEAN DEFAULT false | true → fulfilment is per tranche |
| status | TEXT | `open`/`allocated`/`backordered`/`partially_dispatched`/`dispatched`/`cancelled` |
| promised_date | DATE | single-shot promise (when not scheduled) |
| commission_entry_id | UUID → commission_entry NULL | |

### `delivery_tranche` — scheduled / call-off deliveries
A wholesaler orders e.g. 500 units to land as 2×250 on different dates. A scheduled line carries an ordered list of tranches; **each tranche is independently allocated, dispatched, delivered and invoiced** (so revenue + COGS recognise per tranche on its delivery — ASC 606). Backorders/ATP operate per tranche; future tranches feed supply planning and H6Q.
| column | type | notes |
|---|---|---|
| order_line_id | UUID → order_line NOT NULL | |
| seq | INTEGER NOT NULL | 1,2,3… |
| qty | INTEGER NOT NULL | this drop |
| requested_date | DATE NOT NULL | target land date |
| qty_allocated | INTEGER DEFAULT 0 | |
| qty_dispatched | INTEGER DEFAULT 0 | |
| status | TEXT NOT NULL | `scheduled`/`allocated`/`backordered`/`dispatched`/`delivered`/`invoiced`/`cancelled` |
| dispatch_id | UUID → dispatch NULL | the drop that fulfilled it |
UNIQUE(order_line_id, seq). A non-scheduled line is modelled as a single implicit tranche.

### `allocation`
order_line_id → order_line; `tranche_id UUID → delivery_tranche NULL` (which drop); `location_id → location`; `serial_unit_id → serial_unit NULL`; `qty INTEGER`. (Reservation; see ATP algorithm doc 04.)

### `dispatch`
`dispatch_no TEXT`, order_id → order, `tranche_id UUID → delivery_tranche NULL`, `date TIMESTAMPTZ`, `carrier_id → carrier`, `tracking_no TEXT`, `destination JSONB`, `status TEXT`, `otd_due DATE`, `delivered_at TIMESTAMPTZ NULL`, `tb_transfer_id NUMERIC(39,0) NULL`.

### `dispatch_line`
dispatch_id → dispatch; order_line_id → order_line; `tranche_id UUID → delivery_tranche NULL`; `qty INTEGER`. (Serials captured via `serial_unit.dispatch_id`.)

### `carrier`
`name TEXT`, `type TEXT` (`parcel`/`3pl`), `service_levels JSONB`. (Rhenus, DPD, UPS, Rainus.)

### `order_invoice`
order_id → order; `tranche_id UUID → delivery_tranche NULL` (per-drop invoice); `invoice_no TEXT UNIQUE`; `issued_at`; `total_ex_vat`,`vat_total`,`total_inc_vat`; `tax_regime`; `xero_invoice_id TEXT NULL`; `email_state TEXT`; `tb_transfer_id`. (Auto-issued on delivery of the tranche; posts to ledger; Xero consumer fills `xero_invoice_id`.)

### `order_amendment` — audited post-placement change
Orders **can be amended after placement up to a cutoff (pre-dispatch)**; amendment is a **permission-gated admin action** (doc 05). Append-only record of each change.
| column | type | notes |
|---|---|---|
| order_id | UUID → order | |
| actor_user_id | UUID → app_user | must hold `edit:order:amend` |
| before | JSONB | line/qty/price/schedule snapshot |
| after | JSONB | |
| reason | TEXT | |
| occurred_at | TIMESTAMPTZ | |
(Order header carries `amend_cutoff TIMESTAMPTZ NULL` — default = first dispatch; configurable. After cutoff, no amendment, only return/RMA.)

### `rma` / `return` — **first-class** (deep-dive: doc 09, planned)
Returns are a first-class domain with **several types**, not a refund flag. Stub now; full lifecycle (state machine, restock/refurb/scrap routing, replacement issuance, ledger reversal, commission claw, per-type rules) to be deep-dived in **09_RETURNS**.
| column | type | notes |
|---|---|---|
| order_id | UUID → order | |
| type | TEXT NOT NULL | `full_unit` / `part_only` (component) / `multi_unit` / `dead_on_arrival` / `warranty_replacement` / `goodwill` |
| scope | TEXT | `whole_order`/`line`/`serial`/`component` |
| serials | TEXT[] NULL | units returned |
| component_ref | TEXT NULL | part-only returns |
| reason_code | TEXT | |
| disposition | TEXT | `restock`/`refurbish`/`scrap`/`return_to_supplier` |
| refund_amount | NUMERIC(18,4) NULL | |
| replacement_order_id | UUID → order NULL | |
| status | TEXT | (lifecycle in 09) |
| tb_transfer_id | NUMERIC(39,0) NULL | reversal |
| approved_by | UUID NULL | |

---

## G. Inventory, Batch, Serial, Activation

### `location`
entity_id → entity; `code TEXT`, `name TEXT`, `address JSONB`, `type TEXT` (`warehouse`/`3pl`/`site`).

### `stock_item`
entity_id, product_variant_id → product_variant, location_id → location; `qty_on_hand INTEGER`, `qty_allocated INTEGER`, `qty_incoming INTEGER`. `available` = on_hand − allocated (computed/view). UNIQUE(entity_id, product_variant_id, location_id).

### `stock_movement`
`type TEXT` (`receipt`/`dispatch`/`adjustment`/`transfer_out`/`transfer_in`/`intercompany`/`return`/`write_off`/`count_correction`); product_variant_id; location_id; entity_id; `qty INTEGER` (signed); `ref_type TEXT`,`ref_id UUID`; `reason_code TEXT NULL`; actor. **Append-only, immutable** — on-hand is the sum of movements; corrections are new signed movements, never edits.

### `stock_count` / `stock_count_line` — cycle counts
Cycle counts are routine; variances post as approved adjustments.
- `stock_count`: entity_id, location_id, `type TEXT` (`cycle`/`full`), `status TEXT` (`open`/`counted`/`pending_approval`/`approved`/`posted`), `counted_by UUID`, `approved_by UUID NULL`, `scheduled_for DATE`.
- `stock_count_line`: count_id → stock_count; product_variant_id; `system_qty INTEGER` (snapshot); `counted_qty INTEGER`; `variance INTEGER`; `serials_scanned TEXT[] NULL`. Approval converts variances into `stock_movement(type='count_correction')`.

### `stock_transfer` — location/entity moves with in-transit
`from_location_id → location`, `to_location_id → location`, entity-or-intercompany, product_variant_id, `qty INTEGER`, `serials TEXT[] NULL`, `status TEXT` (`requested`/`approved`/`in_transit`/`received`/`cancelled`), `dispatched_at`, `received_at`, `requested_by`, `approved_by`. Posts `transfer_out` (+ in-transit) then `transfer_in` on receipt. Cross-entity transfer = intercompany (doc §I, transfer pricing).

### `stock_adjustment` — write-offs, damage, corrections (maker-checker)
| column | type | notes |
|---|---|---|
| entity_id, location_id | UUID | |
| product_variant_id | UUID → product_variant | |
| serials | TEXT[] NULL | for serialised write-offs |
| qty | INTEGER NOT NULL | signed |
| kind | TEXT NOT NULL | `write_off`/`damage`/`shrinkage`/`found`/`correction`/`quarantine` |
| reason_code | TEXT NOT NULL | |
| evidence_ref | JSONB NULL | photos/notes |
| status | TEXT NOT NULL | `draft`/`pending_approval`/`approved`/`rejected`/`posted` |
| requested_by | UUID → app_user | maker |
| approved_by | UUID → app_user NULL | checker (different person; permission-gated) |
| tb_transfer_id | NUMERIC(39,0) NULL | inventory write-down to ledger on approval |

`serial_unit.status` flows to `quarantined`/`scrapped` on damage/write-off. **All stock operations require an approval workflow** (maker ≠ checker, permission-gated — doc 05) and emit immutable events + `audit_log` (doc 04 §Stock ops).

### `lot_batch`
Cost is **strictly per-lot** (specific identification): the contract manufacturer can reprice the SKU lot-to-lot, and freight, duty and FX all vary independently. No weighted-average.
| column | type | notes |
|---|---|---|
| batch_no | TEXT NOT NULL | calculated scheme (OPEN: doc 07) |
| supplier_id | UUID → supplier | Luxshare |
| product_variant_id | UUID → product_variant | |
| manufactured_date | DATE | |
| received_date | DATE | |
| qty | INTEGER | |
| unit_cost_usd | NUMERIC(18,4) | per-lot Luxshare price — **always USD** |
| fx_rate | NUMERIC(18,8) | USD → entity functional; rate actually applied |
| fx_basis | TEXT NOT NULL | `spot` / `hedged` (which rate basis — audit) |
| hedge_ref | TEXT NULL | designated hedge instrument, if any (treasury/Phalanx) |
| shipping_alloc | NUMERIC(18,4) | freight allocated to lot |
| duty_alloc | NUMERIC(18,4) | import duty allocated to lot |
| landed_unit_cost | NUMERIC(18,4) | = (unit_cost_usd × fx_rate) + per-unit shipping + per-unit duty |

Each `serial_unit.lot_batch_id` therefore resolves a unit's exact cost; margin, commission true-up, inventory valuation and the delivery COGS amount all use this specific value (doc 04 §Ledger/§Inventory).

### `serial_unit`
| column | type | notes |
|---|---|---|
| serial_no | TEXT UNIQUE NOT NULL | hex/device id |
| generation | TEXT NOT NULL | `v2`(`HYPV-`)/`v3`(`0301…`) |
| product_variant_id | UUID → product_variant | |
| lot_batch_id | UUID → lot_batch NULL | |
| status | TEXT NOT NULL | `in_stock`/`allocated`/`dispatched`/`activated`/`returned`/`scrapped` |
| entity_id, location_id | UUID | current |
| order_line_id | UUID → order_line NULL | |
| dispatch_id | UUID → dispatch NULL | |
| company_id | UUID → company NULL | bound on activation (the account) |
| installer_user_id | TEXT NULL | from UFE v1 placement |
| owner_keycloak_id | TEXT NULL | charger owner |
| activated_at | TIMESTAMPTZ NULL | |
| warranty_end | DATE NULL | |

### `unit_lifecycle_event`
serial_unit_id → serial_unit; `event_type TEXT` (`manufactured`/`received`/`stocked`/`allocated`/`dispatched`/`delivered`/`activated`/`in_service`/`transferred`/`quarantined`/`rma`/`returned`/`refurbished`/`scrapped`); entity_id, location_id; `ref_type`,`ref_id`; actor; `occurred_at TIMESTAMPTZ`. Append-only genealogy.

### `activation` (from UFE/Pulsar; first-write-wins per serial)
| column | type | notes |
|---|---|---|
| serial | TEXT PK | first-time only |
| placement_id | UUID NOT NULL | UFE |
| placement_version | INTEGER NOT NULL | v1 = installer |
| installer_user_id/email/name | TEXT | |
| placement_name/country | TEXT | |
| placement_created_at | TIMESTAMPTZ | |
| charger_model | TEXT | |
| charger_mac | TEXT | |
| charger_keycloak_id | TEXT | owner |
| activated_at | TIMESTAMPTZ | |

### `warranty_provision` — per-unit provision register
Warranty is a first-class feature: the clock **starts at activation**, and Conduit maintains the **consolidated warranty exposure** and its **release-to-balance-sheet cycle**. The register is fully rebuildable by **retroactive ingest of all activations** (backfill).
| column | type | notes |
|---|---|---|
| serial_unit_id | UUID → serial_unit | |
| entity_id | UUID → entity | for consolidated rollup |
| lot_batch_id | UUID → lot_batch NULL | cohort |
| warranty_start | DATE NOT NULL | = activation date |
| warranty_end | DATE NOT NULL | start + variant.warranty_months |
| estimated_provision | NUMERIC(18,4) NOT NULL | est. cost to honour (rate × cost basis) |
| currency | CHAR(3) NOT NULL | |
| released_to_date | NUMERIC(18,4) DEFAULT 0 | recognised release over the term |
| consumed_by_claims | NUMERIC(18,4) DEFAULT 0 | actual claim cost drawn down |
| outstanding | NUMERIC(18,4) | = estimated − released − consumed (exposure) |
| status | TEXT DEFAULT 'open' | `open`/`released`/`expired`/`claimed_out` |

Index(entity_id, warranty_end), Index(status). Consolidated exposure = Σ `outstanding` by entity/period; release schedule is computed (doc 04 §Warranty). Balance-sheet posting is **downstream** (P&L/GL boundary), fed by `warranty.*` events.

### `warranty_claim`
serial_unit_id → serial_unit; `raised_at TIMESTAMPTZ`; `description TEXT`; `cost NUMERIC(18,4)`; `currency CHAR(3)`; `resolution TEXT` (`repair`/`replace`/`reject`); `status TEXT`; `rma_id UUID → rma NULL`. Draws down the unit's `warranty_provision`.

### `legal_warranty` — mandatory statutory term by jurisdiction
Each product is sold with a **mandatory legal warranty** that varies by jurisdiction; the release cycle runs over **activation + legal term + extension**.
`jurisdiction CHAR(2) NOT NULL`; `product_family_id UUID → product_family NULL` (null = all); `statutory_months INTEGER NOT NULL`; `basis TEXT` (consumer-law reference); `effective_from`,`effective_to`. UNIQUE(jurisdiction, product_family_id, effective_from).

### `warranty_extension`
serial_unit_id → serial_unit NULL / order_line_id → order_line NULL; `extra_months INTEGER NOT NULL`; `source TEXT` (`sold`/`goodwill`); `ref TEXT`. Optional additional term on top of the legal warranty.

### `warranty_rate` — provisioning assumption (versioned)
`product_family_id → product_family NULL`; `generation TEXT NULL`; `provision_rate_pct NUMERIC(7,4)` (of unit cost) **or** `provision_per_unit NUMERIC(18,4)`; `effective_from`,`effective_to`. The estimate basis; versioned + audited (a GAAP/SOX-relevant assumption).

---

## H. Purchasing & Supply

### `supplier`
`name TEXT` (Luxshare primary), `billing_currency CHAR(3) NOT NULL` (Luxshare = **USD**; configurable per supplier entity, e.g. a Luxshare-UK entity), `supplier_entity TEXT NULL`, `lead_time_days INTEGER`, `terms JSONB`, `contacts JSONB`.

### `purchase_order`
`po_no TEXT`, entity_id, supplier_id → supplier, `type TEXT` (`external`/`intercompany`), `status TEXT`, `order_date`,`expected_date`, `txn_currency CHAR(3)`, `fx_rate`, `total NUMERIC(18,4)`, `tb_transfer_id NULL`.

### `po_line`
po_id → purchase_order; product_variant_id; `qty INTEGER`,`unit_cost NUMERIC(18,4)`,`qty_received INTEGER`,`expected_date DATE`.

### `goods_receipt` / `goods_receipt_line`
grn: po_id, `date`, actor. line: po_line_id, `qty_received`, `serials TEXT[]`, lot_batch_id → lot_batch.

### `landed_cost_component`
grn_id/po_id; `type TEXT` (`freight`/`duty`/`import_vat`); `amount NUMERIC(18,4)`,`currency CHAR(3)`; `allocation_basis TEXT`.

### `replenishment_suggestion` (derived/materialised)
entity_id, location_id, product_variant_id, `net_requirement INTEGER`, `suggested_qty INTEGER`, supplier_id, `required_by DATE`, `suggested_order_date DATE`.

---

## I. Intercompany

### `transfer_price_policy`
from_entity_id → entity, to_entity_id → entity, `method TEXT`, `markup_pct NUMERIC(7,4)`, `basis TEXT` (`landed_cost`), `product_scope JSONB`, `effective_from`,`effective_to`.

### `intercompany_link`
`sell_order_id UUID → order`, `buy_po_id UUID → purchase_order`, `status TEXT`. Both legs reconcile; tagged for elimination.

---

## J. Commission — first-class, scoped, time-bounded

Commissions are a first-class feature, not a column on the agent. A **scheme** carries a rate, a basis (`gross_margin` is the confirmed default), and a **validity window**, and is **assigned** to a team and/or channel and/or country — à la HubSpot teams (wholesale ≠ retail; per-country variants allowed). Resolution at order time is by specificity + validity (doc 04 §Commission).

### `sales_agent`
user_id → app_user; `name`; `role`; `team_id UUID → team`; `channel_id UUID → channel NULL`; `market_scope UUID[]`; `entity_scope UUID[]`; `targets JSONB`. (No direct plan link — the scheme resolves from team/channel/country/date.)

### `commission_scheme`
| column | type | notes |
|---|---|---|
| name | TEXT NOT NULL | |
| basis | TEXT NOT NULL DEFAULT 'gross_margin' | `gross_margin` (confirmed) / `net_revenue` / `revenue` |
| rate_pct | NUMERIC(7,4) NOT NULL | % of basis (e.g. % of gross margin) |
| tiers | JSONB | optional attainment bands |
| product_modifiers | JSONB | optional per-variant multipliers |
| discount_modifier | JSONB | optional (scales with line discount) |
| exception_treatment | TEXT DEFAULT 'zero' | `full`/`reduced`/`zero` for approved ADLP exceptions |
| **valid_from** | TIMESTAMPTZ NOT NULL | validity window start |
| **valid_to** | TIMESTAMPTZ NULL | window end (NULL = open) |
| status | TEXT DEFAULT 'active' | `draft`/`active`/`retired` |

### `commission_scheme_assignment` — who the scheme applies to
scheme_id → commission_scheme; `team_id UUID → team NULL`; `channel_id UUID → channel NULL`; `market_id UUID → market NULL`; `entity_id UUID → entity NULL`. At least one of team/channel/market set. UNIQUE(scheme_id, team_id, channel_id, market_id, entity_id). (Wholesale-team scheme, retail-team scheme, UK-vs-IE variants, etc.)

### `commission_period` — true-up cadence
`code TEXT` (e.g. `2026-Q2`), `cadence TEXT DEFAULT 'quarterly'` (configurable), `period_start DATE`, `period_end DATE`, `status TEXT` (`open`/`trued_up`/`closed`). The periodic true-up run computes earned commission on actual margins for the period; prior periods are not reopened.

### `commission_entry`
agent_id → sales_agent; `scheme_id → commission_scheme` (resolved scheme, for provenance); `commission_period_id → commission_period`; order_id → order; order_line_id → order_line; `basis_amount NUMERIC(18,4)` (gross margin on the line); `rate_applied NUMERIC(7,4)`; `amount NUMERIC(18,4)`; `currency CHAR(3)`; `kind TEXT` (`accrual`/`true_up_adjustment`); `status TEXT` (`pending`/`posted`/`clawed`); `tb_transfer_id NUMERIC(39,0)`. (Lifecycle = TigerBeetle two-phase; doc 04 §Commission.)

---

## K. Forecasting (H6Q)

H6Q is **continuously captured bottom-up**, not maintained centrally. The people who hold the demand knowledge are distributed across regions and timezones, may never have met, and own different accounts. Each **weekly cycle** every account owner is asked — via mobile / tablet / web — to update demand for **each account they own**, per relevant SKU and scenario. Conduit records **every estimate, by whom, when, for which account** (append-only history), and **auto-rolls it up** into the H6Q hierarchy (account → branch → customer → segment → sub-channel → channel → market). As the catalogue expands, new variants appear in each owner's forecast surface automatically — no config.

### `forecast_scenario`
`type TEXT` (`P20`/`P50`/`P80`), `name TEXT`, `toggle_basis TEXT NULL` (`inc_motability`/`ex_motability`/`ex_octopus`…).

### `forecast_cycle` — the weekly capture window
`code TEXT` (e.g. `2026-W23`), `period_start DATE`, `period_end DATE`, `cadence TEXT DEFAULT 'weekly'`, `status TEXT` (`open`/`closed`). The cadence is configurable; weekly is the default. Cycles are timezone-agnostic — a submission is valid whenever it lands within the window.

### `forecast_submission` — one owner's act of forecasting an account in a cycle
| column | type | notes |
|---|---|---|
| cycle_id | UUID → forecast_cycle | |
| forecaster_user_id | UUID → app_user NOT NULL | the account owner submitting |
| company_id | UUID → company NOT NULL | account (or branch) being forecast |
| status | TEXT NOT NULL | `outstanding`/`submitted`/`skipped` |
| submitted_at | TIMESTAMPTZ NULL | |
| device | TEXT | `mobile`/`tablet`/`web` |
UNIQUE(cycle_id, forecaster_user_id, company_id). Drives "who still owes a forecast this week" — the nudge/coverage-of-forecasters view.

### `forecast_entry` — a single versioned estimate (append-only)
| column | type | notes |
|---|---|---|
| submission_id | UUID → forecast_submission NULL | null for `hyperview`/system |
| cycle_id | UUID → forecast_cycle NULL | |
| forecaster_user_id | UUID → app_user NULL | who estimated |
| channel_id, sub_channel_id NULL, market_id | UUID | |
| company_id NULL, branch_company_id NULL | UUID | account / branch |
| product_variant_id | UUID NULL | per-SKU; null = account total |
| period_month | DATE | the forecast horizon month |
| scenario_id | UUID → forecast_scenario | P20/P50/P80 |
| qty | INTEGER | |
| source | TEXT DEFAULT 'manual' | `manual` (bottom-up rep) / `hyperview` (Prophet retail) |
| superseded_by | UUID → forecast_entry NULL | append-only versioning |

Entries are **immutable**; a revised estimate inserts a new row and sets `superseded_by` on the prior. The *current* estimate is the latest non-superseded row for a key; the **full time-series of every estimate is retained** for accuracy analysis. Index(cycle_id), (company_id, period_month, scenario_id), (forecaster_user_id).

> **Hyperview** (separate Prophet project) lands retail forecasts as `source='hyperview'` rows (no submission). Bottom-up `manual` and `hyperview` coexist; precedence is configurable (default: latest `manual` override else `hyperview`).

### `forecast_accuracy` (projection)
forecaster_user_id, company_id, product_variant_id NULL, `period DATE`, `forecast_qty` (what they said), `actual_qty` (sell-in/sell-through), `error`, `bias`, `mape`. Lets us hold each owner's estimates against reality over time. Rebuilt from `forecast_entry` history + dispatch/activation actuals.

### `pipeline_coverage` (materialised projection)
Dimensioned for drill-down and **dual aggregation (by branch and by sales agent)**: channel_id, sub_channel_id NULL, `segment TEXT NULL`, company_id NULL (enclosing customer/wholesaler), branch_company_id NULL, agent_user_id NULL, market_id, `period DATE`, scenario_id, `forecast_qty`, `weighted_pipeline_qty`, `shipped_qty`, `activated_qty`, `coverage_pct`, `coverage_ex_account_pct`. Rows exist at each rollup level; the board sums up or drills down (channel → sub-channel → segment → customer → branch, and independently by agent). Rebuilt automatically from forecast submissions + deals + orders + activations (doc 04 §H6Q).

### `sell_through` (projection)
company_id, channel_id, `period DATE`, `sell_in_qty` (orders/dispatch), `sell_through_qty` (activations), `last_shipment_date`, `last_activation_date`.

---

## L. Eventing & Audit

### `outbox_event`
| column | type | notes |
|---|---|---|
| event_id | UUID PK | = envelope event_id |
| event_type | TEXT NOT NULL | |
| schema_version | INTEGER NOT NULL | |
| aggregate_type | TEXT NOT NULL | |
| aggregate_id | UUID NOT NULL | |
| partition_key | TEXT NOT NULL | ordering key |
| scope | JSONB | entity/market/channel |
| correlation_id | UUID | |
| causation_id | UUID NULL | |
| payload | JSONB | (Avro on the wire; JSONB at rest) |
| occurred_at | TIMESTAMPTZ NOT NULL | |
| published_at | TIMESTAMPTZ NULL | relay sets |
| status | TEXT DEFAULT 'pending' | `pending`/`published`/`failed` |

Index: `(status, occurred_at)` for the relay; `(aggregate_type, aggregate_id)`.

### `event_schema` (registry mirror)
`event_type`, `version`, `encoding TEXT` (`avro`/`json`), `definition JSONB`, `compatibility TEXT DEFAULT 'backward'`.

### `consumer_checkpoint`
`consumer_group TEXT`, `partition TEXT`, `last_event_id UUID`, `updated_at`. (Replay control.)

### `audit_log` (projection of staff-action events + field-level diffs)
`entity_type TEXT`, `entity_id UUID`, `action TEXT`, `before JSONB`, `after JSONB`, `actor_user_id UUID`, `event_id UUID NULL`, `occurred_at TIMESTAMPTZ`. Append-only; not editable by Admin. Index(entity_type, entity_id, occurred_at DESC).

### `migration_record` (cutover audit)
`source TEXT` (`mrpeasy`/`ghostbusters`/`athena`), `entity_type`, `source_id TEXT`, `conduit_id UUID`, `migrated_at`, `reconciled BOOLEAN`.

---

## M. Extensible properties (custom attributes — governed, not freeform)

The "HubSpot wisdom" done with guardrails: a small **typed core** that never bends, plus a **governed property registry** so descriptive/segmentation/workflow attributes can evolve organically without migrations. Flexibility lives at the edge; financial truth stays typed.

### `property_definition` (the registry)
| column | type | notes |
|---|---|---|
| object_type | TEXT NOT NULL | `party`/`contact`/`deal`/`product_variant`/`order`/… (extensible objects only) |
| key | TEXT NOT NULL | machine name |
| label | TEXT NOT NULL | |
| data_type | TEXT NOT NULL | `string`/`text`/`number`/`integer`/`boolean`/`date`/`datetime`/`enum`/`multi_enum`/`reference` |
| options | JSONB NULL | enum values |
| group | TEXT | property group (UI grouping, à la HubSpot) |
| required | BOOLEAN DEFAULT false | |
| validation | JSONB NULL | regex/min/max/etc. |
| data_layer | TEXT → data_layer NULL | access tag — custom props still respect projection (doc 05) |
| applies_to_subtypes | TEXT[] NULL | e.g. a property only for `party_type='installer'` |
| status | TEXT DEFAULT 'active' | `active`/`deprecated` |
| version | INTEGER | |
| created_by | UUID | governed + audited |
UNIQUE(object_type, key). Adding a property = a registry row (+ optional UI) — **no schema migration**.

### `attributes` convention
Every **extensible** object carries `attributes JSONB NOT NULL DEFAULT '{}'`. On write, values are **validated against `property_definition`** (type, options, required, validation) — it is a *typed, governed* bag, not freeform. GIN-indexed; hot keys get expression indexes (`(attributes->>'industry')`) so custom props stay queryable/reportable. Extensible objects: `party`, `contact`, `deal`, `product_variant`, `order` (header), `activity`. **Not** extensible: anything posting to the ledger or driving allocation/pricing/tax (order *lines’* money, `lot_batch`, `commission_entry`, `price_rule`, ledger).

### Guardrails (what must NOT go in `attributes`)
Money, tax, quantities, prices, costs, currency, credit, status/state-machine values, IDs, or anything that drives the ledger, allocation, pricing, commission or tax determination — those are **typed columns**. `attributes` is for descriptive/segmentation/workflow data (industry, lead source, regional flags, custom enums, campaign tags).

### Graduation path
When a custom property becomes load-bearing (drives money/logic) or high-volume in reporting, **promote it to a typed column** (migration + backfill from `attributes`) and mark the definition `deprecated`. The flexible layer never silently becomes the financial schema.

### On the wire (JSONB vs Avro — by layer)
The Avro event spine stays **stable and backward-compatible** (the rigour is deliberate). Custom attributes travel inside events as an Avro `map<string,string>` / JSON-encoded `attributes` field — **not** as Avro-schema fields — so adding a property is a registry change only, never an Avro schema bump or coordinated deploy. Avro governs the spine that must not drift; JSONB carries the edge that should evolve.

---

## N. Controls & reconciliation (SOX/PCAOB; full spec doc 14)

### `control` (ICFR control register)
`code TEXT UNIQUE`, `name`, `objective TEXT`, `assertion TEXT[]` (`existence`/`completeness`/`valuation`/`cutoff`/`rights_obligations`/`presentation`), `type TEXT` (`preventive`/`detective`), `frequency TEXT` (`continuous`/`daily`/`monthly`/`quarterly`), `automated BOOLEAN`, `owner_user_id UUID`, `evidence_query TEXT` (how the system re-performs/evidences it), `status TEXT`. Each control names the assertion it covers and how it is evidenced (doc 14 §4).

### `control_run` (operating-effectiveness evidence)
`control_id → control`, `run_at TIMESTAMPTZ`, `result TEXT` (`pass`/`exception`), `detail JSONB`, `period_id UUID → accounting_period NULL`. Append-only; the "re-perform now" action (doc 14 §6) writes here.

### `reconciliation` (automated ties; doc 14 §5.2)
`type TEXT` (`tb_vs_gl`/`gl_vs_xero`/`inventory_vs_count`/`ar_vs_invoices`), `period_id → accounting_period`, `scope JSONB`, `expected NUMERIC(18,4)`, `actual NUMERIC(18,4)`, `currency CHAR(3)`, `variance NUMERIC(18,4)`, `status TEXT` (`open`/`matched`/`exception`), `signed_off_by UUID NULL`, `signed_off_at TIMESTAMPTZ NULL`. Exceptions must clear before a period locks.

### `period_close_task` (close checklist)
`period_id → accounting_period`, `name TEXT`, `sequence INT`, `status TEXT` (`pending`/`done`/`blocked`), `done_by UUID NULL`, `evidence_ref JSONB NULL`. The close board (doc 14 §6) renders these with sign-offs.
