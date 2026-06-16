package com.hypervolt.conduit.treasury

import cats.Applicative
import cats.syntax.all._
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
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
  val Open     = Set(Executed, Extended)
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

final case class EffectivenessRow(
    periodMonth: LocalDate,
    supplier: String,
    exposureUsd: BigDecimal,
    hedgeRatio: BigDecimal,
    hedgeRate: BigDecimal,
    spotRate: BigDecimal,
    effectiveRate: BigDecimal,
    hedgedGbp: BigDecimal,
    spotGbp: BigDecimal,
    savingGbp: BigDecimal,
    contractNo: Option[String]
)

// Pure rate maths shared by the apply-to-COGS hook and the effectiveness stream. Rates are GBP/USD (USD per GBP),
// so a USD payable converts to GBP as USD / rate. The blended EFFECTIVE rate for a hedge ratio h is the harmonic
// blend of the locked contract rate and spot: hedged GBP = usd·(h/hedgeRate + (1−h)/spot), i.e. 1/effectiveRate =
// h/hedgeRate + (1−h)/spot. This is the rate the COGS leg uses to value the USD cost; saving vs all-spot is the
// hedge's economic contribution (negative when the lock sits below market).
object HedgeMath {
  def effectiveRate(ratio: BigDecimal, hedgeRate: BigDecimal, spot: BigDecimal): BigDecimal =
    (BigDecimal(1) / (ratio / hedgeRate + (BigDecimal(1) - ratio) / spot)).setScale(8, RoundingMode.HALF_UP)
  def hedgedGbp(usd: BigDecimal, ratio: BigDecimal, hedgeRate: BigDecimal, spot: BigDecimal): BigDecimal =
    (usd * (ratio / hedgeRate + (BigDecimal(1) - ratio) / spot)).setScale(2, RoundingMode.HALF_UP)
  def spotGbp(usd: BigDecimal, spot: BigDecimal): BigDecimal = (usd / spot).setScale(2, RoundingMode.HALF_UP)
}

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
  def repriceExtension(
      currentRate: BigDecimal,
      currentValidTo: LocalDate,
      newValidTo: LocalDate,
      spot: BigDecimal
  ): F[ExtensionQuote]
}

// Ebury adapter: a simple forward (indicative ≈ spot — no forward-points curve is modelled, so planning uses spot
// and the booked rate is the one Ebury confirms), 5% / 5% margins, and a maturity-extension repricing modelled on
// the real Contract-3 case (a 63-pip / 0.47% rate reduction to push the maturity out ~2 months ≈ ~1 pip per day).
final class EburyProvider[F[_]: Applicative] extends FxHedgeProvider[F] {
  val code               = "ebury"
  private val pipsPerDay = BigDecimal("1.05")
  private val marginVar  = BigDecimal("0.05")
  private val marginCall = BigDecimal("0.05")

  def indicativeForward(req: HedgeQuoteRequest): F[HedgeQuote] =
    HedgeQuote(req.spot, marginVar, marginCall).pure[F]

  def repriceExtension(
      currentRate: BigDecimal,
      currentValidTo: LocalDate,
      newValidTo: LocalDate,
      spot: BigDecimal
  ): F[ExtensionQuote] = {
    val days = ChronoUnit.DAYS.between(currentValidTo, newValidTo).max(0L)
    val pips = (BigDecimal(days) * pipsPerDay).setScale(0, RoundingMode.HALF_UP).toInt
    ExtensionQuote((currentRate - BigDecimal(pips) / 10000).setScale(8, RoundingMode.HALF_UP), pips, newValidTo).pure[F]
  }
}

// JSON encoders for the treasury read routes (imported where the routes assemble the program/effectiveness views).
object HedgeJson {
  implicit val facility: Encoder[HedgeFacility]    = deriveEncoder
  implicit val policy: Encoder[HedgePolicy]        = deriveEncoder
  implicit val contract: Encoder[HedgeContract]    = deriveEncoder
  implicit val exposure: Encoder[ExposureForecast] = deriveEncoder
  implicit val eff: Encoder[EffectivenessRow]      = deriveEncoder
}

object HedgeRouting {
  // Select the provider adapter by its registered code. New providers add a case (or this becomes table-driven).
  def adapter[F[_]: Applicative](code: String): FxHedgeProvider[F] =
    code match {
      case "ebury" => new EburyProvider[F]
      case _       => new EburyProvider[F] // default until further adapters land
    }
}
