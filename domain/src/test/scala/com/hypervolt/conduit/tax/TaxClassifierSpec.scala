package com.hypervolt.conduit.tax

import java.time.LocalDate
import java.util.UUID
import weaver.SimpleIOSuite

// Place-of-supply classification (doc 16 §4.2) — the legal heart of the rate-table path. Pure: ship-from /
// ship-to / party tax status → supply_kind + reverse-charge / zero-rate flags. Year-1 is UK domestic, but the
// classifier must already resolve every cross-border case so opening a market is config, not code.
object TaxClassifierSpec extends SimpleIOSuite {

  private def req(
      from: String,
      to: String,
      status: String,
      vatId: Option[String] = None
  ): TaxQuoteRequest =
    TaxQuoteRequest(
      "order_placed",
      UUID.randomUUID(),
      TaxShipPoint(from, None, None),
      TaxShipPoint(to, None, None),
      status,
      vatId,
      None,
      "GBP",
      LocalDate.parse("2026-06-01"),
      List(TaxQuoteLineReq("l1", None, None, None, 1, BigDecimal("100.00")))
    )

  pureTest("domestic GB→GB is standard-rated VAT") {
    val f = TaxClassifier.classify(req("GB", "GB", "business"))
    expect(f.supplyKind == "domestic") and expect(f.taxType == "VAT") and expect(!f.reverseCharge) and expect(
      !f.zeroRated
    )
  }

  pureTest("intra-EU B2B with a valid buyer VAT id is reverse-charge (0%, buyer accounts)") {
    val f = TaxClassifier.classify(req("DE", "FR", "business_with_vat_id", Some("FR12345678901")))
    expect(f.supplyKind == "intra_eu_b2b_reverse") and expect(f.reverseCharge) and expect(
      f.regimeCode == "REVERSE_CHARGE"
    )
  }

  pureTest("intra-EU B2B without a valid VAT id falls back to destination VAT (B2C path)") {
    val f = TaxClassifier.classify(req("DE", "FR", "business_with_vat_id", Some("XX")))
    expect(f.supplyKind == "intra_eu_b2c") and expect(!f.reverseCharge)
  }

  pureTest("intra-EU B2C is destination VAT, not reverse charge") {
    val f = TaxClassifier.classify(req("DE", "FR", "consumer"))
    expect(f.supplyKind == "intra_eu_b2c") and expect(!f.reverseCharge)
  }

  pureTest("EU/UK → ROW is a zero-rated export") {
    val f = TaxClassifier.classify(req("GB", "JP", "business"))
    expect(f.supplyKind == "export") and expect(f.zeroRated) and expect(f.regimeCode == "EXPORT")
  }

  pureTest("ROW → UK is an import (import VAT at destination)") {
    val f = TaxClassifier.classify(req("CN", "GB", "business"))
    expect(f.supplyKind == "import") and expect(f.isImport) and expect(f.regimeCode == "IMPORT")
  }

  pureTest("a supply into CH (non-EU) from a non-EU origin is an import (an EU origin would be an export)") {
    val f = TaxClassifier.classify(req("CN", "CH", "consumer"))
    expect(f.supplyKind == "import") and expect(f.isImport)
  }

  pureTest("shipping to the US is destination sales tax (multi-level, external-capable)") {
    val f = TaxClassifier.classify(req("GB", "US", "consumer"))
    expect(f.supplyKind == "us_destination") and expect(f.taxType == "sales_tax")
  }

  pureTest("shipping to Canada is federal + provincial (GST base)") {
    val f = TaxClassifier.classify(req("GB", "CA", "consumer"))
    expect(f.supplyKind == "ca_federal_provincial") and expect(f.taxType == "GST")
  }
}
