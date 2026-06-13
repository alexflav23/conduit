package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json

// The Xero accounting connector (spec doc 33 §4). Pull Invoices / Contacts / Payments incrementally on the
// `UpdatedDateUTC` watermark; each row becomes an IngestRecord the shared handler reconciles against Conduit's
// own AR (Conduit is the deriver, Xero the cross-check — spec/18 §0). HTTP lives behind XeroApi so the parse +
// cursor logic is testable without live OAuth; the live client (ember + the OAuth2 token from
// `<env>/conduit/xero/*`) is one thin implementation of that seam.
trait XeroApi[F[_]] {
  // GET an endpoint, optionally only rows modified since the cursor (Xero's If-Modified-Since). Returns the
  // decoded JSON body. Paging (Xero `page` param) is the live client's concern; it returns one page per call.
  def get(endpoint: String, modifiedSince: Option[String]): F[Json]
}

object XeroConnector {
  // dataset → (endpoint, JSON array key, id field). UpdatedDateUTC is the shared watermark.
  private[ingest] val datasetSpec: Map[String, (String, String, String)] = Map(
    "invoices" -> ("Invoices", "Invoices", "InvoiceID"),
    "contacts" -> ("Contacts", "Contacts", "ContactID"),
    "payments" -> ("Payments", "Payments", "PaymentID")
  )
  private[ingest] val cursorKey = "UpdatedDateUTC"
  private[ingest] val pageSize  = 100 // Xero's page size; a full page ⇒ more may remain.
}

final class XeroConnector[F[_]: Async](api: XeroApi[F]) extends IngestConnector[F] {
  import XeroConnector._

  def source: String         = "xero"
  def datasets: List[String] = datasetSpec.keys.toList.sorted

  def pullSince(dataset: String, cursor: Option[SyncCursor]): F[IngestBatch] =
    datasetSpec.get(dataset) match {
      case None => Async[F].raiseError(new IllegalArgumentException(s"unknown xero dataset: $dataset"))
      case Some((endpoint, arrayKey, idKey)) =>
        api.get(endpoint, cursor.map(_.value)).map(parse(dataset, arrayKey, idKey, _))
    }

  private def parse(dataset: String, arrayKey: String, idKey: String, body: Json): IngestBatch = {
    val rows = body.hcursor.downField(arrayKey).values.toList.flatten
    val records = rows.flatMap { row =>
      row.hcursor.get[String](idKey).toOption.map(id => IngestRecord(dataset, id, row))
    }
    // advance to the latest UpdatedDateUTC in this page (lexical max works for ISO; the live client normalises
    // Xero's /Date(ms)/ form to ISO via the Accept header). None when the page is empty ⇒ the runner holds the cursor.
    val nextCursor = rows.flatMap(_.hcursor.get[String](cursorKey).toOption).maxOption.map(SyncCursor.apply)
    IngestBatch(records, nextCursor, complete = rows.size < pageSize)
  }
}
