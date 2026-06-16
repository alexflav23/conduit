package com.hypervolt.conduit.treasury

import cats.Applicative
import cats.syntax.all._
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Domain model + provider seam for the FX hedging program (M12-Treasury). Provider-agnostic: Ebury is the first
// FxHedgeProvider; others register in hedge_provider and are selected by HedgeRouting — no provider is hard-coded.

object Exposure {
  val CmPayment    = "cm_payment"    // CM invoice payments (Volex/Luxshare), per payment terms
  val CmPrepayment = "cm_prepayment" // prepayments to the CM
  val CmDeposit    = "cm_deposit"    // a one-off CM deposit (e.g. the $1.5m Luxshare deposit)
}

object HedgeStatus {
  val Proposed = "proposed"
  val Approved = "approved"
  val Executed = "executed"
  val Extended = "extended"
  val Settled  = "settled"
  val Unwound  = "unwound"
  val Open      = Set(Executed, Extended)
}

final case class HedgeProvider(id: UUID, code: String, name: String, adapter: String, active: Boolean)

final case class HedgeFacility(
    id: UUID,
    providerId: UUID,
    entityId: UUID,
    pairFrom: String,
    pairTo: String,
    creditLimit: BigDecimal,
    limitCurrency: String,
    interestFree: Boolean,
    marginVariationPct: BigDecimal,
    marginCallPct: BigDecimal,
    openedOn: LocalDate,
    status: String,
    docRef: Option[String]
)

final case class HedgePolicy(
    id: UUID,
    entityId: UUID,
    exposureType: String,
    hedgeRatio: BigDecimal,
    tenorMonths: Int,
    paymentTermsDays: Int,
    effectiveFrom: LocalDate,
    effectiveTo: Option[LocalDate],
    note: Option[String]
)

final case class ExposureForecast(
    entityId: UUID,
    supplier: String,
    exposureType: String,
    periodMonth: LocalDate,
    amountUsd: BigDecimal,
    source: String
)

final case class HedgeContract(
    id: UUID,
    facilityId: Option[UUID],
    providerId: Option[UUID],
    contractNo: Option[String],
    instrument: String,
    pairFrom: String,
    pairTo: String,
    contractedRate: BigDecimal,
    notional: BigDecimal,
    notionalUsed: BigDecimal,
    validFrom: LocalDate,
    validTo: LocalDate,
    status: String,
    hedgeRatio: Option[BigDecimal],
    supplier: Option[String],
    exposureType: Option[String],
    parentHedgeId: Option[UUID]
) {
  def notionalOpen: BigDecimal = (notional - notionalUsed).max(0)
}

// Insert shape (no server-assigned id / notional_used).
final case class NewHedgeContract(
    facilityId: Option[UUID],
    providerId: Option[UUID],
    contractNo: Option[String],
    instrument: String,
    pairFrom: String,
    pairTo: String,
    contractedRate: BigDecimal,
    notional: BigDecimal,
    validFrom: LocalDate,
    validTo: LocalDate,
    hedgeRatio: Option[BigDecimal],
    supplier: Option[String],
    exposureType: Option[String],
    parentHedgeId: Option[UUID]
)

final case class HedgeApproval(
    id: UUID,
    hedgeId: UUID,
    decision: String,
    requiredRole: String,
    approverUserId: Option[UUID],
    approverName: Option[String],
    status: String
)

// --- Provider seam -----------------------------------------------------------------------------------------------

final case class HedgeQuoteRequest(
    pairFrom: String,
    pairTo: String,
    notional: BigDecimal,
    validFrom: LocalDate,
    validTo: LocalDate,
    spot: BigDecimal
)
final case class HedgeQuote(rate: BigDecimal, marginVariationPct: BigDecimal, marginCallPct: BigDecimal)
final case class ExtensionQuote(newRate: BigDecimal, pipReduction: Int, newValidTo: LocalDate)

// A treasury FX provider. Implementations encode provider-specific quoting/repricing; the booked rate is always
// recorded from the provider's actual confirmation, so these are INDICATIVE (planning) figures, never asserted as
// the executed rate. Adding a provider = a new adapter, not a change to the program.
trait FxHedgeProvider[F[_]] {
  def code: String
  def indicativeForward(req: HedgeQuoteRequest): F[HedgeQuote]
  def repriceExtension(currentRate: BigDecimal, currentValidTo: LocalDate, newValidTo: LocalDate, spot: BigDecimal): F[ExtensionQuote]
}

// Ebury adapter: a simple forward (indicative ≈ spot — no forward-points curve is modelled, so planning uses spot
// and the booked rate is the one Ebury confirms), 5% / 5% margins, and a maturity-extension repricing modelled on
// the real Contract-3 case (a 63-pip / 0.47% rate reduction to push the maturity out ~2 months ≈ ~1 pip per day).
final class EburyProvider[F[_]: Applicative] extends FxHedgeProvider[F] {
  val code                = "ebury"
  private val pipsPerDay  = BigDecimal("1.05")
  private val marginVar   = BigDecimal("0.05")
  private val marginCall  = BigDecimal("0.05")

  def indicativeForward(req: HedgeQuoteRequest): F[HedgeQuote] =
    HedgeQuote(req.spot, marginVar, marginCall).pure[F]

  def repriceExtension(currentRate: BigDecimal, currentValidTo: LocalDate, newValidTo: LocalDate, spot: BigDecimal): F[ExtensionQuote] = {
    val days = ChronoUnit.DAYS.between(currentValidTo, newValidTo).max(0L)
    val pips = (BigDecimal(days) * pipsPerDay).setScale(0, RoundingMode.HALF_UP).toInt
    ExtensionQuote((currentRate - BigDecimal(pips) / 10000).setScale(8, RoundingMode.HALF_UP), pips, newValidTo).pure[F]
  }
}

object HedgeRouting {
  // Select the provider adapter by its registered code. New providers add a case (or this becomes table-driven).
  def adapter[F[_]: Applicative](code: String): FxHedgeProvider[F] = code match {
    case "ebury" => new EburyProvider[F]
    case _       => new EburyProvider[F] // default until further adapters land
  }
}
