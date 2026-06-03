package com.hypervolt.conduit.money

import java.time.LocalDate

// A provenanced FX rate (doc 14 §1.4). Typed by source/target currency so a conversion can only be
// applied to `Money` of the matching `from` currency. Every conversion that uses it therefore
// carries (rate, type, source, asOf) — no conversion is ever implicit or unprovenanced.
final case class FxRate[From <: Currency, To <: Currency](
    from: From,
    to: To,
    rate: BigDecimal,
    rateType: FxRateType,
    source: String,
    asOf: LocalDate
)

sealed abstract class FxRateType(val name: String)
object FxRateType {
  case object Spot    extends FxRateType("spot")
  case object Hedge   extends FxRateType("hedge")
  case object Closing extends FxRateType("closing")
  case object Average extends FxRateType("average")
}
