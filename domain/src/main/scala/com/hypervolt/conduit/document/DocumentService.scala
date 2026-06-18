package com.hypervolt.conduit.document

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class DocumentResult(
    documentId: UUID,
    formattedNumber: String,
    contentSha256: String,
    total: BigDecimal,
    status: String
)

private final case class InvHead(
    orderId: UUID,
    entityId: UUID,
    jurisdiction: String,
    billTo: UUID,
    payerName: String,
    supplierName: String,
    currency: String,
    totalEx: BigDecimal,
    vat: BigDecimal,
    total: BigDecimal,
    locale: String,
    dispatchId: Option[UUID]
)
private final case class InvLine(
    description: String,
    sku: String,
    qty: Int,
    unitPrice: BigDecimal,
    discountPct: BigDecimal,
    vatAmount: BigDecimal
)
private final case class TemplateRow(id: UUID, version: Int, body: String, requiredFields: List[String])
private final case class OrigDoc(
    id: UUID,
    entityId: UUID,
    formattedNumber: String,
    orderId: Option[UUID],
    billTo: Option[UUID],
    locale: String,
    jurisdiction: String,
    currency: String,
    total: BigDecimal,
    supplierName: String,
    payerName: String
)
private final case class Prepared(
    head: InvHead,
    templateId: UUID,
    templateVersion: Int,
    body: String,
    model: Json,
    requiredFields: List[String]
)

// Generates the legal `invoice` document (doc 17 §5): a RENDERED PROJECTION of typed truth. Totals are READ
// from order_invoice (never recomputed) and conservation-checked (Σ lines == total); the gapless number is
// allocated only after a successful render; the finalised document is WORM. Idempotent on a deterministic
// document id, so a redelivered order.invoiced mints no second invoice or number.
final class DocumentService[F[_]: Async](
    xa: Transactor[F],
    renderer: DocumentRenderer[F],
    storage: DocumentStorage[F]
) {

  private def docId(orderInvoiceId: UUID): UUID =
    UUID.nameUUIDFromBytes(s"document:invoice:$orderInvoiceId".getBytes(StandardCharsets.UTF_8))

  def generateInvoice(orderInvoiceId: UUID): F[Either[String, DocumentResult]] = {
    val id = docId(orderInvoiceId)
    existing(id).transact(xa).flatMap {
      case Some(r) => r.asRight[String].pure[F] // idempotent: already issued
      case None =>
        prepare(orderInvoiceId).transact(xa).flatMap {
          case Left(e) => e.asLeft[DocumentResult].pure[F]
          case Right(prep) =>
            renderer.render(prep.body, prep.model).flatMap { rendered =>
              // Put the bytes to WORM storage BEFORE the row commits; the URI is then recorded immutably. A failed
              // commit leaves an orphan object (harmless — the deterministic key re-puts identically on retry).
              storage
                .put(s"documents/$id.pdf", rendered.bytes, "application/pdf")
                .flatMap(uri => persist(id, orderInvoiceId, prep, rendered, uri).transact(xa))
            }
        }
    }
  }

  // Backfill the historical invoiced book (C5 / M13-Docs): generate the WORM PDF + gapless number for up to
  // `limit` order_invoices that have no invoice document yet. Bounded per call so a background loop drains the
  // book gently (never a render storm). Idempotent — generateInvoice no-ops a doc that already exists. Returns
  // the count newly issued this call (0 when the book is fully documented).
  def backfillPending(limit: Int): F[Int] =
    sql"""SELECT oi.id FROM order_invoice oi
          WHERE oi.status <> 'void'
            AND NOT EXISTS (SELECT 1 FROM document d WHERE d.order_invoice_id = oi.id AND d.document_type = 'invoice')
          ORDER BY oi.issued_at NULLS LAST
          LIMIT $limit"""
      .query[UUID]
      .to[List]
      .transact(xa)
      .flatMap(_.foldLeftM(0)((n, id) => generateInvoice(id).map(_.fold(_ => n, _ => n + 1)).handleError(_ => n)))

  private def existing(id: UUID): ConnectionIO[Option[DocumentResult]] =
    sql"""SELECT id, formatted_number, content_sha256, total_amount, status FROM document WHERE id = $id AND status = 'finalised'"""
      .query[(UUID, Option[String], Option[String], Option[BigDecimal], String)]
      .option
      .map(_.map {
        case (d, n, sha, t, st) => DocumentResult(d, n.getOrElse(""), sha.getOrElse(""), t.getOrElse(0), st)
      })

  // ----- assemble (READ the truth; resolve the template; conservation + required-field guards) -----

  private def prepare(orderInvoiceId: UUID): ConnectionIO[Either[String, Prepared]] =
    (head(orderInvoiceId), Option.empty[Prepared].pure[ConnectionIO]).tupled.flatMap {
      case (None, _) => "unknown invoice".asLeft[Prepared].pure[ConnectionIO]
      case (Some(h), _) =>
        (lines(h.orderId, h.dispatchId), template(h.jurisdiction, h.locale)).tupled.map {
          case (_, None) => "no active invoice template".asLeft[Prepared]
          case (ls, Some(t)) =>
            val conserved = ls
              .map(l =>
                (l.unitPrice * l.qty * (1 - l.discountPct / 100)).setScale(2, RoundingMode.HALF_UP) + l.vatAmount
              )
              .sum
            if (conserved.setScale(2, RoundingMode.HALF_UP) != h.total.setScale(2, RoundingMode.HALF_UP))
              s"conservation failed: Σ lines $conserved != invoice total ${h.total}".asLeft[Prepared]
            else {
              val model = buildModel(h, ls)
              missingRequired(model, t.requiredFields) match {
                case Some(f) => s"required field missing: $f".asLeft[Prepared]
                case None    => Prepared(h, t.id, t.version, t.body, model, t.requiredFields).asRight[String]
              }
            }
        }
    }

  private def head(invId: UUID): ConnectionIO[Option[InvHead]] =
    sql"""SELECT o.id, o.entity_id, e.jurisdiction, o.bill_to_party_id,
                 COALESCE(p.legal_name, regexp_replace(p.display_name, '^MRP:\s*', '')),
                 e.name, o.txn_currency, i.total_ex_vat, i.vat_total, i.total_inc_vat,
                 COALESCE(bp.invoice_locale, 'en'), i.dispatch_id
          FROM order_invoice i
            JOIN "order" o ON o.id = i.order_id
            JOIN entity e ON e.id = o.entity_id
            JOIN party p ON p.id = o.bill_to_party_id
            LEFT JOIN billing_profile bp ON bp.party_id = o.bill_to_party_id
          WHERE i.id = $invId"""
      .query[InvHead]
      .option

  // An invoice bills what it shipped: when it is tied to a dispatch (a tranche/call-off of the order), the
  // document lists only that dispatch's lines at the dispatched quantity, with VAT prorated to that quantity —
  // not every line on the order (doc 17 §2 / M4 tranches). A whole-order invoice (no dispatch) lists all lines.
  private def lines(orderId: UUID, dispatchId: Option[UUID]): ConnectionIO[List[InvLine]] =
    dispatchId match {
      case Some(d) =>
        sql"""SELECT COALESCE(NULLIF(v.name, ''), v.sku), v.sku, dl.qty, ol.unit_price_ex_vat, ol.discount_pct,
                     CASE WHEN ol.qty > 0 THEN round(ol.vat_amount * dl.qty / ol.qty, 2) ELSE ol.vat_amount END
              FROM dispatch_line dl
                JOIN order_line ol ON ol.id = dl.order_line_id
                JOIN product_variant v ON v.id = ol.product_variant_id
              WHERE dl.dispatch_id = $d ORDER BY ol.id"""
          .query[InvLine]
          .to[List]
      case None =>
        sql"""SELECT COALESCE(NULLIF(v.name, ''), v.sku), v.sku, ol.qty, ol.unit_price_ex_vat, ol.discount_pct, ol.vat_amount
              FROM order_line ol JOIN product_variant v ON v.id = ol.product_variant_id
              WHERE ol.order_id = $orderId ORDER BY ol.id"""
          .query[InvLine]
          .to[List]
    }

  // Fallback resolution (doc 17 §2.3): exact (jurisdiction, locale) first, then jurisdiction-only, locale-only, global.
  private def template(jurisdiction: String, locale: String): ConnectionIO[Option[TemplateRow]] =
    sql"""SELECT id, version, body, required_fields FROM document_template
          WHERE document_type = 'invoice' AND status = 'active'
            AND (jurisdiction = $jurisdiction OR jurisdiction IS NULL)
            AND (locale = $locale OR locale IS NULL)
          ORDER BY (jurisdiction IS NOT NULL) DESC, (locale IS NOT NULL) DESC, version DESC LIMIT 1"""
      .query[(UUID, Int, String, List[String])]
      .option
      .map(_.map { case (id, v, b, rf) => TemplateRow(id, v, b, rf) })

  private def buildModel(h: InvHead, ls: List[InvLine]): Json =
    Json.obj(
      "supplier_name" -> h.supplierName.asJson,
      "payer_name"    -> h.payerName.asJson,
      "locale"        -> h.locale.asJson,
      "jurisdiction"  -> h.jurisdiction.asJson,
      "currency"      -> h.currency.asJson,
      "lines" -> ls
        .map(l =>
          Json.obj(
            "description" -> l.description.asJson,
            "sku"         -> l.sku.asJson,
            "qty"         -> l.qty.asJson,
            "unit_price"  -> l.unitPrice.asJson,
            "vat"         -> l.vatAmount.asJson,
            "line_total" -> ((l.unitPrice * l.qty * (1 - l.discountPct / 100))
              .setScale(2, RoundingMode.HALF_UP) + l.vatAmount).asJson
          )
        )
        .asJson,
      "subtotal" -> h.totalEx.asJson,
      "vat"      -> h.vat.asJson,
      "total"    -> h.total.asJson
    )

  private def missingRequired(model: Json, required: List[String]): Option[String] =
    required.find(f => model.hcursor.downField(f).focus.forall(j => j.isNull || j.asString.contains("")))

  // ----- finalise: gapless number + WORM document row + outbox, all-or-none -----

  private def persist(
      id: UUID,
      orderInvoiceId: UUID,
      prep: Prepared,
      rendered: RenderedDoc,
      storageUri: String
  ): ConnectionIO[Either[String, DocumentResult]] = {
    val h = prep.head
    DocumentNumberAllocator.allocate(h.entityId, "invoice", h.jurisdiction, LocalDate.now().getYear).flatMap {
      case None => "no active number series for invoice".asLeft[DocumentResult].pure[ConnectionIO]
      case Some(num) =>
        for {
          _ <- sql"""INSERT INTO document
                       (id, document_type, entity_id, document_number_id, formatted_number, order_invoice_id,
                        order_id, bill_to_party_id, locale, jurisdiction, template_id, template_version, currency,
                        total_amount, render_model, status, storage_uri, content_sha256, issued_at)
                     VALUES ($id, 'invoice', ${h.entityId}, ${num.numberId}, ${num.formatted}, $orderInvoiceId,
                        ${h.orderId}, ${h.billTo}, ${h.locale}, ${h.jurisdiction}, ${prep.templateId}, ${prep.templateVersion},
                        ${h.currency}, ${h.total}, ${prep.model}, 'finalised', $storageUri, ${rendered.contentSha256}, now())""".update.run
          _ <- DocumentNumberAllocator.markIssued(num.numberId, id)
          _ <- OutboxRepo.append(issuedEvent(id, num.formatted, h, rendered, storageUri))
        } yield DocumentResult(id, num.formatted, rendered.contentSha256, h.total, "finalised").asRight[String]
    }
  }

  private def issuedEvent(
      id: UUID,
      number: String,
      h: InvHead,
      rendered: RenderedDoc,
      storageUri: String
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "document.issued",
      1,
      "document",
      id,
      id.toString,
      None,
      None,
      None,
      Json.obj(
        "document_id"      -> id.toString.asJson,
        "document_type"    -> "invoice".asJson,
        "formatted_number" -> number.asJson,
        "entity_id"        -> h.entityId.toString.asJson,
        "order_id"         -> h.orderId.toString.asJson,
        "bill_to_party_id" -> h.billTo.toString.asJson,
        "currency"         -> h.currency.asJson,
        "total_amount"     -> h.total.asJson,
        "storage_uri"      -> storageUri.asJson,
        "content_sha256"   -> rendered.contentSha256.asJson,
        "locale"           -> h.locale.asJson,
        "jurisdiction"     -> h.jurisdiction.asJson
      ),
      Instant.now(),
      "service:documents"
    )

  // ===== invoice invalidation (doc 13 §void / 17 §6) =====
  // On invoice.voided: stamp the original invoice document with a WORM-safe void marker (bytes untouched) and mint
  // a credit_note document that `corrects_document_id` it. Idempotent on a deterministic credit-note id. Returns
  // None when there is no original invoice document to invalidate (nothing to correct).
  def invalidateInvoice(
      orderInvoiceId: UUID,
      reason: String,
      correlationId: Option[UUID] = None,
      causationId: Option[UUID] = None
  ): F[Either[String, Option[DocumentResult]]] = {
    val cnId = UUID.nameUUIDFromBytes(s"document:credit_note:$orderInvoiceId".getBytes(StandardCharsets.UTF_8))
    existing(cnId).transact(xa).flatMap {
      case Some(r) => Option(r).asRight[String].pure[F] // idempotent: credit note already minted
      case None =>
        origInvoiceDoc(orderInvoiceId).transact(xa).flatMap {
          case None => Option.empty[DocumentResult].asRight[String].pure[F] // no invoice document to invalidate
          case Some(orig) =>
            creditTemplate(orig.jurisdiction, orig.locale).transact(xa).flatMap {
              case None => "no active credit_note template".asLeft[Option[DocumentResult]].pure[F]
              case Some(t) =>
                val model = creditModel(orig, reason)
                renderer
                  .render(t.body, model)
                  .flatMap(rendered =>
                    storage
                      .put(s"documents/$cnId.pdf", rendered.bytes, "application/pdf")
                      .flatMap(uri =>
                        persistCreditNote(cnId, orig, t, model, rendered, uri, reason, correlationId, causationId)
                          .transact(xa)
                      )
                  )
            }
        }
    }
  }

  private def origInvoiceDoc(orderInvoiceId: UUID): ConnectionIO[Option[OrigDoc]] =
    sql"""SELECT id, entity_id, formatted_number, order_id, bill_to_party_id, locale, jurisdiction, currency,
                 total_amount, render_model
          FROM document
          WHERE order_invoice_id = $orderInvoiceId AND document_type = 'invoice' AND status = 'finalised'
          ORDER BY issued_at DESC LIMIT 1"""
      .query[
        (
            UUID,
            UUID,
            Option[String],
            Option[UUID],
            Option[UUID],
            String,
            String,
            Option[String],
            Option[BigDecimal],
            Json
        )
      ]
      .option
      .map(_.map {
        case (id, ent, no, ord, bill, loc, jur, ccy, tot, rm) =>
          OrigDoc(
            id,
            ent,
            no.getOrElse(""),
            ord,
            bill,
            loc,
            jur,
            ccy.getOrElse("GBP"),
            tot.getOrElse(BigDecimal(0)),
            rm.hcursor.get[String]("supplier_name").getOrElse(""),
            rm.hcursor.get[String]("payer_name").getOrElse("")
          )
      })

  private def creditTemplate(jurisdiction: String, locale: String): ConnectionIO[Option[TemplateRow]] =
    sql"""SELECT id, version, body, required_fields FROM document_template
          WHERE document_type = 'credit_note' AND status = 'active'
            AND (jurisdiction = $jurisdiction OR jurisdiction IS NULL)
            AND (locale = $locale OR locale IS NULL)
          ORDER BY (jurisdiction IS NOT NULL) DESC, (locale IS NOT NULL) DESC, version DESC LIMIT 1"""
      .query[(UUID, Int, String, List[String])]
      .option
      .map(_.map { case (id, v, b, rf) => TemplateRow(id, v, b, rf) })

  private def creditModel(o: OrigDoc, reason: String): Json =
    Json.obj(
      "supplier_name"   -> o.supplierName.asJson,
      "payer_name"      -> o.payerName.asJson,
      "locale"          -> o.locale.asJson,
      "jurisdiction"    -> o.jurisdiction.asJson,
      "currency"        -> o.currency.asJson,
      "corrects_number" -> o.formattedNumber.asJson,
      "reason"          -> reason.asJson,
      "total"           -> o.total.asJson
    )

  private def persistCreditNote(
      cnId: UUID,
      orig: OrigDoc,
      t: TemplateRow,
      model: Json,
      rendered: RenderedDoc,
      storageUri: String,
      reason: String,
      correlationId: Option[UUID],
      causationId: Option[UUID]
  ): ConnectionIO[Either[String, Option[DocumentResult]]] =
    DocumentNumberAllocator.allocate(orig.entityId, "credit_note", orig.jurisdiction, LocalDate.now().getYear).flatMap {
      case None => "no active number series for credit_note".asLeft[Option[DocumentResult]].pure[ConnectionIO]
      case Some(num) =>
        for {
          // stamp the original (WORM: bytes unchanged, the row carries the marker); idempotent on voided_at IS NULL
          _ <-
            sql"UPDATE document SET voided_at = now(), void_reason = $reason WHERE id = ${orig.id} AND voided_at IS NULL".update.run
          _ <- sql"""INSERT INTO document
                       (id, document_type, entity_id, document_number_id, formatted_number, order_invoice_id,
                        order_id, bill_to_party_id, locale, jurisdiction, template_id, template_version, currency,
                        total_amount, render_model, corrects_document_id, status, storage_uri, content_sha256, issued_at)
                     VALUES ($cnId, 'credit_note', ${orig.entityId}, ${num.numberId}, ${num.formatted},
                        (SELECT order_invoice_id FROM document WHERE id = ${orig.id}),
                        ${orig.orderId}, ${orig.billTo}, ${orig.locale}, ${orig.jurisdiction}, ${t.id}, ${t.version},
                        ${orig.currency}, ${orig.total}, $model, ${orig.id}, 'finalised', $storageUri, ${rendered.contentSha256}, now())""".update.run
          _ <- DocumentNumberAllocator.markIssued(num.numberId, cnId)
          _ <- OutboxRepo.append(
            creditIssuedEvent(cnId, num.formatted, orig, rendered, storageUri, correlationId, causationId)
          )
        } yield Option(DocumentResult(cnId, num.formatted, rendered.contentSha256, orig.total, "finalised"))
          .asRight[String]
    }

  private def creditIssuedEvent(
      id: UUID,
      number: String,
      orig: OrigDoc,
      rendered: RenderedDoc,
      storageUri: String,
      correlationId: Option[UUID],
      causationId: Option[UUID]
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "document.issued",
      1,
      "document",
      id,
      id.toString,
      None,
      correlationId,
      causationId,
      Json.obj(
        "document_id"      -> id.toString.asJson,
        "document_type"    -> "credit_note".asJson,
        "formatted_number" -> number.asJson,
        "corrects_number"  -> orig.formattedNumber.asJson,
        "entity_id"        -> orig.entityId.toString.asJson,
        "order_id"         -> orig.orderId.map(_.toString).asJson,
        "bill_to_party_id" -> orig.billTo.map(_.toString).asJson,
        "currency"         -> orig.currency.asJson,
        "total_amount"     -> orig.total.asJson,
        "storage_uri"      -> storageUri.asJson,
        "content_sha256"   -> rendered.contentSha256.asJson,
        "locale"           -> orig.locale.asJson,
        "jurisdiction"     -> orig.jurisdiction.asJson
      ),
      Instant.now(),
      "service:documents"
    )

  // ===== additional document types (doc 17 §5): proforma + packing list =====

  // A generic template lookup for any document type (the invoice/credit lookups are special-cased above).
  private def templateFor(docType: String, jurisdiction: String, locale: String): ConnectionIO[Option[TemplateRow]] =
    sql"""SELECT id, version, body, required_fields FROM document_template
          WHERE document_type = $docType AND status = 'active'
            AND (jurisdiction = $jurisdiction OR jurisdiction IS NULL)
            AND (locale = $locale OR locale IS NULL)
          ORDER BY (jurisdiction IS NOT NULL) DESC, (locale IS NOT NULL) DESC, version DESC LIMIT 1"""
      .query[(UUID, Int, String, List[String])]
      .option
      .map(_.map { case (id, v, b, rf) => TemplateRow(id, v, b, rf) })

  // Proforma invoice (doc 17): a pre-payment projection of the order — no AR, no number reuse with the tax invoice.
  // Idempotent on a deterministic id. The order totals are READ, never recomputed.
  def generateProforma(orderId: UUID): F[Either[String, DocumentResult]] = {
    val id = UUID.nameUUIDFromBytes(s"document:proforma:$orderId".getBytes(StandardCharsets.UTF_8))
    existing(id).transact(xa).flatMap {
      case Some(r) => r.asRight[String].pure[F]
      case None =>
        (orderHead(orderId), lines(orderId, None), Option.empty[Unit].pure[ConnectionIO]).tupled.transact(xa).flatMap {
          case (None, _, _) => "unknown order".asLeft[DocumentResult].pure[F]
          case (Some(h), ls, _) =>
            templateFor("proforma", h.jurisdiction, h.locale).transact(xa).flatMap {
              case None => "no active proforma template".asLeft[DocumentResult].pure[F]
              case Some(t) =>
                val model = buildModel(h, ls).deepMerge(
                  Json.obj(
                    "title" -> "PROFORMA INVOICE".asJson,
                    "notes" -> Json.arr("Proforma — not a VAT invoice. Payable in advance of dispatch.".asJson)
                  )
                )
                renderer
                  .render(t.body, model)
                  .flatMap(rd =>
                    storage
                      .put(s"documents/$id.pdf", rd.bytes, "application/pdf")
                      .flatMap(uri =>
                        finaliseDoc(
                          id,
                          "proforma",
                          h.entityId,
                          h.jurisdiction,
                          h.locale,
                          Some(h.orderId),
                          None,
                          None,
                          Some(h.billTo),
                          Some(h.currency),
                          Some(h.total),
                          t,
                          model,
                          rd,
                          uri
                        ).transact(xa)
                      )
                  )
            }
        }
    }
  }

  // Packing list (doc 17 §9): a VOLUME-only shipment document — packed contents + serials, no money. Tied to a
  // dispatch. Idempotent on a deterministic id.
  def generatePackingList(dispatchId: UUID): F[Either[String, DocumentResult]] = {
    val id = UUID.nameUUIDFromBytes(s"document:packing_list:$dispatchId".getBytes(StandardCharsets.UTF_8))
    existing(id).transact(xa).flatMap {
      case Some(r) => r.asRight[String].pure[F]
      case None =>
        (dispatchHead(dispatchId), dispatchRows(dispatchId)).tupled.transact(xa).flatMap {
          case (None, _) => "unknown dispatch".asLeft[DocumentResult].pure[F]
          case (Some(h), rows) =>
            templateFor("packing_list", h.jurisdiction, h.locale).transact(xa).flatMap {
              case None => "no active packing_list template".asLeft[DocumentResult].pure[F]
              case Some(t) =>
                val model = Json.obj(
                  "title"         -> "PACKING LIST".asJson,
                  "supplier_name" -> h.supplierName.asJson,
                  "payer_name"    -> h.shipTo.asJson,
                  "payer_label"   -> "Ship to".asJson,
                  "locale"        -> h.locale.asJson,
                  "jurisdiction"  -> h.jurisdiction.asJson,
                  "meta" -> Json
                    .arr(Json.arr("Dispatch".asJson, h.dispatchNo.asJson), Json.arr("Order".asJson, h.orderNo.asJson)),
                  "columns" -> Json.arr("Description".asJson, "SKU".asJson, "Qty".asJson, "Serials".asJson),
                  "rows" -> rows
                    .map(r => Json.arr(r._1.asJson, r._2.asJson, r._3.asJson, r._4.asJson))
                    .asJson
                )
                renderer
                  .render(t.body, model)
                  .flatMap(rd =>
                    storage
                      .put(s"documents/$id.pdf", rd.bytes, "application/pdf")
                      .flatMap(uri =>
                        finaliseDoc(
                          id,
                          "packing_list",
                          h.entityId,
                          h.jurisdiction,
                          h.locale,
                          Some(h.orderId),
                          None,
                          Some(dispatchId),
                          None,
                          None,
                          None,
                          t,
                          model,
                          rd,
                          uri
                        ).transact(xa)
                      )
                  )
            }
        }
    }
  }

  // Customer statement (doc 17): a point-in-time projection of a party's open invoices for an entity. Idempotent
  // per (party, period) — re-running the same period returns the same numbered statement.
  def generateStatement(entityId: UUID, partyId: UUID, periodKey: String): F[Either[String, DocumentResult]] = {
    val id = UUID.nameUUIDFromBytes(s"document:statement:$partyId:$periodKey".getBytes(StandardCharsets.UTF_8))
    existing(id).transact(xa).flatMap {
      case Some(r) => r.asRight[String].pure[F]
      case None =>
        (statementHead(entityId, partyId), openInvoices(entityId, partyId)).tupled.transact(xa).flatMap {
          case (None, _) => "unknown entity/party".asLeft[DocumentResult].pure[F]
          case (Some(h), rows) =>
            templateFor("statement", h._4, "en").transact(xa).flatMap {
              case None => "no active statement template".asLeft[DocumentResult].pure[F]
              case Some(t) =>
                val outstanding = rows.map(_._4).sum
                val model = Json.obj(
                  "title"         -> "STATEMENT OF ACCOUNT".asJson,
                  "supplier_name" -> h._1.asJson,
                  "payer_name"    -> h._2.asJson,
                  "payer_label"   -> "Account".asJson,
                  "locale"        -> "en".asJson,
                  "jurisdiction"  -> h._4.asJson,
                  "currency"      -> h._3.asJson,
                  "meta"          -> Json.arr(Json.arr("As of".asJson, periodKey.asJson)),
                  "columns"       -> Json.arr("Invoice".asJson, "Issued".asJson, "Due".asJson, "Outstanding".asJson),
                  "rows" -> rows
                    .map(r =>
                      Json
                        .arr(r._1.asJson, r._2.toString.asJson, r._3.map(_.toString).getOrElse("").asJson, r._4.asJson)
                    )
                    .asJson,
                  "total" -> outstanding.asJson
                )
                renderer
                  .render(t.body, model)
                  .flatMap(rd =>
                    storage
                      .put(s"documents/$id.pdf", rd.bytes, "application/pdf")
                      .flatMap(uri =>
                        finaliseDoc(
                          id,
                          "statement",
                          entityId,
                          h._4,
                          "en",
                          None,
                          None,
                          None,
                          Some(partyId),
                          Some(h._3),
                          Some(outstanding),
                          t,
                          model,
                          rd,
                          uri
                        ).transact(xa)
                      )
                  )
            }
        }
    }
  }

  private def statementHead(entityId: UUID, partyId: UUID): ConnectionIO[Option[(String, String, String, String)]] =
    sql"""SELECT e.name, COALESCE(p.legal_name, p.display_name), e.functional_currency, e.jurisdiction
          FROM entity e, party p WHERE e.id = $entityId AND p.id = $partyId"""
      .query[(String, String, String, String)]
      .option

  private def openInvoices(
      entityId: UUID,
      partyId: UUID
  ): ConnectionIO[List[(String, java.time.LocalDate, Option[java.time.LocalDate], BigDecimal)]] =
    sql"""SELECT i.invoice_no, i.issued_at::date, i.due_date,
                 i.total_inc_vat - COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.order_invoice_id = i.id), 0)
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id
          WHERE o.bill_to_party_id = $partyId AND o.entity_id = $entityId AND i.status IN ('open','part_paid')
          ORDER BY i.issued_at"""
      .query[(String, java.time.LocalDate, Option[java.time.LocalDate], BigDecimal)]
      .to[List]

  // Commercial invoice (doc 17): the customs document for a shipment — HS codes, country of origin, incoterms,
  // customs value per line. Tied to a dispatch. Idempotent on a deterministic id.
  def generateCommercialInvoice(dispatchId: UUID): F[Either[String, DocumentResult]] = {
    val id = UUID.nameUUIDFromBytes(s"document:commercial_invoice:$dispatchId".getBytes(StandardCharsets.UTF_8))
    existing(id).transact(xa).flatMap {
      case Some(r) => r.asRight[String].pure[F]
      case None =>
        (commercialHead(dispatchId), commercialRows(dispatchId)).tupled.transact(xa).flatMap {
          case (None, _) => "unknown dispatch".asLeft[DocumentResult].pure[F]
          case (Some(h), rows) =>
            templateFor("commercial_invoice", h.jurisdiction, h.locale).transact(xa).flatMap {
              case None => "no active commercial_invoice template".asLeft[DocumentResult].pure[F]
              case Some(t) =>
                val total = rows.map(_._5).sum
                val model = Json.obj(
                  "title"         -> "COMMERCIAL INVOICE".asJson,
                  "supplier_name" -> h.supplierName.asJson,
                  "payer_name"    -> h.shipTo.asJson,
                  "payer_label"   -> "Ship to".asJson,
                  "locale"        -> h.locale.asJson,
                  "jurisdiction"  -> h.jurisdiction.asJson,
                  "currency"      -> h.currency.asJson,
                  "meta" -> Json.arr(
                    Json.arr("Incoterms".asJson, h.incoterms.asJson),
                    Json.arr("Country of export".asJson, h.jurisdiction.asJson),
                    Json.arr("Dispatch".asJson, h.dispatchNo.asJson)
                  ),
                  "columns" -> Json
                    .arr("Description".asJson, "HS code".asJson, "Origin".asJson, "Qty".asJson, "Value".asJson),
                  "rows" -> rows
                    .map(r => Json.arr(r._1.asJson, r._2.asJson, r._3.asJson, r._4.asJson, r._5.asJson))
                    .asJson,
                  "total" -> total.asJson,
                  "notes" -> Json.arr("For customs purposes only. Goods of the stated origin.".asJson)
                )
                renderer
                  .render(t.body, model)
                  .flatMap(rd =>
                    storage
                      .put(s"documents/$id.pdf", rd.bytes, "application/pdf")
                      .flatMap(uri =>
                        finaliseDoc(
                          id,
                          "commercial_invoice",
                          h.entityId,
                          h.jurisdiction,
                          h.locale,
                          Some(h.orderId),
                          None,
                          Some(dispatchId),
                          None,
                          Some(h.currency),
                          Some(total),
                          t,
                          model,
                          rd,
                          uri
                        ).transact(xa)
                      )
                  )
            }
        }
    }
  }

  private def commercialHead(dispatchId: UUID): ConnectionIO[Option[CommercialHead]] =
    sql"""SELECT o.id, o.entity_id, e.jurisdiction, e.name, COALESCE(p.legal_name, p.display_name),
                 COALESCE(bp.invoice_locale, 'en'), d.dispatch_no, o.order_no, o.txn_currency, COALESCE(o.incoterms, 'DAP')
          FROM dispatch d
            JOIN "order" o ON o.id = d.order_id
            JOIN entity e ON e.id = o.entity_id
            JOIN party p ON p.id = o.bill_to_party_id
            LEFT JOIN billing_profile bp ON bp.party_id = o.bill_to_party_id
          WHERE d.id = $dispatchId"""
      .query[CommercialHead]
      .option

  // Per dispatch line: description, HS code, origin, qty, customs value (qty × unit ex-VAT).
  private def commercialRows(dispatchId: UUID): ConnectionIO[List[(String, String, String, Int, BigDecimal)]] =
    sql"""SELECT COALESCE(f.name, v.sku), COALESCE(v.hs_code, ''), COALESCE(v.country_of_origin, ''),
                 dl.qty, (dl.qty * ol.unit_price_ex_vat)
          FROM dispatch_line dl
            JOIN order_line ol ON ol.id = dl.order_line_id
            JOIN product_variant v ON v.id = ol.product_variant_id
            LEFT JOIN product_family f ON f.id = v.family_id
          WHERE dl.dispatch_id = $dispatchId ORDER BY dl.id"""
      .query[(String, String, String, Int, BigDecimal)]
      .to[List]

  private def orderHead(orderId: UUID): ConnectionIO[Option[InvHead]] =
    sql"""SELECT o.id, o.entity_id, e.jurisdiction, o.bill_to_party_id, COALESCE(p.legal_name, p.display_name),
                 e.name, o.txn_currency, o.subtotal_ex_vat, o.vat_total, o.total_inc_vat, COALESCE(bp.invoice_locale, 'en')
          FROM "order" o
            JOIN entity e ON e.id = o.entity_id
            JOIN party p ON p.id = o.bill_to_party_id
            LEFT JOIN billing_profile bp ON bp.party_id = o.bill_to_party_id
          WHERE o.id = $orderId"""
      .query[InvHead]
      .option

  private def dispatchHead(dispatchId: UUID): ConnectionIO[Option[DispatchDocHead]] =
    sql"""SELECT o.id, o.entity_id, e.jurisdiction, e.name, COALESCE(p.legal_name, p.display_name),
                 COALESCE(bp.invoice_locale, 'en'), d.dispatch_no, o.order_no
          FROM dispatch d
            JOIN "order" o ON o.id = d.order_id
            JOIN entity e ON e.id = o.entity_id
            JOIN party p ON p.id = o.bill_to_party_id
            LEFT JOIN billing_profile bp ON bp.party_id = o.bill_to_party_id
          WHERE d.id = $dispatchId"""
      .query[DispatchDocHead]
      .option

  // Per dispatch line: description, sku, qty, and the serials shipped (volume-only — never priced).
  private def dispatchRows(dispatchId: UUID): ConnectionIO[List[(String, String, Int, String)]] =
    sql"""SELECT COALESCE(f.name, v.sku), v.sku, dl.qty,
                 COALESCE((SELECT string_agg(s.serial_no, ', ' ORDER BY s.serial_no) FROM serial_unit s
                           WHERE s.dispatch_id = $dispatchId AND s.product_variant_id = ol.product_variant_id), '')
          FROM dispatch_line dl
            JOIN order_line ol ON ol.id = dl.order_line_id
            JOIN product_variant v ON v.id = ol.product_variant_id
            LEFT JOIN product_family f ON f.id = v.family_id
          WHERE dl.dispatch_id = $dispatchId ORDER BY dl.id"""
      .query[(String, String, Int, String)]
      .to[List]

  // One finalise path for the non-invoice types: gapless number, WORM document row, document.issued — all-or-none.
  private def finaliseDoc(
      id: UUID,
      docType: String,
      entityId: UUID,
      jurisdiction: String,
      locale: String,
      orderId: Option[UUID],
      orderInvoiceId: Option[UUID],
      dispatchId: Option[UUID],
      billTo: Option[UUID],
      currency: Option[String],
      total: Option[BigDecimal],
      t: TemplateRow,
      model: Json,
      rendered: RenderedDoc,
      storageUri: String
  ): ConnectionIO[Either[String, DocumentResult]] =
    DocumentNumberAllocator.allocate(entityId, docType, jurisdiction, LocalDate.now().getYear).flatMap {
      case None => s"no active number series for $docType".asLeft[DocumentResult].pure[ConnectionIO]
      case Some(num) =>
        for {
          _ <- sql"""INSERT INTO document
                       (id, document_type, entity_id, document_number_id, formatted_number, order_invoice_id,
                        order_id, dispatch_id, bill_to_party_id, locale, jurisdiction, template_id, template_version,
                        currency, total_amount, render_model, status, storage_uri, content_sha256, issued_at)
                     VALUES ($id, $docType, $entityId, ${num.numberId}, ${num.formatted}, $orderInvoiceId,
                        $orderId, $dispatchId, $billTo, $locale, $jurisdiction, ${t.id}, ${t.version},
                        $currency, $total, $model, 'finalised', $storageUri, ${rendered.contentSha256}, now())""".update.run
          _ <- DocumentNumberAllocator.markIssued(num.numberId, id)
          _ <- OutboxRepo.append(
            OutboxEvent(
              UUID.randomUUID(),
              "document.issued",
              1,
              "document",
              id,
              id.toString,
              None,
              None,
              None,
              Json.obj(
                "document_id"      -> id.toString.asJson,
                "document_type"    -> docType.asJson,
                "formatted_number" -> num.formatted.asJson,
                "order_id"         -> orderId.map(_.toString).asJson,
                "dispatch_id"      -> dispatchId.map(_.toString).asJson,
                "total_amount"     -> total.asJson,
                "storage_uri"      -> storageUri.asJson,
                "content_sha256"   -> rendered.contentSha256.asJson,
                "locale"           -> locale.asJson,
                "jurisdiction"     -> jurisdiction.asJson
              ),
              Instant.now(),
              "service:documents"
            )
          )
        } yield DocumentResult(id, num.formatted, rendered.contentSha256, total.getOrElse(BigDecimal(0)), "finalised")
          .asRight[String]
    }
}

private final case class DispatchDocHead(
    orderId: UUID,
    entityId: UUID,
    jurisdiction: String,
    supplierName: String,
    shipTo: String,
    locale: String,
    dispatchNo: String,
    orderNo: String
)
private final case class CommercialHead(
    orderId: UUID,
    entityId: UUID,
    jurisdiction: String,
    supplierName: String,
    shipTo: String,
    locale: String,
    dispatchNo: String,
    orderNo: String,
    currency: String,
    incoterms: String
)
