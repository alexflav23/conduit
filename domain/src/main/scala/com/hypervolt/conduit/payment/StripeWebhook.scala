package com.hypervolt.conduit.payment

import io.circe.Json
import io.circe.parser
import java.util.UUID
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

// The Stripe webhook event the consumer acts on. We only care about the two events that move the ledger:
// a successful charge (settles AR) and a payout landing in the bank (relieves the Stripe clearing account).
// Everything else is Ignored — the drain marks it 'ignored' (recorded, never settled). Amounts arrive as minor units
// (doc 14 §1.2 Money↔minor 1:1); we carry major-unit BigDecimal to match PaymentService's contract.
sealed trait StripeEvent { def eventId: String }
object StripeEvent {

  final case class PaymentSucceeded(
      eventId: String,
      paymentIntentId: String,
      invoiceNo: String,
      amount: BigDecimal,
      currency: String
  ) extends StripeEvent

  final case class PayoutPaid(
      eventId: String,
      payoutRef: String,
      entityId: UUID,
      currency: String,
      gross: BigDecimal,
      fee: BigDecimal
  ) extends StripeEvent

  final case class Ignored(eventId: String, eventType: String) extends StripeEvent
}

// Parses Stripe's event envelope (`{id, type, data: {object: {...}}}`) into a typed instruction. Pure: no SDK,
// no I/O — the signature check (StripeSignatureVerifier) has already gated authenticity upstream.
object StripeWebhook {

  private def minor(c: io.circe.ACursor, field: String): Option[BigDecimal] =
    c.downField(field).as[Long].toOption.map(l => BigDecimal(l).setScale(2, RoundingMode.HALF_UP) / 100)

  private def str(c: io.circe.ACursor, field: String): Option[String] =
    c.downField(field).as[String].toOption.filter(_.nonEmpty)

  def parse(rawBody: String): Either[String, StripeEvent] =
    parser.parse(rawBody).left.map(e => s"invalid json: ${e.getMessage}").flatMap(fromJson)

  // The webhook only needs the envelope (id, type, raw json) to record idempotently; the consumer does the
  // typed parse. Returns Left only for genuinely malformed input (a 400 to Stripe), not for events we don't act on.
  def envelope(rawBody: String): Either[String, (String, String, Json)] =
    parser.parse(rawBody).left.map(e => s"invalid json: ${e.getMessage}").flatMap { j =>
      val c = j.hcursor
      (str(c, "id"), str(c, "type")) match {
        case (Some(id), Some(tpe)) => Right((id, tpe, j))
        case _                     => Left("missing event id or type")
      }
    }

  def fromJson(envelope: Json): Either[String, StripeEvent] = {
    val root = envelope.hcursor
    (str(root, "id"), str(root, "type")) match {
      case (None, _) => Left("missing event id")
      case (_, None) => Left("missing event type")
      case (Some(id), Some(tpe)) =>
        val obj = root.downField("data").downField("object")
        tpe match {
          case "payment_intent.succeeded" => paymentSucceeded(id, obj)
          case "charge.succeeded"         => paymentSucceeded(id, obj)
          case "payout.paid"              => payoutPaid(id, obj)
          case other                      => Right(StripeEvent.Ignored(id, other))
        }
    }
  }

  private def paymentSucceeded(id: String, obj: io.circe.ACursor): Either[String, StripeEvent] = {
    val meta      = obj.downField("metadata")
    val amount    = minor(obj, "amount_received").orElse(minor(obj, "amount"))
    val invoiceNo = str(meta, "invoice_no")
    (str(obj, "id"), invoiceNo, amount, str(obj, "currency")) match {
      case (Some(pi), Some(no), Some(amt), Some(ccy)) =>
        Right(StripeEvent.PaymentSucceeded(id, pi, no, amt, ccy.toUpperCase))
      case (_, None, _, _) => Left("payment has no metadata.invoice_no — cannot settle AR")
      case (_, _, None, _) => Left("payment has no amount")
      case _               => Left("payment missing id/currency")
    }
  }

  // Stripe's payout `amount` is the NET deposited (processing fees were already netted off the balance). To
  // relieve the clearing account by the gross it received from the charges, gross = net + fee.
  private def payoutPaid(id: String, obj: io.circe.ACursor): Either[String, StripeEvent] = {
    val meta     = obj.downField("metadata")
    val entityId = str(meta, "entity_id").flatMap(s => Try(UUID.fromString(s)).toOption)
    val fee      = minor(meta, "fee").getOrElse(BigDecimal(0))
    (str(obj, "id"), entityId, minor(obj, "amount"), str(obj, "currency")) match {
      case (Some(po), Some(ent), Some(net), Some(ccy)) =>
        Right(StripeEvent.PayoutPaid(id, po, ent, ccy.toUpperCase, net + fee, fee))
      case (_, None, _, _) => Left("payout has no metadata.entity_id — cannot map to a ledger entity")
      case _               => Left("payout missing id/amount/currency")
    }
  }
}
