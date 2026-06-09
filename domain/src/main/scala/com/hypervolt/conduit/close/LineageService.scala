package com.hypervolt.conduit.close

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Auditability lineage (doc 14 §5.1/§6): a reported figure drills figure → order_invoice → ledger transfers →
// events → the issued document. Every link is a real FK / recorded id, so the chain is reconstructable, not
// asserted. This backs the Auditability Center's lineage explorer.
final class LineageService[F[_]: cats.effect.Async](xa: Transactor[F]) {

  def forInvoice(orderInvoiceId: UUID): F[Option[Json]] =
    head(orderInvoiceId)
      .flatMap {
        case None => none[Json].pure[ConnectionIO]
        case Some((orderId, invoiceNo, total)) =>
          (transfers(orderId), events(orderId), document(orderInvoiceId), contractualSourcesC(orderId)).mapN {
            (tx, evs, doc, sources) =>
              Json
                .obj(
                  "order_invoice_id" -> orderInvoiceId.toString.asJson,
                  "invoice_no"       -> invoiceNo.asJson,
                  "total_inc_vat"    -> total.asJson,
                  "ledger_transfers" -> tx.asJson,
                  "events" -> evs.map {
                    case (t, id) => Json.obj("type" -> t.asJson, "event_id" -> id.toString.asJson)
                  }.asJson,
                  "document"            -> doc.getOrElse(Json.Null),
                  "contractual_sources" -> sources
                )
                .some
          }
      }
      .transact(xa)

  // doc 25 §4.4 — the CONTRACTUAL sources of a revenue figure: the order's source customer PO (the document the
  // order was created from), and, per priced line, the governed price agreement with the signed contract +
  // schedules its tiers were entered from. Every link is a recorded id — the chain is reconstructable, not asserted.
  def contractualSources(orderId: UUID): F[Json] = contractualSourcesC(orderId).transact(xa)

  private def contractualSourcesC(orderId: UUID): ConnectionIO[Json] =
    (orderProvenance(orderId), attachmentsFor("order", orderId), lineAgreements(orderId)).mapN {
      (prov, orderDocs, agreements) =>
        Json.obj(
          "order_id"           -> orderId.toString.asJson,
          "customer_po_number" -> prov.flatMap(_._1).asJson,
          "source_attachment"  -> prov.flatMap(_._2).map(_.toString).asJson,
          "order_documents"    -> orderDocs.asJson,
          "price_agreements"   -> agreements.asJson
        )
    }

  private def orderProvenance(orderId: UUID): ConnectionIO[Option[(Option[String], Option[UUID])]] =
    sql"""SELECT customer_po_number, source_attachment_id FROM "order" WHERE id = $orderId"""
      .query[(Option[String], Option[UUID])]
      .option

  // The distinct agreements the order's lines priced from, each with its contract documents attached.
  private def lineAgreements(orderId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT DISTINCT pa.id, pa.name, pa.valid_from, pa.valid_to, pa.status
          FROM order_line ol JOIN price_agreement pa ON pa.id = ol.price_agreement_id
          WHERE ol.order_id = $orderId"""
      .query[(UUID, String, java.time.Instant, Option[java.time.Instant], String)]
      .to[List]
      .flatMap(_.traverse {
        case (id, name, from, to, status) =>
          attachmentsFor("price_agreement", id).map(docs =>
            Json.obj(
              "agreement_id" -> id.toString.asJson,
              "name"         -> name.asJson,
              "valid_from"   -> from.toString.asJson,
              "valid_to"     -> to.map(_.toString).asJson,
              "status"       -> status.asJson,
              "documents"    -> docs.asJson
            )
          )
      })

  private def attachmentsFor(subjectType: String, subjectId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT id, kind, filename, external_ref, content_sha256, storage_uri
          FROM document_attachment WHERE subject_type = $subjectType AND subject_id = $subjectId
          ORDER BY received_at"""
      .query[(UUID, String, String, Option[String], String, String)]
      .to[List]
      .map(_.map {
        case (id, kind, fn, ref, sha, uri) =>
          Json.obj(
            "attachment_id"  -> id.toString.asJson,
            "kind"           -> kind.asJson,
            "filename"       -> fn.asJson,
            "external_ref"   -> ref.asJson,
            "content_sha256" -> sha.asJson,
            "storage_uri"    -> uri.asJson
          )
      })

  private def head(invId: UUID): ConnectionIO[Option[(UUID, String, BigDecimal)]] =
    sql"""SELECT order_id, invoice_no, total_inc_vat FROM order_invoice WHERE id=$invId"""
      .query[(UUID, String, BigDecimal)]
      .option

  // The ledger transfer ids that posted this order's revenue (proof the figure is on the immutable ledger).
  private def transfers(orderId: UUID): ConnectionIO[List[String]] =
    sql"""SELECT ar_transfer_id::text, vat_transfer_id::text, cogs_transfer_id::text
          FROM revenue_recognition WHERE order_id=$orderId"""
      .query[(Option[String], Option[String], Option[String])]
      .to[List]
      .map(_.flatMap { case (a, v, c) => List(a, v, c).flatten })

  private def events(orderId: UUID): ConnectionIO[List[(String, UUID)]] =
    sql"""SELECT event_type, event_id FROM outbox_event WHERE aggregate_id=$orderId ORDER BY occurred_at"""
      .query[(String, UUID)]
      .to[List]

  private def document(invId: UUID): ConnectionIO[Option[Json]] =
    sql"""SELECT id, formatted_number, content_sha256, storage_uri FROM document WHERE order_invoice_id=$invId AND status='finalised'"""
      .query[(UUID, Option[String], Option[String], Option[String])]
      .option
      .map(_.map {
        case (id, no, sha, uri) =>
          Json.obj(
            "document_id"      -> id.toString.asJson,
            "formatted_number" -> no.asJson,
            "content_sha256"   -> sha.asJson,
            "storage_uri"      -> uri.asJson
          )
      })
}
