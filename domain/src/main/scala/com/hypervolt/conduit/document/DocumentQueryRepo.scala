package com.hypervolt.conduit.document

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Read-side for the documents surface (doc 17 §6/§9). Plain projections of the WORM `document` row — the money
// fields (total_amount/currency) are commercial-layer and get walled at the route via Projection. The storage
// URI is resolved separately (download path) and never leaks into the listing JSON.
object DocumentQueryRepo {

  private val cols =
    fr"""d.id, d.document_type, d.formatted_number, d.order_id, d.order_invoice_id, d.currency, d.total_amount,
         d.status, d.content_sha256, d.issued_at"""

  private def toJson(
      r: (
          UUID,
          String,
          Option[String],
          Option[UUID],
          Option[UUID],
          Option[String],
          Option[BigDecimal],
          String,
          Option[String],
          Option[java.time.Instant]
      )
  ): Json =
    Json.obj(
      "id"               -> r._1.toString.asJson,
      "document_type"    -> r._2.asJson,
      "formatted_number" -> r._3.asJson,
      "order_id"         -> r._4.map(_.toString).asJson,
      "order_invoice_id" -> r._5.map(_.toString).asJson,
      "currency"         -> r._6.asJson,
      "total_amount"     -> r._7.asJson,
      "status"           -> r._8.asJson,
      "content_sha256"   -> r._9.asJson,
      "issued_at"        -> r._10.map(_.toString).asJson
    )

  private type Row =
    (
        UUID,
        String,
        Option[String],
        Option[UUID],
        Option[UUID],
        Option[String],
        Option[BigDecimal],
        String,
        Option[String],
        Option[java.time.Instant]
    )

  def listForOrder(orderId: UUID): ConnectionIO[List[Json]] =
    (fr"SELECT" ++ cols ++ fr"FROM document d WHERE d.order_id = $orderId ORDER BY d.issued_at DESC NULLS LAST, d.created_at DESC")
      .query[Row]
      .to[List]
      .map(_.map(toJson))

  def listForInvoiceNo(invoiceNo: String): ConnectionIO[List[Json]] =
    (fr"SELECT" ++ cols ++ fr"""FROM document d JOIN order_invoice i ON i.id = d.order_invoice_id
                                WHERE i.invoice_no = $invoiceNo ORDER BY d.issued_at DESC NULLS LAST""")
      .query[Row]
      .to[List]
      .map(_.map(toJson))

  def byId(id: UUID): ConnectionIO[Option[Json]] =
    (fr"SELECT" ++ cols ++ fr"FROM document d WHERE d.id = $id").query[Row].option.map(_.map(toJson))

  // The download path: the artefact's storage URI + its declared content type, only when finalised.
  def storageRef(id: UUID): ConnectionIO[Option[(String, String)]] =
    sql"SELECT storage_uri, document_type FROM document WHERE id = $id AND status = 'finalised' AND storage_uri IS NOT NULL"
      .query[(String, String)]
      .option
}
