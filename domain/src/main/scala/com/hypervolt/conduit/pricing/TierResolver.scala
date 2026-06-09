package com.hypervolt.conduit.pricing

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

// The group-aggregated cumulative qualifying volume (doc 24 §4) — a DERIVED projection over the immutable order
// stream, never a stored counter. Counts the qualifying product class across ALL parties on the agreement (the
// "Authorised Agent" group aggregation) within the contract-year window. Ordered basis (placed, not yet cancelled);
// the current order isn't placed at resolution time so it's naturally excluded ("going forward only").
object ContractVolumeRepo {

  def priorCumulativeQualifying(
      agreementId: UUID,
      qualifyingClass: String,
      windowStart: Instant,
      windowEnd: Instant
  ): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(ol.qty), 0)::int
          FROM order_line ol
          JOIN "order" o ON o.id = ol.order_id
          JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE o.sold_to_party_id IN (SELECT party_id FROM price_agreement_customer WHERE agreement_id = $agreementId)
            AND pv.product_class = $qualifyingClass
            AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
            AND o.created_at >= $windowStart AND o.created_at < $windowEnd"""
      .query[Int]
      .unique
}

// Builds the full candidate set for a line (doc 24 §2/§4): the per-order bands (unchanged from M3 — qty-filtered),
// PLUS, for each cumulative agreement, the single band the customer's running cumulative position has unlocked. The
// merged list feeds PricingService.resolve, so most-specific-agreement-wins arbitrates between a per_order open_list
// and a cumulative customer contract identically to the per-order case.
object TierResolver {

  def candidates(
      variantId: UUID,
      qualifyingClass: String,
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      qty: Int,
      customer: Option[UUID],
      asOf: Instant
  ): ConnectionIO[List[PriceRuleCandidate]] =
    PriceRuleRepo.candidates(variantId, channel, market, entity, currency, qty, customer, asOf).flatMap { perOrder =>
      PriceRuleRepo.cumulativeBands(variantId, currency, customer, asOf).flatMap { cumBands =>
        cumBands
          .groupBy(_.agreementId)
          .toList
          .traverse {
            case (agreementId, bands) =>
              val (start, end) = ContractYear.windowFor(bands.head.validFrom, asOf)
              ContractVolumeRepo
                .priorCumulativeQualifying(agreementId, qualifyingClass, start, end)
                .map(position => selectBand(bands, position))
          }
          .map(selected => perOrder ++ selected.flatten.map(toCandidate))
      }
    }

  // The band the cumulative position has unlocked: the highest from_qty ≤ position whose ceiling still covers it;
  // if the position is below every floor, the entry tier (lowest from_qty) applies — tier 1 holds from unit zero.
  private def selectBand(bands: List[CumulativeBand], position: Int): Option[CumulativeBand] =
    bands
      .filter(b => b.fromQty <= position && b.upToQty.forall(position <= _))
      .maxByOption(_.fromQty)
      .orElse(bands.minByOption(_.fromQty))

  private def toCandidate(b: CumulativeBand): PriceRuleCandidate =
    PriceRuleCandidate(
      b.id,
      Some(b.agreementId),
      b.appliesTo,
      b.channelId,
      b.marketId,
      b.entityId,
      b.authorisedPrice,
      b.maxDiscountPct,
      b.fromQty,
      b.upToQty,
      b.version,
      b.taxRegime,
      b.taxRatePct
    )
}
