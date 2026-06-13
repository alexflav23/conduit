package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json

// The MRPeasy connector (spec doc 33 §4), replacing scripts/mrpeasy_scrape.py with the REST API. Pulls customer
// orders / shipments / stock-lots / POs / articles incrementally on the `modified` unix watermark — MRPeasy is
// the inventory + landed-cost authority (spec/18 §0), so these land as order/dispatch/lot_batch/serial/po. HTTP
// behind MrpeasyApi so parse + cursor are testable without an API key; MRPeasy returns a top-level JSON array.
trait MrpeasyApi[F[_]] {
  def get(endpoint: String, modifiedSince: Option[String]): F[Json]
}

object MrpeasyConnector {
  // dataset → (REST endpoint, id field). MRPeasy ids + `modified` are numeric; the live client maps real fields.
  private[ingest] val datasetSpec: Map[String, (String, String)] = Map(
    "customer_orders" -> ("customer-orders", "id"),
    "shipments"       -> ("shipments", "id"),
    "stock_lots"      -> ("stock-lots", "id"),
    "purchase_orders" -> ("purchase-orders", "id"),
    "articles"        -> ("articles", "id")
  )
  private[ingest] val watermark = "modified"
  private[ingest] val pageSize  = 100

  // accept either a top-level array or {items:[...]} / {data:[...]} wrappers.
  private[ingest] def rowsOf(body: Json): List[Json] =
    body.asArray
      .map(_.toList)
      .orElse(body.hcursor.downField("items").values.map(_.toList))
      .orElse(body.hcursor.downField("data").values.map(_.toList))
      .getOrElse(Nil)

  // `modified` may arrive as a JSON number (unix secs) or a numeric string; compare numerically either way.
  private[ingest] def watermarkOf(row: Json): Option[String] = {
    val c = row.hcursor.downField(watermark)
    c.as[Long].toOption.map(_.toString).orElse(c.as[String].toOption)
  }
  private[ingest] def maxWatermark(values: List[String]): Option[String] =
    if (values.isEmpty) None else Some(values.maxBy(_.toLongOption.getOrElse(Long.MinValue)))
}

final class MrpeasyConnector[F[_]: Async](api: MrpeasyApi[F]) extends IngestConnector[F] {
  import MrpeasyConnector._

  def source: String         = "mrpeasy"
  def datasets: List[String] = datasetSpec.keys.toList.sorted

  def pullSince(dataset: String, cursor: Option[SyncCursor]): F[IngestBatch] =
    datasetSpec.get(dataset) match {
      case None => Async[F].raiseError(new IllegalArgumentException(s"unknown mrpeasy dataset: $dataset"))
      case Some((endpoint, idKey)) =>
        api.get(endpoint, cursor.map(_.value)).map(parse(dataset, idKey, _))
    }

  private def parse(dataset: String, idKey: String, body: Json): IngestBatch = {
    val rows = rowsOf(body)
    val records = rows.flatMap { row =>
      // id may be a number or string in MRPeasy; accept both.
      val id = row.hcursor
        .downField(idKey)
        .as[Long]
        .toOption
        .map(_.toString)
        .orElse(row.hcursor.get[String](idKey).toOption)
      id.map(i => IngestRecord(dataset, i, row))
    }
    val next = maxWatermark(rows.flatMap(watermarkOf)).map(SyncCursor.apply)
    IngestBatch(records, next, complete = rows.size < pageSize)
  }
}
