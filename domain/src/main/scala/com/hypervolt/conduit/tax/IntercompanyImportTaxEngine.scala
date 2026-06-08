package com.hypervolt.conduit.tax

import com.hypervolt.conduit.intercompany.{TaxEngine => IcTaxEngine}
import com.hypervolt.conduit.intercompany.{TaxQuoteLine => IcLine}
import com.hypervolt.conduit.intercompany.{TaxQuoteRequest => IcReq}
import com.hypervolt.conduit.intercompany.{TaxQuoteResponse => IcResp}
import com.hypervolt.conduit.intercompany.{TaxQuoteResponseLine => IcRespLine}
import doobie._

// The doc-13 §6 buy-side import-tax boundary, served by the real rate-table engine (replacing StubTaxEngine). It
// translates the intercompany engine's import-shaped request into the doc-16 TaxQuote contract, runs the same
// determination, and maps the per-line duty + import VAT back. The intercompany service depends only on the
// IcTaxEngine trait — swapping a vendor in is a different impl, not a caller change.
object IntercompanyImportTaxEngine extends IcTaxEngine {

  def quote(req: IcReq): ConnectionIO[IcResp] = {
    val rich = TaxQuoteRequest(
      context = "intercompany_import",
      entityId = req.movementRef,
      shipFrom = TaxShipPoint(req.shipFromJurisdiction, None, None),
      shipTo = TaxShipPoint(req.shipToJurisdiction, None, None),
      partyTaxStatus = "business",
      buyerTaxId = None,
      incoterm = None,
      currency = req.lines.headOption.map(_.currency).getOrElse("GBP"),
      asOf = req.asOf,
      lines = req.lines.zipWithIndex.map {
        case (l, i) => TaxQuoteLineReq(s"ic-$i", Some(l.productVariantId), None, l.hsCode, l.qty, l.customsValue)
      },
      intercompanyLinkId = Some(req.movementRef)
    )
    RateTableTaxEngine.quoteC(rich).map(resp => mapBack(req.lines, resp))
  }

  private def mapBack(icLines: List[IcLine], resp: TaxQuoteResponse): IcResp = {
    val lines = icLines.zip(resp.lines).map {
      case (ic, rl) =>
        IcRespLine(
          ic.productVariantId,
          ic.hsCode,
          rl.dutyRatePct.getOrElse(BigDecimal(0)),
          rl.dutyAmount.getOrElse(BigDecimal(0)),
          rl.importVatRatePct.getOrElse(BigDecimal(0)),
          rl.importVatAmount.getOrElse(BigDecimal(0)),
          rl.importVatRecoverable.getOrElse(true),
          "IMPORT"
        )
    }
    IcResp(
      lines,
      resp.dutyTotal,
      resp.importVatTotal.getOrElse(BigDecimal(0)),
      resp.currency,
      resp.determinationRef.getOrElse(s"${RateTableTaxEngine.ProviderName}"),
      RateTableTaxEngine.ProviderName,
      RateTableTaxEngine.Version
    )
  }
}
