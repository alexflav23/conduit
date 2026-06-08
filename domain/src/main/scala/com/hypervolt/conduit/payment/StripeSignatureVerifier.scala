package com.hypervolt.conduit.payment

import com.stripe.exception.SignatureVerificationException
import com.stripe.net.Webhook.Signature
import scala.util.Failure
import scala.util.Success
import scala.util.Try

// Ported from Athena (api/.../stripe/StripeSignatureVerifier): verify the Stripe-Signature header against the
// endpoint's signing secret using Stripe's own HMAC check, with a 5-minute tolerance (replay defence). The
// webhook is a PUBLIC endpoint, so this is the only thing standing between the internet and a ledger credit —
// an unverified payload must never reach PaymentService.
sealed trait SignatureValidity
object SignatureValidity {
  case object Valid                        extends SignatureValidity
  final case class Invalid(reason: String) extends SignatureValidity
}

trait SignatureVerifier {
  def verify(payload: String, signatureHeader: String): SignatureValidity
}

final class StripeSignatureVerifier(webhookSecret: String, toleranceSeconds: Long = 300L) extends SignatureVerifier {

  def verify(payload: String, signatureHeader: String): SignatureValidity =
    Try(Signature.verifyHeader(payload, signatureHeader, webhookSecret, toleranceSeconds)) match {
      case Success(true)                              => SignatureValidity.Valid
      case Success(false)                             => SignatureValidity.Invalid("signature mismatch")
      case Failure(e: SignatureVerificationException) => SignatureValidity.Invalid(e.getMessage)
      case Failure(t)                                 => SignatureValidity.Invalid(t.getMessage)
    }
}
