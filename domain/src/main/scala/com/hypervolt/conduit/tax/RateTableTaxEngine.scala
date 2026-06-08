package com.hypervolt.conduit.tax

import cats.syntax.all._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import com.hypervolt.conduit.money.RoundingPolicy
import doobie._
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

// The default tax path (doc 16 §3.3 / §4.4a): pure, deterministic, zero external dependency. Reads the
// effective-dated tax_rate / duty_rate rows and computes per-line tax via Money + RoundingPolicy — never a
// Double/Float. One tax_rate row → one component; multi-level destinations (US state+county+district, CA
// GST+PST) sum their components. Line-vs-invoice rounding is per regime, and the invoice boundary re-allocates
// the rounded total by largest-remainder so Σ line tax == invoice tax exactly (doc 14 §1.3).
object RateTableTaxEngine {

  val ProviderName = "rate_table"
  val Version      = "1"

  private val levelOrder =
    Map("national" -> 0, "federal" -> 1, "state" -> 2, "provincial" -> 3, "county" -> 4, "city" -> 5, "district" -> 6)

  private def currencyOf(code: String): Currency = Currency.fromCode(code).getOrElse(Currency.GBP)

  private def policyOf(mode: String): RoundingPolicy =
    RoundingPolicy(Try(RoundingMode.withName(mode)).getOrElse(RoundingMode.HALF_UP))

  private def categoryOf(line: TaxQuoteLineReq): Option[String] =
    line.taxCategoryCode.orElse(Some("goods_standard"))

  // Most specific rate row within each taxing level (category-specific > generic, longer postcode prefix, region > national).
  private def chosenComponents(rows: List[RateRow]): List[RateRow] =
    rows
      .groupBy(_.level)
      .toList
      .flatMap { case (_, rs) => rs.sortBy(_.specificity).lastOption }
      .sortBy(r => levelOrder.getOrElse(r.level, 99))

  // ---- the engine entry point (within a transaction): supply facts → reproducible TaxQuoteResponse ----
  def quoteC(req: TaxQuoteRequest): ConnectionIO[TaxQuoteResponse] = {
    val facts = TaxClassifier.classify(req)
    if (facts.reverseCharge || facts.zeroRated) zeroQuote(req, facts).pure[ConnectionIO]
    else if (facts.isImport) importQuote(req, facts)
    else salesQuote(req, facts)
  }

  // Reverse-charge / export / out-of-scope: 0% with the flag set; buyer accounts for tax under reverse charge.
  private def zeroQuote(req: TaxQuoteRequest, facts: SupplyFacts): TaxQuoteResponse = {
    val lines = req.lines.map(l =>
      TaxQuoteLineResp(
        l.ref,
        l.productVariantId,
        l.taxableAmount,
        BigDecimal(0),
        BigDecimal(0),
        facts.reverseCharge,
        Some(facts.regimeCode),
        Nil
      )
    )
    TaxQuoteResponse(
      facts.supplyKind,
      facts.reverseCharge,
      req.currency,
      lines,
      req.lines.map(_.taxableAmount).sum,
      BigDecimal(0),
      "line",
      BigDecimal(0),
      None,
      None,
      None,
      ProviderName,
      Version,
      req.asOf
    )
  }

  // Domestic / intra-EU B2C / US destination / CA federal+provincial: gather the destination rate stack, choose the
  // most specific row per level, and compute the components.
  private def salesQuote(req: TaxQuoteRequest, facts: SupplyFacts): ConnectionIO[TaxQuoteResponse] = {
    val to    = req.shipTo
    val multi = facts.supplyKind == "us_destination" || facts.supplyKind == "ca_federal_provincial"
    // For CA, the provincial taxes (PST/QST/HST) are separate tax_types, so gather them alongside the federal GST;
    // compute selects the most specific row per level and sums the components.
    val provincialTypes = if (facts.supplyKind == "ca_federal_provincial") List("PST", "QST", "HST") else Nil
    req.lines
      .traverse(line =>
        (facts.taxType :: provincialTypes)
          .traverse(tt =>
            TaxRateRepo.candidates(tt, to.jurisdiction, to.region, to.postcode, categoryOf(line), req.asOf)
          )
          .map(rowsByType => (line, rowsByType.flatten))
      )
      .flatMap(perLine =>
        TaxRateRepo
          .regimeMeta(facts.regimeCode)
          .map(meta =>
            // Multi-level destinations round per component; single-rate regimes honour their configured boundary.
            compute(req, facts, perLine, if (multi) "line" else meta.roundingPolicy, policyOf(meta.roundingMode))
          )
      )
  }

  // Pure determination given already-resolved rate rows per line — the deterministic heart, exposed so the
  // line-vs-invoice rounding and multi-level summing can be property-tested without a database.
  def compute(
      req: TaxQuoteRequest,
      facts: SupplyFacts,
      perLine: List[(TaxQuoteLineReq, List[RateRow])],
      boundary: String,
      policy: RoundingPolicy
  ): TaxQuoteResponse = {
    val ccy = currencyOf(req.currency)
    // Selection (most specific row per level) is part of the determination, so it is exercised by this pure path.
    val rawByLine = perLine.map {
      case (line, candidates) =>
        val chosen   = chosenComponents(candidates)
        val rawComps = chosen.map(r => (r, line.taxableAmount * r.ratePct / 100))
        (line, chosen, rawComps)
    }
    val respLines =
      if (boundary == "invoice") computeInvoiceBoundary(rawByLine, ccy, policy, facts)
      else computeLineBoundary(rawByLine, ccy, policy, facts)
    val taxTotal = respLines.map(_.lineTaxTotal).sum
    TaxQuoteResponse(
      facts.supplyKind,
      reverseCharge = false,
      req.currency,
      respLines,
      req.lines.map(_.taxableAmount).sum,
      taxTotal,
      boundary,
      BigDecimal(0),
      None,
      None,
      None,
      ProviderName,
      Version,
      req.asOf
    )
  }

  private def lineResp(
      line: TaxQuoteLineReq,
      chosen: List[RateRow],
      comps: List[TaxComponent],
      facts: SupplyFacts
  ): TaxQuoteLineResp = {
    val regime =
      if (facts.supplyKind == "domestic" || facts.supplyKind == "intra_eu_b2c") Some(facts.regimeCode) else None
    TaxQuoteLineResp(
      line.ref,
      line.productVariantId,
      line.taxableAmount,
      comps.map(_.amount).sum,
      chosen.map(_.ratePct).sum,
      reverseCharge = false,
      regime,
      comps
    )
  }

  private def computeLineBoundary(
      rawByLine: List[(TaxQuoteLineReq, List[RateRow], List[(RateRow, BigDecimal)])],
      ccy: Currency,
      policy: RoundingPolicy,
      facts: SupplyFacts
  ): List[TaxQuoteLineResp] =
    rawByLine.map {
      case (line, chosen, rawComps) =>
        val comps = rawComps.map {
          case (r, raw) =>
            TaxComponent(
              r.level,
              r.jurisdiction,
              r.region,
              r.name,
              r.ratePct,
              Money(raw, ccy).roundToMinorUnits(policy).amount,
              r.taxType
            )
        }
        lineResp(line, chosen, comps, facts)
    }

  // Invoice boundary (doc 14 §1.2): sum exact line taxes, round the grand total once, then re-allocate by
  // largest-remainder so Σ line == total exactly; within each line, allocate to components by their raw weight.
  private def computeInvoiceBoundary(
      rawByLine: List[(TaxQuoteLineReq, List[RateRow], List[(RateRow, BigDecimal)])],
      ccy: Currency,
      policy: RoundingPolicy,
      facts: SupplyFacts
  ): List[TaxQuoteLineResp] = {
    val lineRaws = rawByLine.map { case (_, _, rawComps) => rawComps.map(_._2).sum }
    val grandRaw = lineRaws.sum
    val lineTaxes =
      if (grandRaw <= 0) lineRaws.map(_ => Money.zero(ccy))
      else Money.allocate(Money(grandRaw, ccy).roundToMinorUnits(policy), lineRaws.toVector).toList
    rawByLine.zip(lineTaxes).map {
      case ((line, chosen, rawComps), lineTax) =>
        val compRaws = rawComps.map(_._2)
        val compAmts =
          if (lineTax.isZero || compRaws.sum <= 0) rawComps.map(_ => BigDecimal(0).setScale(ccy.minorUnits))
          else Money.allocate(lineTax, compRaws.toVector).map(_.amount).toList
        val comps = rawComps.zip(compAmts).map {
          case ((r, _), amt) => TaxComponent(r.level, r.jurisdiction, r.region, r.name, r.ratePct, amt, r.taxType)
        }
        lineResp(line, chosen, comps, facts)
    }
  }

  // Import (ROW → EU/UK, or into CH/NO; the intercompany_import context, doc 13 §6): duty per line (longest HS
  // prefix) added to the customs value, then import VAT at the destination's standard rate on (value + duty).
  private def importQuote(req: TaxQuoteRequest, facts: SupplyFacts): ConnectionIO[TaxQuoteResponse] = {
    val ccy    = currencyOf(req.currency)
    val policy = RoundingPolicy.HalfUp
    val dest   = req.shipTo.jurisdiction
    req.lines
      .traverse(line =>
        (
          TaxRateRepo.duty(dest, line.hsCode, req.asOf),
          TaxRateRepo.candidates("VAT", dest, None, None, categoryOf(line), req.asOf)
        ).mapN { (dutyOpt, vatRows) =>
          val dutyPct = dutyOpt.map(_.ratePct).getOrElse(BigDecimal(0))
          val vatRow =
            chosenComponents(vatRows).find(_.level == "national").orElse(chosenComponents(vatRows).headOption)
          val vatPct  = vatRow.map(_.ratePct).getOrElse(BigDecimal(0))
          val recover = vatRow.forall(_.recoverable)
          val duty    = Money(line.taxableAmount * dutyPct / 100, ccy).roundToMinorUnits(policy).amount
          val vat     = Money((line.taxableAmount + duty) * vatPct / 100, ccy).roundToMinorUnits(policy).amount
          val comp    = TaxComponent("national", dest, None, "Import VAT", vatPct, vat, "VAT")
          TaxQuoteLineResp(
            line.ref,
            line.productVariantId,
            line.taxableAmount,
            vat,
            vatPct,
            reverseCharge = false,
            Some("IMPORT"),
            List(comp),
            Some(dutyPct),
            Some(duty),
            Some(vatPct),
            Some(vat),
            Some(recover)
          )
        }
      )
      .map { lines =>
        val dutyTotal = lines.flatMap(_.dutyAmount).sum
        val vatTotal  = lines.flatMap(_.importVatAmount).sum
        TaxQuoteResponse(
          facts.supplyKind,
          reverseCharge = false,
          req.currency,
          lines,
          req.lines.map(_.taxableAmount).sum,
          vatTotal,
          "line",
          dutyTotal,
          Some(vatTotal),
          Some(lines.flatMap(_.importVatRecoverable).forall(identity)),
          Some(s"RT-${req.intercompanyLinkId.map(_.toString.take(8)).getOrElse("import")}"),
          ProviderName,
          Version,
          req.asOf
        )
      }
  }
}
