package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json

// The HubSpot CRM connector (spec doc 33 §4). Pull deals / companies / contacts / line-items incrementally on
// the `hs_lastmodifieddate` watermark (epoch-ms, nested under `properties`), each row → an IngestRecord the
// shared handler maps to deal_snapshot / party. HTTP behind HubSpotApi so parse + cursor are testable without a
// live token; the live client (ember + the private-app token) is one thin implementation of the seam.
trait HubSpotApi[F[_]] {
  // GET a CRM-v3 object list, optionally only rows modified since the cursor (epoch-ms). One page per call.
  def get(objectType: String, modifiedSince: Option[String]): F[Json]
}

object HubSpotConnector {
  // dataset == the CRM object type; HubSpot v3 returns {results:[{id, properties:{...}}], paging:{next:{after}}}.
  private[ingest] val objectTypes = List("companies", "contacts", "deals", "line_items")
  private[ingest] val watermark   = "hs_lastmodifieddate"
  private[ingest] val pageSize    = 100

  // epoch-ms strings of equal length compare lexically, but be robust to width: compare numerically when possible.
  private[ingest] def maxWatermark(values: List[String]): Option[String] =
    values match {
      case Nil => None
      case _   => Some(values.maxBy(s => s.toLongOption.getOrElse(Long.MinValue)))
    }
}

final class HubSpotConnector[F[_]: Async](api: HubSpotApi[F]) extends IngestConnector[F] {
  import HubSpotConnector._

  def source: String         = "hubspot"
  def datasets: List[String] = objectTypes

  def pullSince(dataset: String, cursor: Option[SyncCursor]): F[IngestBatch] =
    if (!objectTypes.contains(dataset))
      Async[F].raiseError(new IllegalArgumentException(s"unknown hubspot object: $dataset"))
    else api.get(dataset, cursor.map(_.value)).map(parse(dataset, _))

  private def parse(dataset: String, body: Json): IngestBatch = {
    val rows = body.hcursor.downField("results").values.toList.flatten
    val records = rows.flatMap { row =>
      row.hcursor.get[String]("id").toOption.map(id => IngestRecord(dataset, id, row))
    }
    val next = maxWatermark(
      rows.flatMap(_.hcursor.downField("properties").get[String](watermark).toOption)
    ).map(SyncCursor.apply)
    IngestBatch(records, next, complete = rows.size < pageSize)
  }
}
