# 25 — Associated / Inbound Documents (attachments)

> **✅ Implemented (M13-Docs.9).** `document_attachment` (V1_0_52: WORM `storage_uri` + `content_sha256`,
> `UNIQUE(subject, sha)` idempotent re-upload, `external_ref`, `data_layer`, `metadata`) + `order.source_attachment_id`.
> `document.AttachmentService` — `store` (sha-dedupe, WORM put, `document.attached` event with no PII), `download`,
> `listFor`, and `reconcilePo` (the PO's stated `metadata.po_total` vs the RESOLVED order total — `match`/`drift`,
> the verdict persisted on the attachment for the review worklist; drift is never silent). **The provenance trace**:
> `LineageService.contractualSources(orderId)` walks order → `customer_po_number` + source PO attachment → per-line
> `price_agreement` → the signed contract + schedules its tiers were entered from — and `forInvoice` (the
> Auditability Center lineage) now carries `contractual_sources`, so a recognised figure drills ledger → events →
> invoice → **the contractual sources of the revenue** in one response. REST: `POST/GET /api/v1/documents/attachments`
> (+ `/download`, `/reconcile/{orderId}`), `GET /api/v1/orders/{id}/provenance`; placement accepts
> `sourceAttachmentId`. `AttachmentProvenanceSuite` proves the full Octopus shape end-to-end on LocalStack S3:
> contract on the agreement, order created from PO HK00552 priced from the AGREEMENT (960 × £480, never the PO's
> stated prices), one call returns the whole chain, WORM bytes round-trip intact, and the £-760 drift case flags.
> *Deferred per §7: email-ingest mailbox; assisted PO parsing (baseline = manual + attach).*

**Status:** ~~design spec — spec only, no implementation yet~~ **implemented** (above). This adds the **inbound / received / attached**
document capability that complements **doc 17 (M13-Docs)**, which covers only **generated, outbound** documents
(invoices, credit notes, proformas — the `document` table carries a `render_model` because Conduit *produced*
them). What's missing is storing documents Conduit **receives** — a customer's purchase order, a signed supply
agreement and its schedules, a delivery note / proof-of-delivery, a certificate — and **associating** each with the
entity it belongs to (an order, a customer/party, a price agreement). It **reuses the existing `DocumentStorage`
WORM port** (S3 object-lock) and `content_sha256` discipline — no new storage mechanism.

> **Motivating sample:** Octopus sends POs like **HK00547** (£32k of accessories — covers, holsters, CT clamps,
> cables, brackets, shells, looms; ship-to ByBox) and **HK00552** (£508k, 960 Home 3 Pro chargers), "subject to
> standard Octopus trading terms." We must **store the PO** and **create an order from it**, and we hold the
> **supply agreement** (doc 24's `price_agreement`) and its schedules for the customer. The order *processing* exists;
> the document *attachment + provenance* is the gap.

---

## 1. Scope — attachments vs generated documents

| | Generated (doc 17, exists) | **Associated / inbound (this doc)** |
|---|---|---|
| Origin | Conduit renders it (has a `render_model`) | Received from a customer, or uploaded by staff |
| Numbering | gapless, governed series | the **customer's** own ref (e.g. PO# HK00547) — not ours |
| Examples | invoice, credit note, proforma, statement, packing list | **customer PO**, **signed contract + schedules**, delivery note / POD, certificate, correspondence |
| Storage | `DocumentStorage` WORM (S3) | **same** `DocumentStorage` WORM port |
| Table | `document` (+ `render_model`) | **`document_attachment`** (new — no render model; stores received bytes) |

A separate table keeps the two clean: generated docs have numbering + render provenance; attachments have an
external ref + an arbitrary content type and **belong to** a subject.

---

## 2. The model

```
document_attachment
  id,
  direction ∈ { inbound | uploaded },              -- received from a third party, or staff-uploaded
  kind ∈ { customer_po | signed_contract | contract_schedule | certificate
         | delivery_note | proof_of_delivery | correspondence | other },
  -- association (a doc belongs to exactly one subject; typed FKs for the common ones + a generic escape hatch):
  subject_type ∈ { order | party | price_agreement | dispatch | rma | invoice },  subject_id,
  filename, content_type (MIME), byte_size,
  storage_uri, content_sha256,                      -- WORM proof + dedupe + tamper-evidence (reused from doc 17)
  external_ref,                                     -- the source's own number (e.g. "HK00547"); cross-reference
  source ∈ { upload | email_ingest | api },  uploaded_by,  received_at,
  data_layer,                                       -- layer tag (doc 05) — gates view/download (e.g. commercial/inter_entity)
  metadata (JSONB),                                 -- optional extracted fields (PO total, line count) for reconciliation
  created_at
```

- **Immutable + WORM.** Bytes go to the S3 object-lock store; `content_sha256` is the tamper-evidence and the
  **dedupe key** (the same PO uploaded twice resolves to one stored object). Never edited; a correction is a new
  attachment that supersedes (link via `metadata.supersedes`).
- **Belongs-to-one-subject**, but a subject can have many attachments (an order: its PO + delivery note + POD).

---

## 3. What attaches where

- **Order** — its **source customer PO** (uniquely the document the order was created from), plus delivery notes /
  proof-of-delivery as fulfilment progresses. The order already has `customer_po_number`; the attachment links the
  actual PDF to it.
- **Party (customer)** — general customer documents (onboarding, compliance, correspondence) for a large account.
- **`price_agreement` (the contract)** — the **signed supply agreement + its schedules** (e.g. the Octopus
  Schedule 3 rate card). This is important: it is the **provenance for the governed price tiers** (doc 24) — the
  tiers were entered *from* this document, so the contract PDF sits beside the agreement that encodes its terms.
- **Dispatch / RMA / invoice** — optional (carrier docs, returns paperwork, a customer's remittance advice).

---

## 4. The PO → order flow (receive, store, create, reconcile)

1. **Receive & store.** The PO arrives (upload, or an email-ingest mailbox, or API). Store it as a `document_attachment`
   (`direction=inbound, kind=customer_po, external_ref="HK00547"`), bytes → WORM, `content_sha256` computed.
2. **Create the order from it.** Map PO lines (SKU + qty) to order lines via the catalogue; place the order through
   the **normal order path**. Per doc 24, the order **prices from the customer's authorized agreement/tier — NOT
   from the prices stated on the PO**. Set `order.customer_po_number = "HK00547"` and attach the PO to the order
   (`subject_type=order`).
3. **Reconcile (valuable).** Compare the **PO's stated total/line prices** (in `metadata`) against Conduit's
   **resolved order total**. A match → clean. A mismatch (the customer's PO price ≠ the contracted tier price) is
   **flagged for review**, not silently accepted — catching PO/contract drift before fulfilment. (HK00552's stated
   £417/£473/£435/£475 would be checked against the Octopus agreement's resolved tier.)
4. **Provenance.** The order's lineage (doc 14 §6) now reaches its **source PO document**; the agreement's tiers
   reach the **contract document** they were entered from.

> Line extraction can be **manual** (a clerk keys the lines, attaching the PDF as the record) or **assisted**
> (parse/OCR the PO into a draft order for review). Baseline = manual + attach; assisted parse is an enhancement
> (the attachment + reconciliation design is unchanged either way).

---

## 5. Storage, retention, access, lineage

- **Storage / WORM / dedupe** — reuse `DocumentStorage` (S3 object-lock + versioning) and `content_sha256`; same as
  generated docs (doc 17).
- **Access control / data layers** — view/download gated by permission + the attachment's `data_layer` (a contract
  with commercial terms → `commercial`; an inter-entity agreement → `inter_entity`), exactly like the generated
  `/documents` surface.
- **Lineage / audit** — attachments are first-class in the audit trail: the order → its source PO; the agreement →
  its signed contract + schedules. Strengthens the doc-14 §6 reconstructable chain.
- **PII & GDPR interplay (honest limitation).** Crypto-shred (doc 19 §B.3) erases PII by destroying a per-subject
  key — it **cannot selectively redact inside an opaque PDF blob**. Policy therefore: a B2B PO/contract is largely
  **not personal data** (company + the financial skeleton); where an attachment would carry an individual's PII,
  either keep it out of long-retained attachments, or treat the whole attachment as shreddable on erasure (delete
  the object + tombstone the row). Tag such attachments `pii` so the rule is enforceable. This is a known edge to
  decide at build (doc 19 §B.3 edge cases).

---

## 6. Data-model deltas, events, API

- **New:** `document_attachment` (§2). **Reuse:** `DocumentStorage` port, the WORM bucket.
- **`order`** already has `customer_po_number`; optionally add `source_attachment_id` (the PO it was created from) —
  or rely on the attachment's `subject`.
- **Events:** `document.attached { attachment_id, subject_type, subject_id, kind }` (envelope per doc 03) — lets
  downstream (search, notifications, an accounting mirror) react. No PII in the event payload.
- **API:** `POST /api/v1/documents/attachments` (multipart upload → store + associate), `GET …/attachments?subject_type=&subject_id=`
  (list, layer-projected), `GET …/attachments/{id}/download` (WORM fetch). The PO→order create reuses `POST /orders`
  with a `source_attachment_id`.

---

## 7. Reconciliations & open decisions

- **doc 17** — note the split: generated (`document`) vs associated (`document_attachment`), shared storage.
- **doc 02** — the `document_attachment` schema; `order.source_attachment_id` (optional).
- **doc 06** — the attachment upload/list/download endpoints.
- **doc 24** — the `price_agreement` carries its signed contract + schedules as attachments (tier provenance).
- **doc 14 §6 / doc 20** — lineage + the Auditability/Documents desk surface gains attachments.
- **doc 19 §B.3** — the PII-in-attachment policy (§5).
- **Open:** (a) typed FKs vs the generic `(subject_type, subject_id)` — recommend typed FKs for order/party/agreement
  + the generic escape hatch; (b) manual vs assisted PO parsing (baseline manual); (c) email-ingest mailbox as a
  source; (d) the PII-attachment policy above.

---

## 8. Milestone (spec-only)

A small extension — **M13-Docs.9 (associated documents)** + a touch of **M4** (PO→order provenance) and
**M-Pricing** (contract docs on the agreement). Build order when greenlit: (1) `document_attachment` + upload/list/
download over the existing WORM store + layer gating; (2) attach-to-order + `customer_po_number` provenance + the
PO-vs-resolved-price reconciliation flag; (3) contract docs on `price_agreement`; (4) optional email-ingest /
assisted parse. **Not started.**
