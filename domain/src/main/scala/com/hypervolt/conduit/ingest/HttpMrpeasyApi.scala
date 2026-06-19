package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s.Header
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.Client
import org.typelevel.ci.CIString

// The live MRPeasy REST implementation of the MrpeasyApi seam (S2.2, spec 37 §2). MRPeasy is the inventory +
// landed-cost + serial authority: a customer order flows to a shipment, and each shipment line logs the SERIAL
// NUMBERS dispatched — which the shared mrpShipment handler maps to serial_unit (genealogy). This NORMALIZES the
// live REST shape (cust_ord_id/products/status_txt; shipment products[].serials[].serial) into the canonical
// record the boot handlers consume, stamping a `modified` watermark and `id` so the connector is unchanged.
//
// The REST list endpoints ignore `start` and cap at 100, so a pull returns the RECENT WINDOW (newest activity) —
// exactly the live delta shadow needs; the historical bulk is the committed snapshot, and IngestSink dedups the
// overlap. The connector advances on the synthetic `modified`; warm pulls return only rows newer than the cursor.
final class HttpMrpeasyApi[F[_]: Async](client: Client[F], accessKey: String, apiKey: String, baseUrl: String)
    extends MrpeasyApi[F] {

  def get(endpoint: String, modifiedSince: Option[String]): F[Json] = {
    val normalize: Json => Option[Json] = endpoint match {
      case "customer-orders" => normOrder
      case "shipments"       => normShipment
      case "purchase-orders" => normPo
      case other =>
        return Async[F].raiseError(
          new IllegalArgumentException(
            s"mrpeasy live pull not wired for '$other' (S2.2: customer-orders, shipments, purchase-orders)"
          )
        )
    }
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/$endpoint?limit=100"))
      .withHeaders(Header.Raw(CIString("access_key"), accessKey), Header.Raw(CIString("api_key"), apiKey))
    client.expect[Json](req)(jsonOf[F, Json]).map(raw => repackage(raw, normalize, modifiedSince))
  }

  // Normalize each raw row, keep only those modified after the cursor, sort ascending — the watermark-paginated
  // delta the connector expects (the connector reads top-level `modified` + `id`).
  private def repackage(raw: Json, normalize: Json => Option[Json], since: Option[String]): Json = {
    val rows   = raw.asArray.map(_.toList).getOrElse(Nil)
    val sinceL = since.flatMap(_.toLongOption).getOrElse(Long.MinValue)
    val out = rows
      .flatMap(normalize)
      .filter(r => watermark(r) > sinceL)
      .sortBy(watermark)
    Json.fromValues(out)
  }

  private def watermark(row: Json): Long =
    row.hcursor.get[String]("modified").toOption.flatMap(_.toLongOption).getOrElse(Long.MinValue)

  // customer-orders: cust_ord_id→id, products→lines{item_code,qty,price,total}, status_txt→status.
  private val normOrder: Json => Option[Json] = row => {
    val c = row.hcursor
    longStr(c, "cust_ord_id").map { id =>
      val products = c.downField("products").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val lines = products.toList.map { p =>
        val pc = p.hcursor
        Json.obj(
          "item_code" -> str(pc, "item_code"),
          "qty"       -> numJson(pc, "quantity"),
          "price"     -> numJson(pc, "item_price"),
          "total"     -> numJson(pc, "total_price")
        )
      }
      Json.obj(
        "id"              -> id.asJson,
        "code"            -> str(c, "code"),
        "customer_name"   -> str(c, "customer_name"),
        "created"         -> str(c, "created"),
        "status"          -> str(c, "status_txt"),
        "total_price"     -> numJson(c, "total_price"),
        "total_price_cur" -> numJson(c, "total_price_cur"),
        "modified"        -> maxTs(c, "created", "delivery_date", "actual_delivery_date").asJson,
        "lines"           -> Json.fromValues(lines)
      )
    }
  }

  // shipments: shipment_id→id, customer_order_code→order_code, products→lines{item_code,qty,serials[]} where
  // serials are the dispatched hex serial STRINGS the mrpShipment handler maps to serial_unit (genealogy).
  private val normShipment: Json => Option[Json] = row => {
    val c = row.hcursor
    longStr(c, "shipment_id").map { id =>
      val products = c.downField("products").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val lines = products.toList.map { p =>
        val pc        = p.hcursor
        val serials   = pc.downField("serials").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        val serialNos = serials.toList.flatMap(_.hcursor.get[String]("serial").toOption)
        Json.obj(
          "item_code" -> str(pc, "item_code"),
          "qty"       -> numJson(pc, "quantity_picked"),
          "serials"   -> Json.fromValues(serialNos.map(Json.fromString))
        )
      }
      Json.obj(
        "id"            -> id.asJson,
        "code"          -> str(c, "code"),
        "order_code"    -> str(c, "customer_order_code"),
        "rma_order_id"  -> longStr(c, "rma_order_id").map(Json.fromString).getOrElse(Json.Null),
        "created"       -> str(c, "created"),
        "delivery_date" -> str(c, "delivery_date"),
        "status"        -> str(c, "status_txt"),
        "modified"      -> maxTs(c, "created", "delivery_date").asJson,
        "lines"         -> Json.fromValues(lines)
      )
    }
  }

  // purchase-orders: pur_ord_id→id, vendor_title (supplier), products→lines{item_code,qty,unit_cost}. The PO the
  // shadow-mode reframe named — keeps the supply-in side (supplier + ordered cost) current.
  private val normPo: Json => Option[Json] = row => {
    val c = row.hcursor
    longStr(c, "pur_ord_id").map { id =>
      val products = c.downField("products").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val lines = products.toList.map { p =>
        val pc = p.hcursor
        Json.obj(
          "item_code" -> str(pc, "item_code"),
          "qty"       -> numJson(pc, "quantity"),
          "unit_cost" -> numJson(pc, "item_price")
        )
      }
      Json.obj(
        "id"            -> id.asJson,
        "code"          -> str(c, "code"),
        "vendor_title"  -> str(c, "vendor_title"),
        "status"        -> str(c, "status"),
        "total_price"   -> numJson(c, "total_price"),
        "currency_rate" -> numJson(c, "currency_rate"),
        "order_date"    -> str(c, "order_date"),
        "expected_date" -> str(c, "expected_date"),
        "modified"      -> maxTs(c, "created", "order_date", "arrival_date", "expected_date").asJson,
        "lines"         -> Json.fromValues(lines)
      )
    }
  }

  private def str(c: io.circe.ACursor, k: String): Json =
    c.get[String](k).toOption.filter(_.nonEmpty).fold(Json.Null)(Json.fromString)

  // a numeric field as JSON (MRPeasy returns numbers or numeric strings); preserves number where possible.
  private def numJson(c: io.circe.ACursor, k: String): Json =
    c.downField(k)
      .focus
      .flatMap(j =>
        j.asNumber.map(Json.fromJsonNumber).orElse(j.asString.flatMap(s => s.toDoubleOption).flatMap(Json.fromDouble))
      )
      .getOrElse(Json.Null)

  // an id field (number or string) → its string form.
  private def longStr(c: io.circe.ACursor, k: String): Option[String] =
    c.downField(k).focus.flatMap(j => j.asNumber.map(_.toString).orElse(j.asString)).filter(_.nonEmpty)

  // the most recent epoch-seconds across the given fields, as a string (the synthetic `modified` watermark).
  private def maxTs(c: io.circe.ACursor, keys: String*): String =
    keys.toList
      .flatMap(k => c.downField(k).focus.flatMap(j => j.asString.orElse(j.asNumber.map(_.toString))))
      .flatMap(_.toLongOption)
      .maxOption
      .getOrElse(0L)
      .toString
}
