# 17 — Document Generation

Build-grade deep-dive for the **document-generation** subsystem — the production of the **legally-required commercial artefacts** Conduit must emit: **invoices, credit notes, proformas, packing lists, commercial invoices for customs, and statements**. Same template as 02–05/09/13: field-level schemas, outbox events, pseudocode, REST contracts, permission/data-layer mappings, Acceptance block. This document **references and extends** the spine; it does **not** redefine tables already in doc 02. Tables it builds on: `order_invoice`, `order`/`order_line`, `delivery_tranche`, `dispatch`/`dispatch_line`, `billing_profile`, `party`, `entity`/`tax_registration`/`tax_regime`, `locale`/`currency`/`market`, `product_variant` (`hs_code`)/`product_translation`, `address`, `rma`/`return`, `intercompany_link`/`tp_document`, `accounting_period` (doc 02 §A/§C/§D/§F/§G/§I). It builds on the algorithms in doc 04 (§Orders/§Ledger — ASC 606 invoice-on-delivery), the events in doc 03 (`dispatch.delivered`, `order.invoiced`, `return.*`, `intercompany.movement.posted`), the access wall in doc 05 (`commercial`/`pii`/`inter_entity` layers), the typed-money + locale-formatting + retention/WORM discipline in doc 14 (§1 typed money, §2 period model, §5.3 evidence & retention), and the i18n workstream + this row in doc 10 §B ("Document generation").

Conventions per doc 00: every table has `id UUID PK DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`, optional `deleted_at TIMESTAMPTZ` (transactional/legal records are **never** hard-deleted). Money = `NUMERIC(18,4)` + `CHAR(3)` currency. Scoping columns `entity_id`/`market_id`/`channel_id` per doc 00. Common columns omitted below. `→ X` = FK to `X.id`.

Design stance (consistent with the pack): **a document is a rendered projection of typed financial truth, not a source of it** — every number on a document traces back to its `order_invoice`/`order_line`/ledger transfer (doc 14 §5.1 lineage); **a finalised legal document is immutable** (WORM); **corrections are new documents** (a credit note reverses an invoice — you never re-render a finalised invoice); **numbering is gapless and audit-grade** (no reuse, even on void); **money and dates render per locale and per jurisdiction** with no float ever touching a figure (doc 14 §1); and **the trigger model honours ASC 606** — the invoice is issued by `dispatch.delivered`, never before control transfers (doc 04 §Ledger/§Orders). The PDF engine is an **abstraction, not a vendor** (§5).

---

## 1. Scope, document types & ownership

### 1.1 The document taxonomy

| `document_type` | Legal nature | Trigger (source of truth) | Numbering series | Money? | Immutable once finalised |
|---|---|---|---|---|---|
| `invoice` | Statutory tax invoice (AR; the demand for payment) | **`order.invoiced`** (auto from `dispatch.delivered`, per tranche, ASC 606) | per `(entity, type=invoice)` | yes | **yes** |
| `credit_note` | Statutory reversal/correction of an invoice (AR contra) | `return.refunded` / a finalised correction against an `order_invoice` | per `(entity, type=credit_note)` | yes | **yes** |
| `proforma` | **Non-fiscal** quotation/advance-request (no VAT point, no AR) | order `placed` / a quote (doc 06 `/pricing/quote`) — **before** delivery | per `(entity, type=proforma)` (or non-gapless draft series, §3.4) | yes | no (regenerable — it is not a legal record) |
| `packing_list` | Logistics manifest (no money) — what physically ships | **`dispatch.created`** | per `(entity, type=packing_list)` | **no** (qty/serials only) | finalised on dispatch |
| `commercial_invoice` | **Customs** document for cross-border movement (declared value, HS codes, incoterm, origin) | a cross-border `dispatch.created` **or** a cross-border `intercompany.movement.posted` (doc 13 §6) | per `(entity, type=commercial_invoice)` | yes (customs value) | finalised on dispatch/movement |
| `statement` | Periodic account statement (open items / aged balance for a payer) | scheduled (period close, or on demand) per `bill_to` payer | per `(entity, type=statement)` | yes | snapshot (re-issuable for a new as-of) |

- **`invoice` and `credit_note` are the legal AR pair.** Conduit **enforces ASC 606**: the invoice cannot precede delivery (doc 04 §Ledger), so the `invoice` document is produced from `order.invoiced` (which `dispatch.delivered` auto-triggers) — invoice date = delivery date = revenue-recognition date. A **finalised invoice is never re-rendered or edited**; any correction is a **`credit_note`** (full or partial), itself a finalised, numbered legal document.
- **`proforma` is explicitly *not* a tax document** — no VAT point, no AR posting, no gapless legal series requirement; it is a quotation/advance-payment request issued *before* delivery, and may be regenerated/superseded freely. (This is the one type that is mutable; everything fiscal is WORM.)
- **`packing_list` carries no money** — quantities and serials only (doc 02 §G `serial_unit`), so it is `volume`-layer-only and never leaks price.
- **`commercial_invoice`** is the **customs** artefact: declared/customs value, HS code per line (`product_variant.hs_code`, doc 02 §D), country of origin, incoterm, ship-from/ship-to. For an **intercompany** cross-border movement its value basis is the **transfer price** and it links to `intercompany_link`/`tp_document` (doc 13 §3/§6); for an external customer cross-border sale its basis is the invoice value. It does **not determine** duty/VAT — that is the tax/customs engine boundary (doc 13 §6, doc 10 §B); this subsystem **renders** the declaration.
- **`statement`** is a periodic projection of open AR items (`order_invoice` − applied `credit_note`/payments) per **payer** (`order.bill_to_party_id`; a CEF master receives one consolidated statement across its branches — doc 02 §C/§F).

### 1.2 What this doc owns vs. what it calls

This subsystem owns: the **template registry**, the **numbering schemes**, the **rendering pipeline** (data assembly → locale/jurisdiction formatting → PDF), **document storage + retention (WORM)**, **regeneration/versioning rules**, and the **event/trigger model** that turns business events into documents. It **does not** own: tax *determination* (calls the tax engine — doc 13 §6 `TaxQuote`), revenue recognition / ledger posting (doc 04 §Ledger), or the email/notification *channel* (it emits `document.issued`; the notifications consumer — doc 10 §B — delivers it). The numbers it renders are **read** from `order_invoice`/`order_line`/`rma`/`intercompany_link`, never recomputed (the document is a projection, doc 14 §5.1).

---

## 2. Template registry — keyed by (document_type, locale, jurisdiction)

Templates are **data**, versioned and governed (doc 02 §M change-management discipline / doc 14 §4). A template is resolved per `(document_type, locale, jurisdiction)` with a **fallback chain** (§2.3) so that legal content varies by jurisdiction and language varies by locale, without code.

### 2.1 `document_template`

| column | type | constraints | notes |
|---|---|---|---|
| document_type | TEXT | NOT NULL | `invoice`/`credit_note`/`proforma`/`packing_list`/`commercial_invoice`/`statement` |
| jurisdiction | CHAR(2) | NULL | ISO country; **NULL = jurisdiction-agnostic fallback** |
| locale | TEXT | → locale.code NULL | BCP-47 (doc 02 §A); **NULL = locale-agnostic fallback** |
| entity_id | UUID | → entity NULL | optional per-entity override (NULL = all entities in the jurisdiction) |
| body | TEXT | NOT NULL | template source (Typst/Handlebars-style markup — §5.1); references typed render-model fields only |
| legal_clauses | JSONB | NOT NULL DEFAULT '{}' | jurisdiction-mandated boilerplate (§2.2) |
| required_fields | TEXT[] | NOT NULL DEFAULT '{}' | render-model fields the jurisdiction **mandates** be present (validated at finalise, §2.4) |
| paper_size | TEXT | NOT NULL DEFAULT 'A4' | `A4`/`Letter` (US/CA → `Letter`) |
| font_stack | TEXT[] | NOT NULL | covers the locale's script — incl. **CJK + Thai** (§5.3) |
| logo_asset_ref | TEXT | NULL | entity-branded asset |
| status | TEXT | NOT NULL DEFAULT 'draft' | `draft`/`active`/`superseded` |
| version | INTEGER | NOT NULL DEFAULT 1 | versioned + audited |
| effective_from | TIMESTAMPTZ | NOT NULL | |
| effective_to | TIMESTAMPTZ | NULL | |
| owner_user_id | UUID | → app_user | proposer (maker) |
| approved_by | UUID | → app_user NULL | checker (maker ≠ checker, doc 05 §4) |

UNIQUE(document_type, jurisdiction, locale, entity_id, version). Resolution index `(document_type, jurisdiction, locale, status, effective_from DESC)`. A template change is a **governed, maker-checker, audited** action (doc 05 §4 / doc 14 §4) — proposer ≠ approver — and emits `document_template.changed` (§6). The active template version a document used is **recorded on the document** (`document.template_id` + `template_version`, §4.1) so the rendered artefact is reproducible.

### 2.2 Jurisdiction-mandated legal content (`legal_clauses`)

`legal_clauses` carries the per-jurisdiction statutory content that a tax invoice/credit note **must** contain — driven by the destination/supply `tax_regime` (doc 02 §A) and jurisdiction. Examples (data, not code; maintained per market as it opens, doc 02 §A "year-1 = UK only"):

- **GB/IE/EU**: full VAT-invoice particulars — supplier VAT number (`tax_registration.number`, doc 02 §A), customer VAT number where B2B, the **`tax_regime.kind`** treatment line (`standard`/`zero`/`reverse_charge`), and the **reverse-charge notice** ("VAT to be accounted for by the recipient" / "Reverse charge") when `tax_regime.kind='reverse_charge'`.
- **EU intra-community / export (`International`)**: zero-rating / export wording and EC-sales-list references; place-of-supply statement.
- **DE/FR/IT/etc.**: localised mandatory-particulars wording + sequential-numbering attestation (the gapless series, §3).
- **JP / TH / APAC**: consumption-tax / VAT wording, qualified-invoice particulars where applicable; rendered in `ja`/`th` script.
- **US/CA**: no VAT invoice; sales-tax / GST-HST-PST line presentation comes from the tax engine result (doc 10 §B) — the template renders what the engine determined, it does not assert a rate.

The template **references** the `tax_regime` (doc 02 §A) resolved on the underlying `order_line`/`order_invoice` (doc 02 §F) and the entity's `tax_registration` — it never hard-codes a rate or a number; rate change = `tax_regime` data, registration change = `tax_registration` data.

### 2.3 Resolution & fallback chain

```
resolveTemplate(documentType, locale, jurisdiction, entity, asOf):
  // most specific first; each step requires status='active' and asOf within effective window
  candidates = document_template
     WHERE document_type = documentType AND status='active'
       AND effective_from <= asOf AND (effective_to IS NULL OR effective_to > asOf)
  // specificity score over the resolution axes (entity > jurisdiction > locale fallbacks):
  for (loc, jur, ent) in [
        (locale,            jurisdiction, entity),     // exact
        (locale,            jurisdiction, null),       // any entity in jurisdiction
        (marketDefaultLocale(jurisdiction), jurisdiction, null),  // jurisdiction default language (doc 02 §A market.default_locale)
        (locale,            null,         null),        // locale, jurisdiction-agnostic
        ('en',              jurisdiction, null),        // English in jurisdiction
        (null,              null,         null) ]:      // global fallback
     hit = candidates.find(t => matches(t, loc, jur, ent), highest version)
     if hit: return hit
  raise TemplateNotFound(documentType, locale, jurisdiction)   // a finalise must never proceed without a template
```

The **locale** chosen is the payer's `billing_profile.invoice_locale` (doc 02 §C) → party `preferred_locale` → `market.default_locale` (doc 02 §A) → `en` — the same fallback chain as `product_translation` (doc 02 §D). The **jurisdiction** is the supply/destination jurisdiction resolved on the invoice (the entity's `tax_registration.jurisdiction` for the supply, the customs destination for a `commercial_invoice`).

### 2.4 Required-field validation (finalise gate)

A document **cannot be finalised** if any `template.required_fields` resolves empty — e.g. a GB invoice requires a supplier VAT number; an EU B2B reverse-charge invoice requires the **customer** VAT number (`billing_profile.tax_registration_number`, doc 02 §C, "required where the jurisdiction mandates"); a `commercial_invoice` requires `hs_code` (doc 02 §D), country of origin and incoterm. Missing → `422 DocumentValidationFailed` (§8), surfacing the missing fields. This mirrors the `billing_profile` promote-to-billable policy (doc 02 §C) at document time.

---

## 3. Numbering schemes — immutable, gapless, audit-grade

Statutory tax documents in most jurisdictions must carry a **sequential, gapless** identifier per issuing entity and type, with **no reuse** — including when a document is **voided** (a void consumes its number; the gap is forbidden, the void is recorded). This is a SOX **completeness** assertion (doc 14 §4 — "gapless event sequence numbers… a missing sequence is a detective alarm") applied to legal numbering.

### 3.1 `document_number_series`

The allocator — one row per `(entity, document_type[, jurisdiction])`. The **last allocated value** advances under a row lock; allocation is atomic with the document insert (same transaction) so the spine is gapless by construction.

| column | type | constraints | notes |
|---|---|---|---|
| entity_id | UUID | → entity NOT NULL | issuing legal entity |
| document_type | TEXT | NOT NULL | `invoice`/`credit_note`/… |
| jurisdiction | CHAR(2) | NULL | where a jurisdiction mandates a *separate* series (else NULL) |
| series_code | TEXT | NOT NULL | the human prefix, e.g. `HV-UK-INV` |
| format | TEXT | NOT NULL | pattern, e.g. `{series}-{yyyy}-{seq:06d}` (data, like the batch-no scheme, doc 07 Decisions) |
| period_scope | TEXT | NOT NULL DEFAULT 'continuous' | `continuous` / `annual` (reset seq each fiscal year where the jurisdiction allows) |
| current_seq | BIGINT | NOT NULL DEFAULT 0 | last allocated; advances under `FOR UPDATE` |
| seq_resets_at | DATE | NULL | for `annual` scope |
| status | TEXT | NOT NULL DEFAULT 'active' | |

UNIQUE(entity_id, document_type, jurisdiction, series_code). The `format` literal is **data** (a different legal scheme is config, not code — exactly the batch-no pattern, doc 07 Decisions).

### 3.2 `document_number` (the allocation ledger — append-only)

Every allocated number is recorded **before** (or atomically with) the document it identifies, so the series is auditable end-to-end including voids. Append-only; never deleted.

| column | type | constraints | notes |
|---|---|---|---|
| series_id | UUID | → document_number_series NOT NULL | |
| seq | BIGINT | NOT NULL | the allocated ordinal |
| formatted_number | TEXT | NOT NULL | rendered via `series.format` (the legal number) |
| document_id | UUID | → document NULL | the document it identifies (set on insert) |
| status | TEXT | NOT NULL | `allocated`/`issued`/`voided` |
| voided_reason | TEXT | NULL | required when `voided` (the gap is forbidden; the void is *recorded*, the number is **not** reused) |
| allocated_at | TIMESTAMPTZ | NOT NULL | |

UNIQUE(series_id, seq). UNIQUE(series_id, formatted_number). Index(document_id). **Invariant (completeness control, §10):** for any series, `seq` values are contiguous `1..current_seq` with **no holes** — a missing `seq` is a detective alarm (doc 14 §4); a `voided` row keeps its `seq` (the number is consumed, never recycled).

### 3.3 Allocation

```
allocateNumber(series, documentType, entity, jurisdiction, asOf):     // runs IN the finalise transaction
  row = SELECT * FROM document_number_series
         WHERE entity_id=entity AND document_type=documentType
           AND (jurisdiction = :j OR jurisdiction IS NULL)
         ORDER BY (jurisdiction IS NOT NULL) DESC LIMIT 1
         FOR UPDATE                               // serialise concurrent allocators → no duplicate, no gap
  if row.period_scope=='annual' and fiscalYear(asOf) > fiscalYear(row.seq_resets_at):
     row.current_seq = 0 ; row.seq_resets_at = startOfFiscalYear(asOf)
  seq = row.current_seq + 1
  number = format(row.format, series=row.series_code, seq=seq, year=fiscalYear(asOf))
  UPDATE document_number_series SET current_seq=seq WHERE id=row.id
  insert document_number(series_id=row.id, seq=seq, formatted_number=number, status='allocated')
  return (row.id, seq, number)
```

The `FOR UPDATE` lock serialises concurrent finalisers (same discipline as ATP allocation, doc 04 §ATP) so two invoices can never share a number and the sequence never skips. Allocation is in the **same DB transaction** as the document finalise + outbox row (doc 03 §outbox) — all-or-none.

### 3.4 Voids, drafts & proformas

- **A finalised fiscal document is never deleted.** If it must be cancelled, its `document_number.status` → `voided` (reason required), the document `status` → `void`, and **the correction is a new document** (a `credit_note` for an invoice). The voided number is consumed, recorded, never reused — gaps stay forbidden.
- **`proforma`** is non-fiscal: it may use a **separate, non-gapless** draft series (or no `document_number` row at all) and is freely regenerable/superseded — it never enters the legal sequence.
- **Failure during render** does not consume a number: numbers are allocated at **finalise** (after the render model validates, §4.2), so a render that fails before finalise allocates nothing.

---

## 4. The document & its rendering pipeline

### 4.1 `document` (the WORM record)

The immutable record of a generated artefact — its identity, its source linkage, the template/locale/jurisdiction it used, and the pointer to the stored PDF (§4.4). Once `status='finalised'` the row's content-bearing columns are **immutable** (enforced by the storage rule, §4.4, and a no-update guard).

| column | type | constraints | notes |
|---|---|---|---|
| document_type | TEXT | NOT NULL | the taxonomy (§1.1) |
| entity_id | UUID | → entity NOT NULL | issuing entity |
| document_number_id | UUID | → document_number NULL | the gapless number (NULL for non-fiscal proforma) |
| formatted_number | TEXT | NULL | denormalised legal number (the human ref) |
| **order_invoice_id** | UUID | → order_invoice NULL | originating tax invoice (invoice/credit_note/statement line) |
| **order_id** | UUID | → order NULL | originating order |
| **tranche_id** | UUID | → delivery_tranche NULL | the per-drop invoice (doc 02 §F — per-tranche ASC 606) |
| **dispatch_id** | UUID | → dispatch NULL | packing_list / commercial_invoice source |
| **rma_id** | UUID | → rma NULL | credit_note from a return (doc 09) |
| **intercompany_link_id** | UUID | → intercompany_link NULL | commercial_invoice for an IC movement (doc 13) |
| bill_to_party_id | UUID | → party NULL | the payer (statements; AR) |
| locale | TEXT | → locale.code NOT NULL | the resolved render locale |
| jurisdiction | CHAR(2) | NOT NULL | the resolved supply/destination jurisdiction |
| template_id | UUID | → document_template NOT NULL | template used |
| template_version | INTEGER | NOT NULL | version used (reproducibility) |
| currency | CHAR(3) | NULL | document currency (NULL for packing_list) |
| total_amount | NUMERIC(18,4) | NULL | document total inc-tax (NULL for packing_list) |
| render_model | JSONB | NOT NULL | the **typed, frozen** data the PDF was rendered from (§4.2) — the re-render input |
| corrects_document_id | UUID | → document NULL | a credit_note points at the invoice it corrects |
| superseded_by_document_id | UUID | → document NULL | proforma supersession only (fiscal docs never superseded) |
| status | TEXT | NOT NULL | `draft`/`rendering`/`finalised`/`void` |
| storage_uri | TEXT | NULL | object-store key of the immutable PDF (§4.4) |
| content_sha256 | TEXT | NULL | hash of the finalised PDF (tamper-evidence / WORM proof) |
| issued_at | TIMESTAMPTZ | NULL | finalise timestamp (UTC instant; period via projection, doc 14 §2) |
| retention_class | TEXT | NOT NULL DEFAULT 'fiscal_10y' | retention policy key (§4.5) |
| accounting_period_key | TEXT | NULL | resolved at finalise (period-projection, doc 14 §2) |

Indexes: `(order_id)`, `(order_invoice_id)`, `(bill_to_party_id, document_type, issued_at DESC)`, `(document_type, entity_id, issued_at DESC)`, `(formatted_number)`, `(corrects_document_id)`. **Layer note:** `total_amount`/`currency`/`render_model` (money) → `commercial` layer; `render_model` PII (addresses/contacts) → `pii`; a `commercial_invoice` linked to `intercompany_link` carries `inter_entity`-layer transfer-price content (doc 05 §3, §9 below). A `packing_list` carries **no** money — `volume` layer only.

### 4.2 The render model (typed, frozen)

The pipeline first builds a **typed render model** — every money figure is a `Money` (doc 14 §1), every quantity a typed count, every date a UTC instant — by **reading** the originating records (never recomputing the totals). It is frozen into `document.render_model` (JSONB) at finalise so the PDF re-renders byte-stably from the same inputs (doc 14 §5.1 re-performability).

```
buildRenderModel(documentType, source):           // source = order_invoice | dispatch | rma | intercompany_link | statement-spec
  e   = entity(source.entity_id)
  tr  = tax_registration(e, jurisdiction, asOf=source.issued_at)        // doc 02 §A
  payer = billing_profile.resolve(source.bill_to_party_id)              // doc 02 §C (self or via bills_to_party_id)
  loc = payer.invoice_locale ?? party.preferred_locale ?? market.default_locale(jurisdiction) ?? 'en'   // §2.3 chain
  lines = source.lines.map(l =>
     LineVM(
       description = product_translation(l.variant, loc).display_name      // doc 02 §D localized name, same fallback
                       ?? variant.name,
       qty         = l.qty,                                                 // typed quantity (Squants-backed where energy)
       unit_price  = Money(l.unit_price_ex_vat, source.currency),          // READ, not recomputed (doc 14 §1)
       tax_regime  = l.tax_regime,                                          // doc 02 §A — drives §2.2 legal wording
       tax_rate    = tax_regime[l.tax_regime].rate_percent,
       vat_amount  = Money(l.vat_amount, source.currency),
       line_total  = Money(l.line_total_inc_vat, source.currency),
       hs_code     = (documentType=='commercial_invoice') ? variant.hs_code : null,   // doc 02 §D
       country_of_origin = (documentType=='commercial_invoice') ? lot_origin(l) : null))
  totals = TotalsVM(                                                       // READ off order_invoice (doc 02 §F) — the typed truth
     subtotal_ex_vat = Money(source.total_ex_vat, source.currency),
     vat_total       = Money(source.vat_total,    source.currency),
     total_inc_vat   = Money(source.total_inc_vat,source.currency))
  assert totals == foldLines(lines)                                        // conservation check (doc 14 §1.3) — Σ parts == whole
  legal = renderClauses(template.legal_clauses, tax_regime=lines.taxRegimes, reverse_charge = anyReverseCharge(lines))
  return RenderModel(entity=e, supplier_tax_no=tr.number, payer=payer, locale=loc, jurisdiction=jurisdiction,
                     lines=lines, totals=totals, legal=legal, dates=DatesVM(source))
```

- **No recomputation.** Totals are **read** from `order_invoice` (doc 02 §F), which the ledger already booked (doc 04 §Ledger). The pipeline only **renders** them — and asserts the **conservation** invariant (`Σ line totals == document total`, doc 14 §1.3) as a guard before finalising. A divergence is a defect, not silently re-summed.
- **Localized line descriptions** use `product_translation` (doc 02 §D) under the same locale fallback chain.

### 4.3 Locale formatting (numbers, dates, currency — doc 14 + i18n, doc 10 §B)

All presentation formatting is a **single locale-aware formatter** keyed by the resolved locale, applied at the very last step (render), never to the stored typed values (doc 14 §1 — "presentation rounds to `minorUnits`… the rounding is recorded"):

```
format(money: Money, locale):           // e.g. de-DE 1.234,50 € ; en-GB £1,234.50 ; ja-JP ￥1,235 (0 minor units)
  rounded = round(money.amount, money.currency.minorUnits, RoundingPolicy.presentation(jurisdiction))   // doc 14 §1.1/§1.2
  return ICU.currencyFormat(rounded, money.currency, locale)              // CLDR/ICU — grouping, decimal, symbol position
format(date, locale):  return ICU.dateFormat(date AT TIME ZONE entity.reporting_tz, locale)   // doc 14 §2 — instant → entity TZ → locale
```

- **JPY has 0 minor units** (doc 14 §1.1 / doc 02 §A `currency.minor_units`) — the formatter rounds to whole yen at presentation; the stored figure stays `NUMERIC(18,4)`.
- Decimal/grouping separators, currency-symbol position, and date order all come from **CLDR/ICU** per locale (`1.234,50 €` for `de-DE`, `£1,234.50` for `en-GB`, etc.) — no hand-rolled formatting.
- The presentation rounding **mode + boundary is recorded** (doc 14 §1.2) — the document's figures re-derive from the typed truth exactly.

### 4.4 PDF generation — an abstraction, not a vendor

The renderer is behind a **`DocumentRenderer` port** (cats-effect `F[_]`), so the PDF engine is **swappable and not locked to a vendor** (the same stance as the swappable accounting consumer, doc 07 M13):

```
trait DocumentRenderer[F[_]]:
  def render(template: DocumentTemplate, model: RenderModel): F[RenderedPdf]   // RenderedPdf = (bytes, content_sha256, page_count)
```

- **Default implementation: a deterministic, server-side, HTML/markup-to-PDF engine** (e.g. a Typst or headless-Chromium/`weasyprint`-class renderer running in-cluster — no external SaaS, no PII leaving the perimeter, doc 14 §5.3). The choice is an implementation detail behind the port; **the spec mandates the abstraction and the properties, not the product.**
- **Required properties of any implementation:** (a) **deterministic** — same `(template_version, render_model)` → byte-identical bytes (re-performability, doc 14 §5.1); (b) **full Unicode incl. CJK + Thai** — the engine must embed the template's `font_stack` covering the script of all 15 languages (doc 02 §A — Japanese/Thai/Latin/etc.); (c) **PDF/A** output for fiscal documents (archival/legal); (d) **no float** in any figure it receives (figures arrive pre-formatted as strings from the locale formatter, §4.3 — doc 14 §1).
- The renderer runs **idempotently** keyed off `document_id`; a re-render of a finalised document must reproduce `content_sha256` (or it is a defect).

### 4.5 Storage & retention (WORM)

- **Storage:** the finalised PDF is written to an **object store under a WORM/object-lock policy** (immutable for the retention term — doc 14 §5.3 "WORM-style export… immutable, time-stamped, indefinitely retained"); `document.storage_uri` + `content_sha256` pin it. The DB row is **never** updated after `finalised` (a no-update guard on content columns); a void sets `status='void'` and writes a *separate* `credit_note` document — it does not mutate the original.
- **Linkage (lineage, doc 14 §5.1):** every document FKs its originating `order`/`order_invoice`/`tranche`/`dispatch`/`rma`/`intercompany_link`, so the Auditability Center lineage explorer (doc 14 §6) clicks **figure → `order_invoice` → ledger transfer → events → this PDF**, and back.
- **Retention class** (`document.retention_class`) keys the policy (e.g. `fiscal_10y` for tax documents — most EU jurisdictions mandate 6–10 years; `logistics_2y` for packing lists). **GDPR/DSAR tension** is resolved per doc 14 §5.3 / doc 01 §3a: PII on a fiscal document is retained for the statutory term (the financial skeleton survives erasure); the **crypto-erase** strategy applies to the PII fields, not the legally-required document — a DSAR cannot delete a statutory invoice within its retention window.

---

## 5. Generation flow (assemble → format → render → finalise → store)

```
generateDocument(documentType, source, asOf, actor):           // ONE DB transaction + one outbox row
  template = resolveTemplate(documentType, locale(source), jurisdiction(source), source.entity_id, asOf)   // §2.3
  model    = buildRenderModel(documentType, source)             // §4.2 — typed, READ off truth, conservation-checked
  validateRequiredFields(model, template.required_fields)       // §2.4 — 422 if a mandated field is empty
  doc = document(type=documentType, entity=source.entity_id, locale=model.locale, jurisdiction=model.jurisdiction,
                 template_id=template.id, template_version=template.version, render_model=freeze(model),
                 ...source FKs..., status='rendering')
  // fiscal types: allocate the gapless number INSIDE this transaction (proforma/draft: skip or use draft series)
  if isFiscal(documentType):
     (seriesId, seq, number) = allocateNumber(series(documentType, source.entity_id, model.jurisdiction), ...)  // §3.3
     doc.document_number_id = numberRowId ; doc.formatted_number = number
  pdf = renderer.render(template, model)                        // §4.4 — deterministic; embeds CJK/Thai fonts
  storage_uri = objectStore.putImmutable(pdf.bytes, worm=true)  // §4.5 — WORM object-lock
  doc.storage_uri = storage_uri ; doc.content_sha256 = pdf.content_sha256
  doc.status = 'finalised' ; doc.issued_at = asOf
  doc.accounting_period_key = periodKey(asOf, entity.reporting_tz)        // doc 14 §2
  mark document_number.status='issued'
  persist(doc) + outbox(document.issued)                        // all-or-none (doc 03 §outbox)
  return doc
```

- The whole flow is **one transaction + one outbox row** (doc 03) — the document, its number allocation, and the `document.issued` event commit atomically; a failed render rolls back the number (so the series stays gapless, §3.4).
- **Idempotency:** `document.issued` and the renderer are idempotent on `document_id` (which is deterministic from the triggering `event_id` + type, doc 03 §3) — a redelivered `order.invoiced` does **not** mint a second invoice or a second number.

### 5.1 Template markup

`document_template.body` is a logic-light markup (Typst or a Handlebars/Mustache-class templating layer over the render model) — it can iterate `model.lines`, place `model.totals`, and emit `model.legal` clauses, but **may only reference render-model fields** (it cannot reach into the DB or compute money). This keeps the legal/visual layer editable by non-engineers (governed via §2.1 maker-checker) while the financial truth stays in typed code (doc 02 §M typed-core / governed-edge stance).

---

## 6. Events (extends doc 03)

Topic `conduit.documents` (new aggregate type `document`); envelope per doc 03 §1; `BACKWARD` compatible; idempotent on `event_id`; partition by `document_id` (or the originating aggregate for ordering). Documents are produced **by consumers** of the spine events, and emit their own.

### Consumed (triggers — no new producers needed)
- **`order.invoiced`** (doc 03 §Orders — auto-triggered by `dispatch.delivered`, per tranche, ASC 606) → generate `invoice`. **This is the canonical invoice trigger** — never before delivery (doc 04 §Ledger).
- **`dispatch.created`** (doc 03 §Orders) → generate `packing_list`; **if cross-border** → also `commercial_invoice` (customs).
- **`return.refunded`** (doc 03 §Orders / doc 09) → generate `credit_note` against the original `order_invoice`.
- **`intercompany.movement.posted`** with `is_cross_border=true` (doc 03/§13 §5) → generate `commercial_invoice` for the IC movement (value basis = transfer price, links `tp_document`).
- **scheduled** (period close / on-demand) → generate `statement` per payer.

### Produced (new — registered in doc 03)
- **`document.issued`** · key `document_id` · `{ document_id, document_type, formatted_number, entity_id, order_id?, order_invoice_id?, tranche_id?, dispatch_id?, rma_id?, intercompany_link_id?, bill_to_party_id?, locale, jurisdiction, currency?, total_amount?, storage_uri, content_sha256, template_id, template_version, issued_at }` → **notifications consumer** (emails the PDF to the payer — doc 10 §B; resolves `order_invoice.email_state`, doc 02 §F), **Xero** consumer (attach to the AR invoice it already books — doc 04 §Ledger / doc 07 M13), audit, Auditability Center lineage (doc 14 §6). **Layer note (doc 05 §3):** the external/notification projection is layer-filtered — a `packing_list` event carries no money; an IC `commercial_invoice` event is `inter_entity`-stripped for principals lacking the layer.
- **`document.voided`** · key `document_id` · `{ document_id, document_type, formatted_number, reason, voided_by, corrected_by_document_id? }` → audit, AR, Auditability Center. (The number is recorded `voided`, never reused — §3.4.)
- **`document_template.changed`** · key `template_id` · `{ document_type, jurisdiction, locale, entity_id?, version, before, after, owner_user_id, approved_by }` → template read-model refresh, audit (maker-checker, governed change — doc 05 §4 / doc 14 §4).

---

## 7. Regeneration & versioning rules

The rule is a single sentence with sharp edges: **a finalised legal document is immutable; you never re-render it — you reverse it with a new numbered document.**

| Situation | Rule |
|---|---|
| Finalised **invoice** is wrong (price, party, qty) | Issue a **`credit_note`** (full or partial) referencing it (`corrects_document_id`), then issue a corrected new `invoice`. The original invoice is **never** edited or re-rendered. (Corrections-are-credit-notes — doc 09 mirror.) |
| Re-send / re-download a finalised document | Serve the **stored PDF** (`storage_uri`) byte-for-byte; if regenerated, the deterministic renderer reproduces `content_sha256` exactly (§4.4) — same number, same bytes. |
| Template changes after issue | Existing documents keep their **`template_version`** and re-render from it (reproducibility, §4.1); only **new** documents use the new active template. |
| **Proforma** changes (still pre-delivery, non-fiscal) | Freely **regenerate / supersede** (`superseded_by_document_id`); it carries no legal number and no AR posting. |
| Localisation / currency of a finalised document | Fixed at finalise (`locale`/`currency` on the row). A document for a different locale is a **new** document, not a re-render of the old one. |
| Void | `document_number.status='voided'` (reason required), `document.status='void'`, `document.voided` emitted; the number is consumed, the gap forbidden, the correction is a new document (§3.4). |

This is the **WORM** guarantee made operational: storage is object-locked, the DB row is no-update after finalise, and every correction is itself a finalised, numbered, audited artefact — so the legal trail is append-only and reconstructable (doc 14 §5.1).

---

## 8. REST contracts (extends doc 06)

Base `/api/v1`; Keycloak bearer; authorisation per doc 05; money as `{amount,currency}`; layer-projected (a `packing_list` carries no money; an IC `commercial_invoice` is `inter_entity`-walled). Standard errors per doc 06; `422 DocumentValidationFailed` carries the missing `required_fields`.

```
## Documents (read + serve)
GET    /documents?type=&order_id=&bill_to_party_id=&entity_id=&from=&to=&status=
                                  → [Document]   (layer-projected; packing_list = volume only)
GET    /documents/{id}                                → Document (type, number, locale, jurisdiction, links, totals)
GET    /documents/{id}/pdf                            → application/pdf  (the stored WORM artefact; 200 stream)
GET    /documents/{id}/lineage                        → { order_invoice, order, ledger_transfers[], events[] }  (doc 14 §6)
POST   /documents/{id}/resend     { channel: email }  → { document_id }  (re-emits document.issued for the notifications consumer; no re-mint)
POST   /documents/{id}/void       { reason }          → Document  (maker≠checker; status=void; number recorded voided; emits document.voided)

## On-demand generation (the auto-triggered ones come from events; these are the manual/preview surfaces)
POST   /documents/proforma        { order_id | quote: {entity_id, bill_to_party_id, lines:[...]} , locale? }
                                  → Document  (non-fiscal; no gapless number; regenerable)
POST   /documents/credit-note     { order_invoice_id, scope: full|partial, lines?:[{order_line_id, qty, amount}], reason }
                                  → Document  (finalised credit_note; references the invoice via corrects_document_id; maker≠checker)
POST   /documents/commercial-invoice  { dispatch_id | intercompany_link_id, incoterm, country_of_origin? }
                                  → Document  (customs; requires hs_code per line — 422 if missing)
POST   /documents/statements      { bill_to_party_id, period | as_of, locale? }
                                  → Document  (open AR items for the payer; CEF master = consolidated across branches)
GET    /documents/{id}/preview    ?locale=&jurisdiction=   → application/pdf  (NON-finalising preview; allocates NO number)

## Templates (governed, maker-checker; admin/tax_specialist)
GET    /document-templates?type=&jurisdiction=&locale=&status=   → [DocumentTemplate]
POST   /document-templates        { document_type, jurisdiction?, locale?, entity_id?, body, legal_clauses, required_fields, font_stack, paper_size, effective_from }
                                  → DocumentTemplate (status=draft)
POST   /document-templates/{id}/activate   { }   → DocumentTemplate (status=active; CFO/admin approve, maker≠checker; emits document_template.changed)
GET    /document-templates/resolve ?document_type=&locale=&jurisdiction=&entity_id=   → DocumentTemplate  (shows the fallback-resolved template, §2.3)

## Numbering series (admin/finance; audit)
GET    /document-number-series?entity_id=&document_type=   → [DocumentNumberSeries]
POST   /document-number-series    { entity_id, document_type, jurisdiction?, series_code, format, period_scope }   → DocumentNumberSeries
GET    /document-number-series/{id}/audit                  → [DocumentNumber]  (the gapless allocation ledger; completeness evidence)
```

Auto-issued documents (`invoice` on `order.invoiced`, `packing_list`/`commercial_invoice` on `dispatch.created`, `credit_note` on `return.refunded`) are produced by **event consumers**, not these endpoints; the POST surfaces are for **manual/preview** generation (proforma, on-demand statement, ad-hoc credit note) and the **read/serve/void** lifecycle.

---

## 9. Permissions & data-layer mapping (extends doc 05)

| object_type | sections | layers (view/edit) | who (seed roles, doc 05 §4) |
|---|---|---|---|
| `document` (`invoice`/`credit_note`/`statement`) | — | `commercial` (totals/currency/lines); `pii` (payer name/address/contact) | `finance` view/create; `tax_specialist` view; `customer_service_agent` view (commercial+pii, scoped); `auditor` view-only |
| `document` (`packing_list`) | — | **`volume`** only (qty/serials — **no money**) | `fulfilment_agent` view/create; all warehouse roles |
| `document` (`commercial_invoice`, customer sale) | — | `commercial` + `pii` (customs value, consignee) | `finance`/`tax_specialist`/`fulfilment_agent` view |
| `document` (`commercial_invoice`, **intercompany**) | `inter_entity_pricing` | `inter_entity` (transfer-price value, lot cost) + `commercial` | `finance`/`tax_specialist`/`auditor` view; **Deal Desk sees none** (doc 05 §3 wall) |
| `document_template` | `document_template` | governed; edit maker-checker | `admin`/`tax_specialist` edit (propose); **CFO/admin** approve; `auditor` view |
| `document_number_series` / `document_number` | — | finance/admin view (the completeness audit) | `finance`/`admin` view; `auditor` view (gapless-series evidence) |

`field_layer_map` additions (drive doc 05 §3 projection): `document.total_amount|currency` and money inside `render_model` → **`commercial`**; payer name/address/contact inside `render_model` → **`pii`**; an IC `commercial_invoice`'s transfer-price/lot-cost fields → **`inter_entity`**; `document.qty`/serials on a `packing_list` → **`volume`**. So a `fulfilment_agent` generating a packing list sees quantities and serials but **no price**; the `inter_entity` wall on an IC commercial invoice matches doc 13 §9. **Layer projection applies to the `document.issued` event too** (doc 05 §3) — external/Xero/notification projections are layer-filtered.

**Segregation of duties (maker-checker, doc 05 §4 / doc 14 §4):** `document_template` activation and document `void` / `credit_note` issuance are maker-checker (proposer ≠ approver; CFO/admin approves a template). Finalising a fiscal document into a **`locked` `accounting_period` is rejected** at the boundary (doc 14 §2.4) — a late item issues into the current open period (or via a controlled prior-period adjustment). All of the above are audited (`audit_log` + immutable events + the WORM PDF; doc 05 §5): document issue, void, template change, numbering-series change.

---

## 10. Controls (extends doc 14 §4/§5 — ICFR)

Registered `control` rows with `evidence_query` (re-performable, doc 14 §6):

| control | assertion | type | evidence (re-perform) |
|---|---|---|---|
| Gapless numbering | **completeness** | detective | per `document_number_series`, `seq` is contiguous `1..current_seq` with no holes; every `voided` row retains its `seq` (number never reused) — a hole is an alarm (doc 14 §4) |
| Invoice never precedes delivery | cutoff, existence | preventive | every `invoice` document's `issued_at` ≥ its tranche `dispatch.delivered_at`; no `invoice` without a delivered tranche (ASC 606, doc 04 §Ledger) |
| Document ties to ledger | valuation, accuracy | detective | `document.total_amount` (invoice/credit_note) == the linked `order_invoice` total == the AR ledger transfer (re-derive, doc 14 §5.1); conservation `Σ lines == total` holds |
| Render reproducibility | accuracy, presentation | detective | re-render `(template@template_version, render_model)` → reproduces `content_sha256` byte-for-byte (deterministic renderer, §4.4) |
| Correction-is-credit-note | presentation, rights & obligations | detective | no finalised `invoice`/`credit_note` row mutated after `finalised`; every correction is a new numbered document with `corrects_document_id` set |
| Retention / WORM intact | rights & obligations | detective | every finalised fiscal document has a `storage_uri` under object-lock and a matching `content_sha256`; the stored bytes hash to it (tamper-evidence, doc 14 §5.3) |
| Required-field completeness | completeness, presentation | preventive | no finalised document missing a `template.required_fields` value (e.g. GB supplier VAT no.; reverse-charge notice where `tax_regime.kind='reverse_charge'`; HS code on a commercial invoice) |

These run continuously / at close, write `control_run` rows, and surface in the Auditability Center (doc 14 §6 — controls register, lineage explorer): a reported revenue figure drills **figure → ledger transfer → `order_invoice` → events → the issued PDF**.

---

## Acceptance

A subsystem implementation is **done** when:

1. **Templates vary by locale and jurisdiction, by data.** A template resolves per `(document_type, locale, jurisdiction)` with the fallback chain (§2.3); a GB invoice renders English with UK VAT particulars and the supplier VAT number, a DE invoice renders German mandatory particulars, an EU B2B reverse-charge invoice renders the **reverse-charge notice** and requires the customer VAT number — all from `document_template` + `tax_regime`/`tax_registration` data, **no code change**; year-1 ships UK-only (doc 02 §A), more markets switch on as data.
2. **Numbering is gapless, immutable and never reused.** Concurrent finalisers never share or skip a number (`FOR UPDATE` allocation, §3.3); voiding a finalised invoice records its number `voided` (reason required) and consumes it — the next invoice does **not** reuse it and **no gap** appears; the completeness control passes; proformas use a separate non-gapless series.
3. **The invoice trigger honours ASC 606.** The `invoice` document is produced by **`order.invoiced`** (auto-triggered by `dispatch.delivered`, per tranche) — `issued_at` ≥ delivery, **never before control transfer** (doc 04 §Ledger); a redelivered `order.invoiced` mints no second invoice or number (idempotent on `document_id`).
4. **The document is a projection of typed truth.** Totals are **read** from `order_invoice` (not recomputed), the conservation invariant `Σ lines == total` holds (doc 14 §1.3), no float touches any figure, and money/dates render per locale via CLDR/ICU — `de-DE` shows `1.234,50 €`, `en-GB` `£1,234.50`, **JPY rounds to 0 minor units** (doc 14 §1.1) — while the stored typed value stays `NUMERIC(18,4)`.
5. **PDF is a vendor-neutral, deterministic abstraction with full script coverage.** Rendering is behind the `DocumentRenderer` port; the same `(template_version, render_model)` produces byte-identical PDF (`content_sha256` reproduces — reproducibility control), and **CJK + Thai** (Japanese/Thai) documents embed the template `font_stack` and render correctly across all 15 locales (doc 02 §A).
6. **Storage is WORM and linked to its source.** Every finalised fiscal document is written to object-locked storage with a `content_sha256`, the DB row is immutable after `finalised`, and it FKs its originating `order`/`order_invoice`/`tranche`/`dispatch`/`rma`/`intercompany_link` so the Auditability Center clicks **figure → ledger transfer → `order_invoice` → events → PDF** (doc 14 §5.1/§6); retention honours the statutory term against DSAR (doc 14 §5.3).
7. **Corrections are credit notes; finalised documents are never re-rendered.** A wrong finalised invoice is corrected by a new numbered `credit_note` (`corrects_document_id`), never by editing the original; re-sending serves the stored bytes; a template change leaves prior documents on their `template_version`.
8. **Customs & intercompany content is correct and walled.** A cross-border dispatch/IC movement produces a `commercial_invoice` with HS code per line (`product_variant.hs_code`), country of origin, incoterm and the declared/transfer-price value (doc 13 §6) — it **renders** the declaration, it does not determine duty/VAT; its transfer-price content projects only to the `inter_entity` layer (Deal Desk sees none, doc 05 §3); a `packing_list` carries no money (`volume` layer only).
9. **Access wall & SoD hold.** Documents project per layer (`commercial`/`pii`/`inter_entity`/`volume`); template activation and document void/credit-note issuance are maker-checker; finalising into a `locked` `accounting_period` is rejected; every issue/void/template change is audited and reconstructable.

> Supports **M13** (doc 07): ERP/GL & P&L consumers + Xero, and the document-generation launch-blocker in doc 10 §B — legally-required artefacts (invoices, credit notes, proformas, packing lists, commercial invoices, statements) per locale + jurisdiction, gapless-numbered, WORM-stored, issued on delivery (ASC 606).
