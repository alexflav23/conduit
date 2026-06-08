package com.hypervolt.conduit.payment

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor

// Consumer-side drain (doc 13 §payments): pull recorded-but-unprocessed Stripe events and settle each on the
// ledger via the handler, then mark its outcome. Runs in the consumer process (which owns TigerBeetle). Settlement
// is idempotent (PaymentService keys on the Stripe ref), so a crash between settle and mark is safe — the next
// pass re-settles to the same no-op and marks. Returns the number of rows it advanced this pass.
final class StripeInboundProcessor[F[_]: Async](xa: Transactor[F], handler: StripePaymentHandler[F], batch: Int = 50) {

  def runOnce(): F[Int] =
    StripeInboundRepo.fetchUnprocessed(batch).transact(xa).flatMap(_.traverse(processOne)).map(_.sum)

  private def processOne(row: (String, io.circe.Json)): F[Int] =
    StripeWebhook.fromJson(row._2) match {
      case Left(err) => StripeInboundRepo.markFailed(row._1, err).transact(xa)
      case Right(StripeEvent.Ignored(_, tpe)) =>
        StripeInboundRepo.markIgnored(row._1, s"ignored $tpe").transact(xa)
      case Right(event) =>
        handler.handle(event).flatMap {
          case Right(result) => StripeInboundRepo.markProcessed(row._1, result).transact(xa)
          case Left(err)     => StripeInboundRepo.markFailed(row._1, err).transact(xa)
        }
    }
}
