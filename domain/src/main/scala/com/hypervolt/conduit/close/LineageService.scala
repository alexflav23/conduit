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
          (transfers(orderId), events(orderId), document(orderInvoiceId)).mapN { (tx, evs, doc) =>
            Json
              .obj(
                "order_invoice_id" -> orderInvoiceId.toString.asJson,
                "invoice_no"       -> invoiceNo.asJson,
                "total_inc_vat"    -> total.asJson,
                "ledger_transfers" -> tx.asJson,
                "events" -> evs.map {
                  case (t, id) => Json.obj("type" -> t.asJson, "event_id" -> id.toString.asJson)
                }.asJson,
                "document" -> doc.getOrElse(Json.Null)
              )
              .some
          }
      }
      .transact(xa)

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
