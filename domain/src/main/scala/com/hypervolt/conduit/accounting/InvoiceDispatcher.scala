package com.hypervolt.conduit.accounting

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.IdempotentConsumer
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID
import org.typelevel.log4cats.slf4j.Slf4jLogger

// The order.invoiced → accounting-system step (doc 03: order.invoiced → Xero). Transport-agnostic: a Pulsar
// consumer hands it the event_id + invoice_no; it idempotently builds the invoice and pushes it to whichever
// AccountingConsumer is wired (Xero today), then stamps the returned external id onto order_invoice. Exactly
// once per event_id via the shared dedupe store; safe under at-least-once redelivery.
final class InvoiceDispatcher[F[_]: Async](
    xa: Transactor[F],
    consumer: AccountingConsumer[F],
    consumerGroup: String = "conduit-xero-invoicer-1"
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val idem   = new IdempotentConsumer[F](xa, consumerGroup)

  // Returns true if this delivery did the work (first time), false if already processed.
  def handle(eventId: UUID, invoiceNo: String): F[Boolean] =
    idem.process(eventId)(push(invoiceNo))

  private def push(invoiceNo: String): F[Unit] =
    InvoiceProjectionRepo.load(invoiceNo).transact(xa).flatMap {
      case None =>
        logger.warn(s"order.invoiced for unknown invoice_no=$invoiceNo — skipping")
      case Some(req) =>
        consumer.createInvoice(req).flatMap {
          case Right(externalId) =>
            InvoiceProjectionRepo.setExternalId(invoiceNo, externalId).transact(xa).void *>
              logger.info(s"invoice $invoiceNo pushed to accounting system as $externalId")
          case Left(msg) =>
            logger.error(s"accounting consumer rejected invoice $invoiceNo: $msg") *>
              Async[F].raiseError(new RuntimeException(s"accounting push failed for $invoiceNo: $msg"))
        }
    }
}
