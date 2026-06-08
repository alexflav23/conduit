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

  // The governed A4 invoice layout (doc 17 §5). Pure function of the render model → reproducible FO → reproducible
  // PDF. Latin base-14 fonts for the year-1 GB/en template; a CJK/Thai font_stack drops in via fo:font-family + a
  // fop.xconf when those markets come online (doc 02 §A roadmap).
  def foDocument(model: Json): String = {
    val ccy      = esc(field(model, "currency"))
    val corrects = field(model, "corrects_number")
    val reason   = field(model, "reason")
    val title    = if (corrects.nonEmpty) "CREDIT NOTE" else "INVOICE"
    val rows     = lineRows(model)
    // A line table is only valid FO when it has rows (invoices); creditnotes/statements without lines omit it.
    val tableBlock =
      if (rows.trim.isEmpty) ""
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
    val subtotalBlock =
      if (subtotal.isEmpty) ""
      else s"""      <fo:block text-align="right">Total ex VAT: $ccy ${esc(subtotal)}</fo:block>"""
    val vatBlock = if (vat.isEmpty) "" else s"""      <fo:block text-align="right">VAT: $ccy ${esc(vat)}</fo:block>"""
    val body = List(
      s"""      <fo:block font-size="20pt" font-weight="bold" space-after="6pt">$title</fo:block>""",
      s"""      <fo:block space-after="2pt">${esc(field(model, "supplier_name"))}</fo:block>""",
      s"""      <fo:block space-after="12pt">Bill to: ${esc(field(model, "payer_name"))}</fo:block>""",
      correctsBlock,
      reasonBlock,
      tableBlock,
      subtotalBlock,
      vatBlock,
      s"""      <fo:block text-align="right" font-weight="bold" space-before="2pt">Total: $ccy ${esc(
        field(model, "total")
      )}</fo:block>"""
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
