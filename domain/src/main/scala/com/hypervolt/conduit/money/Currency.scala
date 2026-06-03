package com.hypervolt.conduit.money

import scala.math.BigDecimal.RoundingMode

// ISO 4217 currency. `minorUnits` drives presentation rounding (2 for USD/EUR/GBP, 0 for JPY).
// Each is a distinct singleton type so `Money` can be phantom-typed by currency and cross-currency
// arithmetic becomes a compile error (doc 14 §1.1). Group presentation currency = USD.
sealed abstract class Currency(val code: String, val minorUnits: Int, val defaultRounding: RoundingMode.Value)

object Currency {
  case object USD extends Currency("USD", 2, RoundingMode.HALF_UP)
  case object GBP extends Currency("GBP", 2, RoundingMode.HALF_UP)
  case object EUR extends Currency("EUR", 2, RoundingMode.HALF_UP)
  case object CAD extends Currency("CAD", 2, RoundingMode.HALF_UP)
  case object CHF extends Currency("CHF", 2, RoundingMode.HALF_UP)
  case object PLN extends Currency("PLN", 2, RoundingMode.HALF_UP)
  case object NOK extends Currency("NOK", 2, RoundingMode.HALF_UP)
  case object SEK extends Currency("SEK", 2, RoundingMode.HALF_UP)
  case object DKK extends Currency("DKK", 2, RoundingMode.HALF_UP)
  case object JPY extends Currency("JPY", 0, RoundingMode.HALF_UP)
  case object AUD extends Currency("AUD", 2, RoundingMode.HALF_UP)
  case object NZD extends Currency("NZD", 2, RoundingMode.HALF_UP)
  case object THB extends Currency("THB", 2, RoundingMode.HALF_UP)

  val all: List[Currency] = List(USD, GBP, EUR, CAD, CHF, PLN, NOK, SEK, DKK, JPY, AUD, NZD, THB)

  def fromCode(code: String): Option[Currency] = all.find(_.code == code)
}
