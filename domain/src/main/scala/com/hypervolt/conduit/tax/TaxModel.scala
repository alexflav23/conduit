package com.hypervolt.conduit.tax

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import java.time.LocalDate
import java.util.UUID

// The doc-16 TaxQuote contract — supply facts in, per-line tax + a multi-level jurisdiction breakdown out.
// One contract, two provider paths (rate-table default; external vendor port); the caller never knows which ran.
// Every amount is decimal (BigDecimal on the wire / in storage; computed via Money + RoundingPolicy — no float).

final case class TaxShipPoint(jurisdiction: String, region: Option[String], postcode: Option[String])

final case class TaxQuoteLineReq(
    ref: String,
    productVariantId: Option[UUID],
    taxCategoryCode: Option[String],
    hsCode: Option[String],
    qty: Int,
    taxableAmount: BigDecimal
)

final case class TaxQuoteRequest(
    context: String, // quote_preview | order_placed | invoice | intercompany_import
    entityId: UUID,
    shipFrom: TaxShipPoint,
    shipTo: TaxShipPoint,
    partyTaxStatus: String, // consumer | business | business_with_vat_id | exempt
    buyerTaxId: Option[String],
    incoterm: Option[String],
    currency: String,
    asOf: LocalDate,
    lines: List[TaxQuoteLineReq],
    orderId: Option[UUID] = None,
    trancheId: Option[UUID] = None,
    orderInvoiceId: Option[UUID] = None,
    intercompanyLinkId: Option[UUID] = None
)

// One taxing authority's slice of a line's tax — the unit the invoice line / tax return renders from.
final case class TaxComponent(
    level: String, // national | state | county | city | district | federal | provincial
    jurisdiction: String,
    region: Option[String],
    name: String,
    ratePct: BigDecimal,
    amount: BigDecimal,
    taxType: String
)

final case class TaxQuoteLineResp(
    ref: String,
    productVariantId: Option[UUID],
    taxableAmount: BigDecimal,
    lineTaxTotal: BigDecimal,
    effectiveRatePct: BigDecimal,
    reverseCharge: Boolean,
    regimeCode: Option[String],
    components: List[TaxComponent],
    // import context only (doc 13 §6) — duty into landed cost, import VAT recoverable/capitalised
    dutyRatePct: Option[BigDecimal] = None,
    dutyAmount: Option[BigDecimal] = None,
    importVatRatePct: Option[BigDecimal] = None,
    importVatAmount: Option[BigDecimal] = None,
    importVatRecoverable: Option[Boolean] = None
)

final case class TaxQuoteResponse(
    supplyKind: String,
    reverseCharge: Boolean,
    currency: String,
    lines: List[TaxQuoteLineResp],
    taxableTotal: BigDecimal,
    taxTotal: BigDecimal,
    roundingPolicy: String, // line | invoice (the boundary actually applied, doc 14 §1.2)
    dutyTotal: BigDecimal,
    importVatTotal: Option[BigDecimal],
    importVatRecoverable: Option[Boolean],
    determinationRef: Option[String],
    provider: String,
    providerVersion: String,
    ratesAsof: LocalDate
)

object TaxShipPoint     { implicit val c: Codec[TaxShipPoint] = deriveCodec     }
object TaxQuoteLineReq  { implicit val c: Codec[TaxQuoteLineReq] = deriveCodec  }
object TaxQuoteRequest  { implicit val c: Codec[TaxQuoteRequest] = deriveCodec  }
object TaxComponent     { implicit val c: Codec[TaxComponent] = deriveCodec     }
object TaxQuoteLineResp { implicit val c: Codec[TaxQuoteLineResp] = deriveCodec }
object TaxQuoteResponse { implicit val c: Codec[TaxQuoteResponse] = deriveCodec }

// The resolved legal shape of a supply — the decision the rate-table path encodes (doc 16 §4.2).
final case class SupplyFacts(
    supplyKind: String,
    taxType: String,
    regimeCode: String,
    reverseCharge: Boolean,
    zeroRated: Boolean,
    isImport: Boolean
)

// Place-of-supply classification (doc 16 §4.2). Pure: ship-from / ship-to / party tax status → supply_kind + which
// regime + reverse-charge / zero-rate flags. The external path defers rates to the vendor but Conduit still
// classifies for routing, nexus tracking and Intrastat/EC-sales lineage.
object TaxClassifier {

  private val euCountries: Set[String] = Set(
    "AT",
    "BE",
    "BG",
    "HR",
    "CY",
    "CZ",
    "DK",
    "EE",
    "FI",
    "FR",
    "DE",
    "GR",
    "HU",
    "IE",
    "IT",
    "LV",
    "LT",
    "LU",
    "MT",
    "NL",
    "PL",
    "PT",
    "RO",
    "SK",
    "SI",
    "ES",
    "SE"
  )

  def economicZone(jurisdiction: String): String =
    if (jurisdiction == "GB") "UK"
    else if (euCountries.contains(jurisdiction)) "EU"
    else if (jurisdiction == "US" || jurisdiction == "CA") "NA"
    else "ROW"

  def taxTypeOf(jurisdiction: String): String =
    if (jurisdiction == "US") "sales_tax" else if (jurisdiction == "CA") "GST" else "VAT"

  // Structural validity: a 2-letter country prefix matching the destination + at least one identifier char.
  // (VIES/HMRC online validation is an external check layered on top — doc 16 §4.2.)
  private def validVatId(buyerTaxId: Option[String], destination: String): Boolean =
    buyerTaxId.exists { raw =>
      val id = raw.trim
      id.length >= 4 && id.take(2).forall(_.isLetter) && id.take(2).equalsIgnoreCase(destination)
    }

  def classify(req: TaxQuoteRequest): SupplyFacts = {
    val from     = req.shipFrom.jurisdiction
    val to       = req.shipTo.jurisdiction
    val fromZone = economicZone(from)
    val toZone   = economicZone(to)
    val business = req.partyTaxStatus == "business" || req.partyTaxStatus == "business_with_vat_id"
    val validVat = req.partyTaxStatus == "business_with_vat_id" && validVatId(req.buyerTaxId, to)

    if (to == "US")
      SupplyFacts(
        "us_destination",
        "sales_tax",
        "US_DESTINATION",
        reverseCharge = false,
        zeroRated = false,
        isImport = false
      )
    else if (to == "CA")
      SupplyFacts("ca_federal_provincial", "GST", "CA_GST", reverseCharge = false, zeroRated = false, isImport = false)
    else if (from == to)
      SupplyFacts(
        "domestic",
        taxTypeOf(to),
        s"${to}_STANDARD",
        reverseCharge = false,
        zeroRated = false,
        isImport = false
      )
    else if (fromZone == "EU" && toZone == "EU" && business && validVat)
      SupplyFacts(
        "intra_eu_b2b_reverse",
        "VAT",
        "REVERSE_CHARGE",
        reverseCharge = true,
        zeroRated = false,
        isImport = false
      )
    else if (fromZone == "EU" && toZone == "EU")
      SupplyFacts("intra_eu_b2c", "VAT", s"${to}_STANDARD", reverseCharge = false, zeroRated = false, isImport = false)
    else if ((fromZone == "EU" || fromZone == "UK") && toZone == "ROW")
      SupplyFacts("export", "VAT", "EXPORT", reverseCharge = false, zeroRated = true, isImport = false)
    else if ((toZone == "EU" || toZone == "UK") && fromZone == "ROW")
      SupplyFacts("import", "VAT", "IMPORT", reverseCharge = false, zeroRated = false, isImport = true)
    else if ((to == "CH" || to == "NO") && from != to)
      SupplyFacts("import", "VAT", "IMPORT", reverseCharge = false, zeroRated = false, isImport = true)
    else
      SupplyFacts("out_of_scope", "VAT", "TAX_FREE", reverseCharge = false, zeroRated = true, isImport = false)
  }
}
