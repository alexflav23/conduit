package com.hypervolt.conduit.commission

import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class CommissionScheme(
    id: UUID,
    basis: String,
    ratePct: BigDecimal,
    exceptionTreatment: String,
    validFrom: Instant,
    validTo: Option[Instant]
)

// An assignment's fields are constraints: a None field means "applies to any". More set fields = more specific.
final case class SchemeAssignment(
    schemeId: UUID,
    teamId: Option[UUID],
    channelId: Option[UUID],
    marketId: Option[UUID],
    entityId: Option[UUID]
)

final case class ResolvableScheme(scheme: CommissionScheme, assignment: SchemeAssignment)

final case class CommissionLineInput(
    unitPriceExVat: BigDecimal,
    unitCost: BigDecimal,
    qty: Int,
    adlpCategory: String,
    exceptionApproved: Boolean
)

// Resolution by specificity + validity window (doc 04 §Commission): a more specific assignment beats a
// general one; a per-country override is just an assignment with market set; all bounded by valid_from/to.
object CommissionResolver {

  def resolve(
      candidates: List[ResolvableScheme],
      teamId: Option[UUID],
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      asOf: Instant
  ): Option[CommissionScheme] = {
    val matching = candidates.filter { rs =>
      val s = rs.scheme
      val a = rs.assignment
      s.validFrom.compareTo(asOf) <= 0 &&
      s.validTo.forall(asOf.isBefore) &&
      a.teamId.forall(t => teamId.contains(t)) &&
      a.channelId.forall(_ == channel) &&
      a.marketId.forall(_ == market) &&
      a.entityId.forall(e => entity.contains(e))
    }
    def specificity(a: SchemeAssignment): Int = List(a.teamId, a.channelId, a.marketId, a.entityId).count(_.isDefined)
    matching.sortBy(rs => (-specificity(rs.assignment), -rs.scheme.validFrom.toEpochMilli)).headOption.map(_.scheme)
  }

  // Gross-margin basis. Exception lines earn 0 unless approved; an approved exception applies the scheme's
  // exception_treatment (full / reduced / zero). Returns (basis_amount, commission_amount).
  def lineCommission(scheme: CommissionScheme, line: CommissionLineInput): (BigDecimal, BigDecimal) = {
    val grossMargin = (line.unitPriceExVat - line.unitCost) * BigDecimal(line.qty)
    val factor =
      if (line.adlpCategory != "exception") BigDecimal(1)
      else if (!line.exceptionApproved) BigDecimal(0)
      else
        scheme.exceptionTreatment match {
          case "full"    => BigDecimal(1)
          case "reduced" => BigDecimal("0.5")
          case _         => BigDecimal(0)
        }
    val amount = (grossMargin * scheme.ratePct / 100 * factor).setScale(2, RoundingMode.HALF_UP)
    (grossMargin.setScale(2, RoundingMode.HALF_UP), amount)
  }
}
