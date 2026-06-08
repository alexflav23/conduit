package com.hypervolt.conduit.document

import cats.effect.Sync
import io.circe.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.Date
import javax.xml.transform.TransformerFactory
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.stream.StreamSource
import org.apache.fop.apps.FopFactory
import org.apache.xmlgraphics.util.MimeConstants

// The real legal-document PDF engine (doc 17 §4.4): Apache FOP renders XSL-FO → PDF. Chosen over PDFBox because
// the layout is markup-driven (not imperative draw calls), fonts (incl. CJK + Thai) embed declaratively via the
// FO font-family, and — with a FIXED creation date + producer — the output is byte-deterministic, so the
// content_sha256 re-performability control (doc 14 §5.1) holds: re-rendering the frozen render-model yields the
// same hash. The template body participates in the bytes via a digest carried in the PDF keywords, so a template
// change is sha-visible even though the governed layout itself lives in code.
final class FopDocumentRenderer[F[_]: Sync] extends DocumentRenderer[F] {

  def render(body: String, model: Json): F[RenderedDoc] =
    Sync[F].delay {
      val fo             = FopDocumentRenderer.foDocument(model)
      val (bytes, pages) = FopDocumentRenderer.transform(fo, DocumentRenderer.sha256(body.getBytes("UTF-8")))
      RenderedDoc(bytes, DocumentRenderer.sha256(bytes), pages)
    }
}

object FopDocumentRenderer {

  // One factory for the JVM; FOUserAgent is per-render so concurrent renders don't share mutable date/producer.
  private val factory = FopFactory.newInstance(new File(".").toURI)
  private val epoch   = new Date(0L)

  private def transform(fo: String, templateDigest: String): (Array[Byte], Int) = {
    val out = new ByteArrayOutputStream()
    val ua  = factory.newFOUserAgent()
    ua.setCreationDate(epoch)
    ua.setProducer("Conduit Document Engine")
    ua.setAuthor("Hypervolt")
    ua.setKeywords(templateDigest)
    val fop = factory.newFop(MimeConstants.MIME_PDF, ua, out)
    val tf  = TransformerFactory.newInstance().newTransformer()
    tf.transform(new StreamSource(new StringReader(fo)), new SAXResult(fop.getDefaultHandler))
    (out.toByteArray, fop.getResults.getPageCount)
  }

  private def esc(x: String): String =
    x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def field(model: Json, name: String): String =
    model.hcursor.downField(name).focus.map(j => j.asString.getOrElse(j.toString)).getOrElse("")

  private def lineRows(model: Json): String =
    model.hcursor
      .downField("lines")
      .values
      .getOrElse(Nil)
      .map { l =>
        val c                    = l.hcursor
        def f(n: String): String = c.downField(n).focus.map(j => j.asString.getOrElse(j.toString)).getOrElse("")
        s"""        <fo:table-row>
           |          <fo:table-cell padding="3pt"><fo:block>${esc(f("description"))}</fo:block></fo:table-cell>
           |          <fo:table-cell padding="3pt"><fo:block>${esc(f("sku"))}</fo:block></fo:table-cell>
           |          <fo:table-cell padding="3pt" text-align="right"><fo:block>${esc(f("qty"))}</fo:block></fo:table-cell>
           |          <fo:table-cell padding="3pt" text-align="right"><fo:block>${esc(f("unit_price"))}</fo:block></fo:table-cell>
           |          <fo:table-cell padding="3pt" text-align="right"><fo:block>${esc(f("line_total"))}</fo:block></fo:table-cell>
           |        </fo:table-row>""".stripMargin
      }
      .mkString("\n")

  // Columns whose values are amounts/quantities → right-aligned in the generic table.
  private val numericCols =
    Set("qty", "unit", "amount", "total", "value", "outstanding", "duty", "vat", "price", "unit price", "line total")
  private def isNum(h: String): Boolean = numericCols.contains(h.trim.toLowerCase)

  // A generic table from model.columns (header labels) + model.rows (arrays of cells) — used by document types
  // that aren't invoice-shaped (packing list, statement, commercial invoice). Empty when absent.
  private def genericTable(model: Json): String = {
    val cols = model.hcursor.downField("columns").values.getOrElse(Nil).toList.flatMap(_.asString)
    val rows = model.hcursor.downField("rows").values.getOrElse(Nil).toList
    if (cols.isEmpty) ""
    else {
      val colDefs =
        cols.map(_ => """        <fo:table-column column-width="proportional-column-width(1)"/>""").mkString("\n")
      val header = cols
        .map(h =>
          s"""<fo:table-cell padding="3pt" text-align="${if (isNum(h)) "right" else "left"}"><fo:block>${esc(
            h
          )}</fo:block></fo:table-cell>"""
        )
        .mkString
      val body = rows
        .map { r =>
          val cells = r.asArray.getOrElse(Vector.empty)
          val tds = cols.indices.map { i =>
            val v = cells.lift(i).map(j => j.asString.getOrElse(j.toString)).getOrElse("")
            s"""<fo:table-cell padding="3pt" text-align="${if (isNum(cols(i))) "right" else "left"}"><fo:block>${esc(
              v
            )}</fo:block></fo:table-cell>"""
          }.mkString
          s"""        <fo:table-row>$tds</fo:table-row>"""
        }
        .mkString("\n")
      s"""      <fo:table table-layout="fixed" width="100%" space-after="12pt" border-top="0.5pt solid black" border-bottom="0.5pt solid black">
         |$colDefs
         |        <fo:table-header font-weight="bold"><fo:table-row>$header</fo:table-row></fo:table-header>
         |        <fo:table-body>
         |$body
         |        </fo:table-body>
         |      </fo:table>""".stripMargin
    }
  }

  // model.meta = [[label, value], …] → "label: value" blocks (incoterms, country of origin, account period, …).
  private def metaBlocks(model: Json): String =
    model.hcursor
      .downField("meta")
      .values
      .getOrElse(Nil)
      .toList
      .flatMap { m =>
        val a = m.asArray.getOrElse(Vector.empty)
        if (a.size >= 2)
          Some(s"""      <fo:block space-after="2pt">${esc(a(0).asString.getOrElse(""))}: ${esc(
            a(1).asString.getOrElse(a(1).toString)
          )}</fo:block>""")
        else None
      }
      .mkString("\n")

  // model.notes = ["…"] → small print (legal clauses, "proforma — not a tax invoice", …).
  private def noteBlocks(model: Json): String =
    model.hcursor
      .downField("notes")
      .values
      .getOrElse(Nil)
      .toList
      .flatMap(_.asString)
      .map(n => s"""      <fo:block font-size="8pt" space-before="2pt">${esc(n)}</fo:block>""")
      .mkString("\n")

  // The governed A4 document layout (doc 17 §5). Pure function of the render model → reproducible FO → reproducible
  // PDF. Generic enough for every document type: an explicit title, supplier/payer, a meta block, an invoice-style
  // line table OR a generic columns/rows table, totals, and notes. Latin base-14 fonts for year-1 GB/en; a CJK/Thai
  // font_stack drops in via fo:font-family + a fop.xconf when those markets come online (doc 02 §A roadmap).
  def foDocument(model: Json): String = {
    val ccy        = esc(field(model, "currency"))
    val corrects   = field(model, "corrects_number")
    val reason     = field(model, "reason")
    val titleField = field(model, "title")
    val title      = if (titleField.nonEmpty) titleField else if (corrects.nonEmpty) "CREDIT NOTE" else "INVOICE"
    val payerLabel = { val l = field(model, "payer_label"); if (l.nonEmpty) l else "Bill to" }
    val generic = genericTable(model)
    val rows    = if (generic.nonEmpty) "" else lineRows(model)
    // The generic table wins when present; otherwise the invoice-style line table (only valid FO when it has rows).
    val tableBlock =
      if (generic.nonEmpty) generic
      else if (rows.trim.isEmpty) ""
      else
        s"""      <fo:table table-layout="fixed" width="100%" space-after="12pt" border-top="0.5pt solid black" border-bottom="0.5pt solid black">
           |        <fo:table-column column-width="40%"/>
           |        <fo:table-column column-width="20%"/>
           |        <fo:table-column column-width="10%"/>
           |        <fo:table-column column-width="15%"/>
           |        <fo:table-column column-width="15%"/>
           |        <fo:table-header font-weight="bold">
           |          <fo:table-row>
           |            <fo:table-cell padding="3pt"><fo:block>Description</fo:block></fo:table-cell>
           |            <fo:table-cell padding="3pt"><fo:block>SKU</fo:block></fo:table-cell>
           |            <fo:table-cell padding="3pt" text-align="right"><fo:block>Qty</fo:block></fo:table-cell>
           |            <fo:table-cell padding="3pt" text-align="right"><fo:block>Unit</fo:block></fo:table-cell>
           |            <fo:table-cell padding="3pt" text-align="right"><fo:block>Line total</fo:block></fo:table-cell>
           |          </fo:table-row>
           |        </fo:table-header>
           |        <fo:table-body>
           |$rows
           |        </fo:table-body>
           |      </fo:table>""".stripMargin
    val correctsBlock =
      if (corrects.isEmpty) "" else s"""      <fo:block space-after="2pt">Corrects: ${esc(corrects)}</fo:block>"""
    val reasonBlock =
      if (reason.isEmpty) "" else s"""      <fo:block space-after="12pt">Reason: ${esc(reason)}</fo:block>"""
    val subtotal = field(model, "subtotal")
    val vat      = field(model, "vat")
    val total    = field(model, "total")
    val subtotalBlock =
      if (subtotal.isEmpty) ""
      else s"""      <fo:block text-align="right">Total ex VAT: $ccy ${esc(subtotal)}</fo:block>"""
    val vatBlock = if (vat.isEmpty) "" else s"""      <fo:block text-align="right">VAT: $ccy ${esc(vat)}</fo:block>"""
    val totalBlock =
      if (total.isEmpty) ""
      else
        s"""      <fo:block text-align="right" font-weight="bold" space-before="2pt">Total: $ccy ${esc(
          total
        )}</fo:block>"""
    val body = List(
      s"""      <fo:block font-size="20pt" font-weight="bold" space-after="6pt">$title</fo:block>""",
      s"""      <fo:block space-after="2pt">${esc(field(model, "supplier_name"))}</fo:block>""",
      s"""      <fo:block space-after="12pt">$payerLabel: ${esc(field(model, "payer_name"))}</fo:block>""",
      metaBlocks(model),
      correctsBlock,
      reasonBlock,
      tableBlock,
      subtotalBlock,
      vatBlock,
      totalBlock,
      noteBlocks(model)
    ).filter(_.nonEmpty).mkString("\n")
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" xml:lang="${esc(field(model, "locale"))}">
       |  <fo:layout-master-set>
       |    <fo:simple-page-master master-name="doc" page-width="210mm" page-height="297mm"
       |        margin-top="20mm" margin-bottom="20mm" margin-left="20mm" margin-right="20mm">
       |      <fo:region-body/>
       |    </fo:simple-page-master>
       |  </fo:layout-master-set>
       |  <fo:page-sequence master-reference="doc" font-family="Helvetica" font-size="10pt">
       |    <fo:flow flow-name="xsl-region-body">
       |$body
       |    </fo:flow>
       |  </fo:page-sequence>
       |</fo:root>""".stripMargin
  }
}
