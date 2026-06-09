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
    lines: List[PlaceLineInput],
    // A pending tier request (doc 24 §6.3): the order is held pending_ceo until the draft agreement is activated,
    // then released + re-quoted against the now-active tier. The ONLY way to a price that doesn't exist yet.
    draftAgreementId: Option[UUID] = None,
    // The stored customer-PO attachment this order was created from (doc 25 §4) — the provenance link.
    sourceAttachmentId: Option[UUID] = None
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
  final case class UnknownSku(sku: String) extends OrderError("unknown_sku", s"unknown sku: $sku")
  final case class NoPrice(sku: String)    extends OrderError("no_price", s"no active price for $sku")
  // doc 24 §3 — nobody types a price: a supplied price that differs from the resolved authorized tier price is
  // rejected outright (the only path to a new price is a governed tier request, §6).
  final case class NonTierPrice(sku: String)
      extends OrderError("non_tier_price", s"price for $sku is not the authorized tier price (doc 24 §3)")
  final case class BadTierRequest(detail: String)  extends OrderError("bad_tier_request", detail)
  final case class NotBillable(detail: String)     extends OrderError("not_billable", detail)
  final case class CreditBlocked(over: BigDecimal) extends OrderError("credit_block", s"credit limit exceeded by $over")
  final case class AmendRejected(detail: String)   extends OrderError("amend_rejected", detail)
  final case class NotFound(detail: String)        extends OrderError("not_found", detail)
}
