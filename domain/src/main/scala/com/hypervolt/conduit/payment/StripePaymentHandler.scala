package com.hypervolt.conduit.payment

import cats.effect.Async
import cats.syntax.all._

// Bridges a verified, parsed Stripe event to the ledger settlement (doc 13 §payments). Runs in the consumer
// process, which owns the TigerBeetle client — the API never touches TB. PaymentService is already idempotent
// on the Stripe ref (deterministic payment/transfer ids), so a redelivered webhook is a no-op: this handler
// adds no dedupe of its own. A charge settles AR; a payout relieves the Stripe clearing into bank net of fees.
final class StripePaymentHandler[F[_]: Async](payments: PaymentService[F]) {

  def handle(event: StripeEvent): F[Either[String, String]] =
    event match {
      case StripeEvent.PaymentSucceeded(_, pi, invoiceNo, amount, _) =>
        payments
          .apply(invoiceNo, amount, "stripe", Some(pi))
          .map(_.bimap(m => s"$pi: $m", r => s"settled $invoiceNo via $pi → ${r.invoiceStatus}"))
          .recover(t => Left(s"$pi: ${t.getMessage}"))

      case StripeEvent.PayoutPaid(_, payoutRef, entityId, currency, gross, fee) =>
        payments
          .recordPayout(payoutRef, entityId, currency, gross, fee)
          .map(_.bimap(m => s"$payoutRef: $m", _ => s"payout $payoutRef relieved clearing"))
          .recover(t => Left(s"$payoutRef: ${t.getMessage}"))

      case StripeEvent.Ignored(id, tpe) =>
        s"ignored $tpe ($id)".asRight[String].pure[F]
    }
}
