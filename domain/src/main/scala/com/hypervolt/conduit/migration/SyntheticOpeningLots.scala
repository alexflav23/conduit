package com.hypervolt.conduit.migration

import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Reconciling a weighted-average source to a specific-identification ledger (doc 18 §1.3, §2).
//
// MRPeasy carries `avg_cost` (weighted-average) and an aggregate inventory *value* per variant — Conduit is
// strict specific-identification (doc 02 §G). We do NOT import the average as a cost. Instead, where only an
// aggregate balance exists, we create ONE synthetic opening lot per variant/location and distribute the
// MRPeasy-reported total inventory value across the lots by weight (qty x reference cost) using the
// largest-remainder allocation (doc 14 §1.3). This makes Σ(opening value) tie to MRPeasy's reported value
// EXACTLY — to the penny — while honouring specific-ID going forward. Any sub-penny residue is handed to the
// largest fractional remainders, never dropped.
object SyntheticOpeningLots {

  // One synthetic opening lot to be created: a variant balance at a location with a reference unit cost
  // (MRPeasy avg_cost, used ONLY as an allocation weight — never as the booked cost).
  final case class OpeningLot(variantId: UUID, locationId: UUID, qty: Int, refUnitCost: BigDecimal)

  // The allocation result: the exact value this lot carries (its share of the reported total), the booked
  // per-unit landed cost (value / qty, at 4dp for storage) and the integer minor-unit amount the opening INV
  // transfer posts. The minor amounts sum to the reported total EXACTLY.
  final case class Allocated[C <: Currency](
      lot: OpeningLot,
      value: Money[C],
      landedUnitCost: BigDecimal,
      minorAmount: BigInt
  )

  // Distribute `reportedTotal` across `lots` by weight (qty x refUnitCost). Σ values == reportedTotal exactly.
  def reconcile[C <: Currency](reportedTotal: Money[C], lots: Vector[OpeningLot]): Vector[Allocated[C]] = {
    require(lots.nonEmpty, "at least one opening lot is required")
    val weights = lots.map(l => BigDecimal(l.qty) * l.refUnitCost)
    val values  = Money.allocate(reportedTotal, weights)
    val scale   = reportedTotal.currency.minorUnits
    val unit    = BigInt(10).pow(scale)
    lots.zip(values).map {
      case (l, v) =>
        val landed = if (l.qty == 0) BigDecimal(0) else (v.amount / BigDecimal(l.qty)).setScale(4, RoundingMode.HALF_UP)
        val minor  = (v.amount.setScale(scale, RoundingMode.HALF_UP) * BigDecimal(unit)).toBigInt
        Allocated(l, v, landed, minor)
    }
  }
}
