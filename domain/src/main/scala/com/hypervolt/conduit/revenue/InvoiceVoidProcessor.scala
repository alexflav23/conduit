package com.hypervolt.conduit.revenue

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.payment.PaymentService
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// Orchestrates an invoice.void_requested (doc 13 §void), consumer-side (owns TigerBeetle). Always reverses the
// recognition (InvoiceReversalService); when the kind is `refund` and cash was actually received, it also returns
// that cash (PaymentService.refund) so AR nets back to zero and the money leaves the bank. Both legs are
// idempotent on deterministic ids, so a redelivered request is a no-op.
final class InvoiceVoidProcessor[F[_]: Async](
    xa: Transactor[F],
    reversal: InvoiceReversalService[F],
    payments: PaymentService[F]
) {

  def process(
      orderInvoiceId: UUID,
      invoiceNo: String,
      kind: String,
      reason: String,
      actor: String,
      causedBy: Option[UUID] = None
  ): F[Either[String, Unit]] = {
    // One correlation threads the whole cycle (= the reversal/invoice.voided id); causation links each step to it.
    val corr = CollectionCycle.correlationId(orderInvoiceId)
    reversal.reverse(orderInvoiceId, kind, reason, actor, causedBy).flatMap {
      case Left(m) => m.asLeft[Unit].pure[F]
      case Right(_) =>
        if (kind != "refund") ().asRight[String].pure[F]
        else
          settledCash(orderInvoiceId).transact(xa).flatMap {
            case Some((net, method)) if net > 0 =>
              payments
                .refund(invoiceNo, net, method, s"void:$orderInvoiceId", Some(corr), Some(corr))
                .map(_.map(_ => ()))
            case _ => ().asRight[String].pure[F] // nothing was paid — recognition reversal is enough
          }
    }
  }

  // Net cash applied to the invoice + the method it came in on (to return it the same way). None if never paid.
  private def settledCash(orderInvoiceId: UUID): ConnectionIO[Option[(BigDecimal, String)]] =
    (
      sql"SELECT COALESCE(SUM(amount),0) FROM payment_allocation WHERE order_invoice_id = $orderInvoiceId"
        .query[BigDecimal]
        .unique,
      sql"""SELECT p.method FROM payment p JOIN payment_allocation a ON a.payment_id = p.id
            WHERE a.order_invoice_id = $orderInvoiceId AND p.status = 'applied' LIMIT 1"""
        .query[String]
        .option
    ).tupled.map { case (net, method) => method.map(m => (net, m)) }
}
