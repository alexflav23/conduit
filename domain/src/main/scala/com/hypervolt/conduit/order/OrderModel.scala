package com.hypervolt.conduit.order

import java.time.LocalDate
import java.util.UUID

final case class TrancheInput(seq: Int, qty: Int, requestedDate: LocalDate)

final case class PlaceLineInput(sku: String, qty: Int, unitPriceExVat: Option[BigDecimal], schedule: List[TrancheInput])

final case class PlaceOrderInput(
    orderType: String,
    entityId: Option[UUID],
    soldToPartyId: UUID,
    billToPartyId: UUID,
    channelId: UUID,
    marketId: UUID,
    currency: String,
    paymentMethod: String,
    customerPoNumber: Option[String],
    requestedDelivery: Option[LocalDate],
    createdBy: Option[UUID],
    lines: List[PlaceLineInput]
)

final case class PlacedOrder(
    id: UUID,
    orderNo: String,
    status: String,
    adlpCategory: String,
    subtotalExVat: BigDecimal,
    vatTotal: BigDecimal,
    totalIncVat: BigDecimal
)

sealed abstract class OrderError(val code: String, val message: String)
object OrderError {
  final case class UnknownSku(sku: String)         extends OrderError("unknown_sku", s"unknown sku: $sku")
  final case class NoPrice(sku: String)            extends OrderError("no_price", s"no active price for $sku")
  final case class NotBillable(detail: String)     extends OrderError("not_billable", detail)
  final case class CreditBlocked(over: BigDecimal) extends OrderError("credit_block", s"credit limit exceeded by $over")
  final case class AmendRejected(detail: String)   extends OrderError("amend_rejected", detail)
  final case class NotFound(detail: String)        extends OrderError("not_found", detail)
}
