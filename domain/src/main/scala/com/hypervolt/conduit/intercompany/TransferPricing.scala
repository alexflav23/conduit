package com.hypervolt.conduit.intercompany

import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.RoundingPolicy

// The three transfer-pricing methods (doc 13 §2.2), computed against the SPECIFIC lot's landed cost — never a
// weighted average (doc 02 §G / doc 14 §3 specific-identification). Pure: a unit price in the tp currency,
// rounded at an explicit RoundingPolicy. Cross-currency `fixed` conversion is provenanced by the caller.

object TransferPricing {

  sealed abstract class Method(val code: String)
  object Method {
    case object CostPlus    extends Method("cost_plus")
    case object ResaleMinus extends Method("resale_minus")
    case object Fixed       extends Method("fixed")
    def fromCode(s: String): Option[Method] =
      List(CostPlus, ResaleMinus, Fixed).find(_.code == s)
  }

  final case class Policy(
      method: Method,
      markupPct: Option[BigDecimal],
      resaleMarginPct: Option[BigDecimal],
      fixedPrice: Option[BigDecimal]
  )

  // Resolve the transfer unit price in `currency`. `landedUnitCost` is the specific lot's landed cost (the
  // cost basis); `resaleAnchor` is the downstream customer resale price (resale_minus only); `fixedPrice` is
  // assumed already in `currency` (the caller converts with a provenanced rate before calling). Rounds at the
  // currency's minor units with the given policy.
  def unitPrice(
      policy: Policy,
      landedUnitCost: BigDecimal,
      resaleAnchor: Option[BigDecimal],
      currency: Currency,
      rounding: RoundingPolicy
  ): Either[String, BigDecimal] =
    raw(policy, landedUnitCost, resaleAnchor).map(_.setScale(currency.minorUnits, rounding.mode))

  private def raw(
      policy: Policy,
      landedUnitCost: BigDecimal,
      resaleAnchor: Option[BigDecimal]
  ): Either[String, BigDecimal] =
    policy.method match {
      case Method.CostPlus =>
        policy.markupPct
          .toRight("cost_plus policy missing markup_pct")
          .map(m => landedUnitCost * (1 + m / 100))
      case Method.ResaleMinus =>
        (
          policy.resaleMarginPct.toRight("resale_minus policy missing resale_margin_pct"),
          resaleAnchor.toRight("resale_minus requires a downstream resale price")
        )
          .match2((m, anchor) => anchor * (1 - m / 100))
      case Method.Fixed =>
        policy.fixedPrice.toRight("fixed policy missing fixed_price")
    }

  // Small helper to combine two Eithers (avoids pulling cats into this pure file).
  private implicit class Tuple2EitherOps[E, A, B](t: (Either[E, A], Either[E, B])) {
    def match2[C](f: (A, B) => C): Either[E, C] =
      t match {
        case (Right(a), Right(b)) => Right(f(a, b))
        case (Left(e), _)         => Left(e)
        case (_, Left(e))         => Left(e)
      }
  }
}
