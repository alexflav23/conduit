package com.hypervolt.conduit.tax

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate

// One effective-dated tax_rate row = one taxing authority. The rate in force at any historic as_of is reproducible
// because a change is a NEW dated row, never an in-place edit (doc 16 §7).
final case class RateRow(
    taxType: String,
    jurisdiction: String,
    region: Option[String],
    postcodePrefix: Option[String],
    level: String,
    category: Option[String],
    name: String,
    ratePct: BigDecimal,
    kind: String,
    recoverable: Boolean
) {
  // Specificity for "most specific row wins within a level": a category-specific row beats a generic one, a longer
  // postcode prefix beats a shorter, a region-specific row beats a national one.
  def specificity: (Int, Int, Int) =
    (if (category.isDefined) 1 else 0, postcodePrefix.map(_.length).getOrElse(0), if (region.isDefined) 1 else 0)
}

final case class DutyRow(hsPrefix: String, ratePct: BigDecimal, name: Option[String])

final case class RegimeMeta(roundingPolicy: String, roundingMode: String)

object TaxRateRepo {

  // Candidate rates for a destination: jurisdiction + tax_type match, region/postcode/category null-or-match, in the
  // effective window, active. Grouping by level + most-specific-wins happens in the pure layer (TaxComputation).
  def candidates(
      taxType: String,
      jurisdiction: String,
      region: Option[String],
      postcode: Option[String],
      category: Option[String],
      asOf: LocalDate
  ): ConnectionIO[List[RateRow]] =
    sql"""SELECT tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind, recoverable
          FROM tax_rate
          WHERE jurisdiction = $jurisdiction
            AND tax_type = $taxType
            AND (region IS NULL OR region = $region)
            AND (postcode_prefix IS NULL OR ($postcode IS NOT NULL AND $postcode LIKE postcode_prefix || '%'))
            AND (tax_category_code IS NULL OR tax_category_code = $category)
            AND status <> 'draft'
            AND effective_from <= $asOf
            AND (effective_to IS NULL OR effective_to > $asOf)"""
      .query[RateRow]
      .to[List]

  // Longest HS-prefix match for import duty into a destination, effective at as_of.
  def duty(destination: String, hsCode: Option[String], asOf: LocalDate): ConnectionIO[Option[DutyRow]] =
    sql"""SELECT hs_prefix, rate_pct, name
          FROM duty_rate
          WHERE destination = $destination
            AND ($hsCode LIKE hs_prefix || '%' OR hs_prefix = '')
            AND status = 'active'
            AND effective_from <= $asOf
            AND (effective_to IS NULL OR effective_to > $asOf)
          ORDER BY length(hs_prefix) DESC
          LIMIT 1"""
      .query[DutyRow]
      .option

  // Rounding boundary for a regime (doc 14 §1.2 — line vs invoice, per tax_regime). Default line / HALF_UP.
  def regimeMeta(regimeCode: String): ConnectionIO[RegimeMeta] =
    sql"""SELECT rounding_policy, rounding_mode FROM tax_regime WHERE code = $regimeCode"""
      .query[RegimeMeta]
      .option
      .map(_.getOrElse(RegimeMeta("line", "HALF_UP")))
}
