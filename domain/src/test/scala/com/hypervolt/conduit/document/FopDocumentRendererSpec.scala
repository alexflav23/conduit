package com.hypervolt.conduit.document

import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import weaver.SimpleIOSuite

// M13-Docs.2 — the real Apache FOP engine (doc 17 §4.4). The legal artefact must be a true PDF, and — for the
// content_sha256 re-performability control (doc 14 §5.1) — rendering the frozen model must be byte-deterministic:
// same (body, model) → same hash, every time. Different inputs must change the hash (tamper-evidence).
object FopDocumentRendererSpec extends SimpleIOSuite {

  private val renderer = new FopDocumentRenderer[IO]

  private def model(total: String): Json =
    Json.obj(
      "supplier_name" -> "HV UK".asJson,
      "payer_name"    -> "Doc Customer Ltd".asJson,
      "locale"        -> "en".asJson,
      "jurisdiction"  -> "GB".asJson,
      "currency"      -> "GBP".asJson,
      "lines" -> Json.arr(
        Json.obj(
          "description" -> "Home 3".asJson,
          "sku"         -> "HOME3-V3".asJson,
          "qty"         -> 2.asJson,
          "unit_price"  -> "500.00".asJson,
          "vat"         -> "200.00".asJson,
          "line_total"  -> "1000.00".asJson
        )
      ),
      "subtotal" -> "1000.00".asJson,
      "vat"      -> "200.00".asJson,
      "total"    -> total.asJson
    )

  private val body = "GB/en invoice template v1"

  test("renders a real PDF") {
    renderer.render(body, model("1200.00")).map { d =>
      val header = new String(d.bytes.take(5), "US-ASCII")
      expect(header == "%PDF-") and expect(d.pageCount >= 1) and expect(d.contentSha256.length == 64)
    }
  }

  test("byte-deterministic: re-rendering the same model yields the same sha (re-performability)") {
    (renderer.render(body, model("1200.00")), renderer.render(body, model("1200.00"))).parTupled.map {
      case (a, b) => expect(a.contentSha256 == b.contentSha256) and expect(a.bytes.sameElements(b.bytes))
    }
  }

  // M13-Docs.8 — multi-locale: the body font follows the document locale so CJK/Thai glyphs embed from the Noto
  // fonts registered by fop.xconf; Latin markets use base-14 Helvetica.
  pureTest("locale → font family: CJK + Thai map to their Noto families; everything else to Helvetica") {
    expect(FopDocumentRenderer.fontFamily("th") == "Noto Sans Thai") and
      expect(FopDocumentRenderer.fontFamily("zh-CN") == "Noto Sans CJK SC") and
      expect(FopDocumentRenderer.fontFamily("ja") == "Noto Sans CJK JP") and
      expect(FopDocumentRenderer.fontFamily("ko") == "Noto Sans CJK KR") and
      expect(FopDocumentRenderer.fontFamily("en") == "Helvetica")
  }

  pureTest("a Thai-locale document requests the Thai font family in the FO") {
    val fo = FopDocumentRenderer.foDocument(model("1200.00").deepMerge(Json.obj("locale" -> "th".asJson)))
    expect(fo.contains("""font-family="Noto Sans Thai"""")) and expect(fo.contains("""xml:lang="th""""))
  }

  test("renders a real PDF for a Thai-locale document with non-Latin content (graceful when the font is absent)") {
    val thai = model("1200.00").deepMerge(
      Json.obj("locale" -> "th".asJson, "supplier_name" -> "ไฮเปอร์โวลต์".asJson, "title" -> "ใบแจ้งหนี้".asJson)
    )
    renderer.render("TH/th invoice template v1", thai).map { d =>
      expect(new String(d.bytes.take(5), "US-ASCII") == "%PDF-") and expect(d.pageCount >= 1)
    }
  }

  test("tamper-evident: a different total changes the sha; a different template body changes the sha") {
    (
      renderer.render(body, model("1200.00")),
      renderer.render(body, model("9999.00")),
      renderer.render("GB/en invoice template v2", model("1200.00"))
    ).parTupled.map {
      case (a, b, c) =>
        expect(a.contentSha256 != b.contentSha256) and expect(a.contentSha256 != c.contentSha256)
    }
  }
}
