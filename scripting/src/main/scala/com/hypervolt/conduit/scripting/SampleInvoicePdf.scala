package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import com.hypervolt.conduit.document.FopDocumentRenderer
import io.circe.Json
import io.circe.syntax._
import java.nio.file.Files
import java.nio.file.Paths

// Renders a sample GB/en invoice through the real Apache FOP engine and writes it to target/sample-invoice.pdf,
// so the rendered legal artefact can be eyeballed (the on-screen verification step). Same model the integration
// test uses; the printed sha must match across runs (re-performability).
object SampleInvoicePdf extends IOApp.Simple {

  private val model: Json =
    Json.obj(
      "supplier_name" -> "Hypervolt UK Ltd".asJson,
      "payer_name"    -> "Doc Customer Ltd".asJson,
      "locale"        -> "en".asJson,
      "jurisdiction"  -> "GB".asJson,
      "currency"      -> "GBP".asJson,
      "lines" -> Json.arr(
        Json.obj(
          "description" -> "Home 3 Charger".asJson,
          "sku"         -> "HOME3-V3".asJson,
          "qty"         -> 2.asJson,
          "unit_price"  -> "500.00".asJson,
          "vat"         -> "200.00".asJson,
          "line_total"  -> "1000.00".asJson
        ),
        Json.obj(
          "description" -> "Tethered Cable 5m".asJson,
          "sku"         -> "CABLE-5M".asJson,
          "qty"         -> 2.asJson,
          "unit_price"  -> "0.00".asJson,
          "vat"         -> "0.00".asJson,
          "line_total"  -> "0.00".asJson
        )
      ),
      "subtotal" -> "1000.00".asJson,
      "vat"      -> "200.00".asJson,
      "total"    -> "1200.00".asJson
    )

  // A credit note that invalidates an invoice (doc 13 §void): no line table, carries corrects + reason.
  private val creditModel: Json =
    Json.obj(
      "supplier_name"   -> "Hypervolt UK Ltd".asJson,
      "payer_name"      -> "Doc Customer Ltd".asJson,
      "locale"          -> "en".asJson,
      "jurisdiction"    -> "GB".asJson,
      "currency"        -> "GBP".asJson,
      "corrects_number" -> "HV-UK-INV-2026-000001".asJson,
      "reason"          -> "Wrong customer on the PO".asJson,
      "total"           -> "1200.00".asJson
    )

  // A volume-only packing list: a generic columns/rows table, ship-to + dispatch meta, no money.
  private val packingModel: Json =
    Json.obj(
      "title"         -> "PACKING LIST".asJson,
      "supplier_name" -> "Hypervolt UK Ltd".asJson,
      "payer_name"    -> "Doc Customer Ltd".asJson,
      "payer_label"   -> "Ship to".asJson,
      "locale"        -> "en".asJson,
      "meta"          -> Json.arr(Json.arr("Dispatch".asJson, "DSP-FLOW".asJson), Json.arr("Order".asJson, "ORD-FLOW".asJson)),
      "columns"       -> Json.arr("Description".asJson, "SKU".asJson, "Qty".asJson, "Serials".asJson),
      "rows" -> Json.arr(
        Json.arr("Home 3 Charger".asJson, "HOME3-V3".asJson, "2".asJson, "SER-0001, SER-0002".asJson),
        Json.arr("Tethered Cable 5m".asJson, "CABLE-5M".asJson, "2".asJson, "".asJson)
      )
    )

  def run: IO[Unit] = {
    val renderer = new FopDocumentRenderer[IO]
    renderer
      .render("GB/en invoice template v1", model)
      .flatMap(d =>
        IO.blocking(Files.write(Paths.get("target/sample-invoice.pdf"), d.bytes)) *>
          IO.println(s"wrote target/sample-invoice.pdf  pages=${d.pageCount}  sha256=${d.contentSha256}")
      ) *>
      renderer
        .render("GB/en credit_note template v1", creditModel)
        .flatMap(d =>
          IO.blocking(Files.write(Paths.get("target/sample-credit-note.pdf"), d.bytes)) *>
            IO.println(s"wrote target/sample-credit-note.pdf  pages=${d.pageCount}  sha256=${d.contentSha256}")
        ) *>
      renderer
        .render("GB/en packing_list template v1", packingModel)
        .flatMap(d =>
          IO.blocking(Files.write(Paths.get("target/sample-packing-list.pdf"), d.bytes)) *>
            IO.println(s"wrote target/sample-packing-list.pdf  pages=${d.pageCount}  sha256=${d.contentSha256}")
        )
  }
}
