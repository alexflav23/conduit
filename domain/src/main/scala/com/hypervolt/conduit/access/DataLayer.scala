package com.hypervolt.conduit.access

// The data layers a field can belong to (doc 05 §3). The response serialiser strips fields whose layer
// is not in the principal's viewable layers — the units-vs-margins wall.
sealed abstract class DataLayer(val code: String)

object DataLayer {
  case object Volume        extends DataLayer("volume")        // units, coverage %, sell-through, stock counts
  case object Commercial    extends DataLayer("commercial")    // price, revenue
  case object Profitability extends DataLayer("profitability") // cost, margin, GP
  case object Commission    extends DataLayer("commission")    // agent amounts
  case object Pii           extends DataLayer("pii")           // contact details
  case object InterEntity   extends DataLayer("inter_entity")  // transfer prices, price_rule inter-entity
  case object Treasury      extends DataLayer("treasury")      // FX hedges, hedged rates, consolidated figures

  val all: List[DataLayer] =
    List(Volume, Commercial, Profitability, Commission, Pii, InterEntity, Treasury)

  def fromCode(code: String): Option[DataLayer] = all.find(_.code == code)
}
