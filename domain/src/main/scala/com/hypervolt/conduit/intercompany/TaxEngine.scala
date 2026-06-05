package com.hypervolt.conduit.intercompany

import doobie._
import io.circe.Encoder
import io.circe.generic.semiauto._
import java.util.UUID

// The tax/customs engine boundary (doc 13 §6). This subsystem DOES NOT determine VAT/duty; it assembles the
// buy-side context and calls the engine (Avalara/TaxJar/Stripe Tax in production), then records what it returns.
// The boundary is this request/response contract. A stub implementation is used locally and in tests; year-1
// (domestic UK ← Luxshare-UK) is not cross-border, so the engine is not called at all.

final case class TaxQuoteLine(
    productVariantId: UUID,
    hsCode: Option[String],
    qty: Int,
    customsValue: BigDecimal,
    currency: String
)
final case class TaxQuoteRequest(
    context: String,
    shipFromJurisdiction: String,
    shipToJurisdiction: String,
    shipToRegime: String,
    lines: List[TaxQuoteLine],
    movementRef: UUID
)

final case class TaxQuoteResponseLine(
    productVariantId: UUID,
    hsCode: Option[String],
    dutyRatePct: BigDecimal,
    dutyAmount: BigDecimal,
    importVatRatePct: BigDecimal,
    importVatAmount: BigDecimal,
    importVatRecoverable: Boolean,
    regime: String
)
final case class TaxQuoteResponse(
    lines: List[TaxQuoteResponseLine],
    dutyTotal: BigDecimal,
    importVatTotal: BigDecimal,
    currency: String,
    determinationRef: String,
    engine: String,
    engineVersion: String
)

object TaxQuoteResponse {
  implicit val encLine: Encoder[TaxQuoteResponseLine] = deriveEncoder
  implicit val enc: Encoder[TaxQuoteResponse]         = deriveEncoder
}

trait TaxEngine {
  def quote(req: TaxQuoteRequest): ConnectionIO[TaxQuoteResponse]
}

// A deterministic stub: flat duty + standard import VAT by destination regime, VAT recoverable for B2B. Good
// enough to exercise the recording path; a real provider swaps in behind the same contract.
object StubTaxEngine extends TaxEngine {
  import cats.syntax.all._

  private def rates(regime: String): (BigDecimal, BigDecimal, Boolean) =
    regime match {
      case "GB"                      => (BigDecimal(0), BigDecimal(20), true)
      case "DE" | "FR" | "IE" | "NL" => (BigDecimal("2.5"), BigDecimal(19), true) // EU import: duty + import VAT
      case "US"                      => (BigDecimal("3.0"), BigDecimal(0), false) // destination sales tax handled elsewhere
      case "AU"                      => (BigDecimal("5.0"), BigDecimal(10), true)
      case _                         => (BigDecimal("2.0"), BigDecimal(10), true)
    }

  def quote(req: TaxQuoteRequest): ConnectionIO[TaxQuoteResponse] = {
    val (dutyPct, vatPct, recoverable) = rates(req.shipToRegime)
    val lines = req.lines.map { l =>
      val duty = (l.customsValue * dutyPct / 100).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      val vat  = ((l.customsValue + duty) * vatPct / 100).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      TaxQuoteResponseLine(l.productVariantId, l.hsCode, dutyPct, duty, vatPct, vat, recoverable, req.shipToRegime)
    }
    TaxQuoteResponse(
      lines,
      lines.map(_.dutyAmount).sum,
      lines.map(_.importVatAmount).sum,
      req.lines.headOption.map(_.currency).getOrElse("USD"),
      s"STUB-${req.movementRef.toString.take(8)}",
      "stub",
      "1"
    ).pure[ConnectionIO]
  }
}
