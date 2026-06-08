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
    locale: String
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
        (lines(h.orderId), template(h.jurisdiction, h.locale)).tupled.map {
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
    sql"""SELECT o.id, o.entity_id, e.jurisdiction, o.bill_to_party_id, COALESCE(p.legal_name, p.display_name),
                 e.name, o.txn_currency, i.total_ex_vat, i.vat_total, i.total_inc_vat,
                 COALESCE(bp.invoice_locale, 'en')
          FROM order_invoice i
            JOIN "order" o ON o.id = i.order_id
            JOIN entity e ON e.id = o.entity_id
            JOIN party p ON p.id = o.bill_to_party_id
            LEFT JOIN billing_profile bp ON bp.party_id = o.bill_to_party_id
          WHERE i.id = $invId"""
      .query[InvHead]
      .option

  private def lines(orderId: UUID): ConnectionIO[List[InvLine]] =
    sql"""SELECT COALESCE(f.name, v.sku), v.sku, ol.qty, ol.unit_price_ex_vat, ol.discount_pct, ol.vat_amount
          FROM order_line ol JOIN product_variant v ON v.id = ol.product_variant_id
            LEFT JOIN product_family f ON f.id = v.family_id
          WHERE ol.order_id = $orderId ORDER BY ol.id"""
      .query[InvLine]
      .to[List]

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
      Instant.now()
    )
}
