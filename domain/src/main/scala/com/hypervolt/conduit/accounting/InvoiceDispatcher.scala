package com.hypervolt.conduit.accounting

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.IdempotentConsumer
import com.hypervolt.conduit.shadow.ShadowGuard
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import org.typelevel.log4cats.slf4j.Slf4jLogger

// The order.invoiced → accounting-system step (doc 03: order.invoiced → Xero). Transport-agnostic: a Pulsar
// consumer hands it the event_id + invoice_no; it idempotently builds the invoice and pushes it to whichever
// AccountingConsumer is wired (Xero today), then stamps the returned external id onto order_invoice. Exactly
// once per event_id via the shared dedupe store; safe under at-least-once redelivery.
final class InvoiceDispatcher[F[_]: Async](
    xa: Transactor[F],
    consumer: AccountingConsumer[F],
    consumerGroup: String = "conduit-xero-invoicer-1",
    shadow: ShadowGuard[F]
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val idem   = new IdempotentConsumer[F](xa, consumerGroup)

  // Returns true if this delivery did the work (first time), false if already processed.
  def handle(eventId: UUID, invoiceNo: String): F[Boolean] =
    idem.process(eventId)(push(invoiceNo))

  // invoice.voided → void the invoice in the external system, so the ERP stops counting/dunning it (doc 13 §void).
  // Idempotent per event_id; uses the stamped external id when present, else the Conduit invoice_no.
  def handleVoid(eventId: UUID, invoiceNo: String, reason: String): F[Boolean] =
    idem.process(eventId)(voidExternal(invoiceNo, reason))

  private def voidExternal(invoiceNo: String, reason: String): F[Unit] =
    InvoiceProjectionRepo.externalId(invoiceNo).transact(xa).flatMap { ext =>
      // Outbound: muted in shadow (recorded, not sent) — the void never reaches the accounting system.
      shadow
        .outbound("xero.invoice.void", invoiceNo, Json.obj("reason" -> reason.asJson))(
          consumer.voidInvoice(ext.getOrElse(invoiceNo), invoiceNo, reason)
        )
        .flatMap {
          case None           => logger.info(s"[shadow] suppressed accounting void for $invoiceNo")
          case Some(Right(_)) => logger.info(s"invoice $invoiceNo voided in the accounting system")
          case Some(Left(msg)) =>
            Async[F].raiseError(new RuntimeException(s"accounting void failed for $invoiceNo: $msg"))
        }
    }

  private def push(invoiceNo: String): F[Unit] =
    InvoiceProjectionRepo.load(invoiceNo).transact(xa).flatMap {
      case None =>
        logger.warn(s"order.invoiced for unknown invoice_no=$invoiceNo — skipping")
      case Some(req) =>
        // Outbound: the Xero create is muted in shadow; the external-id stamp only happens on a real push.
        shadow
          .outbound("xero.invoice.create", invoiceNo, Json.obj("invoice_no" -> invoiceNo.asJson))(
            consumer.createInvoice(req)
          )
          .flatMap {
            case None => logger.info(s"[shadow] suppressed accounting push for $invoiceNo")
            case Some(Right(externalId)) =>
              InvoiceProjectionRepo.setExternalId(invoiceNo, externalId).transact(xa).void *>
                logger.info(s"invoice $invoiceNo pushed to accounting system as $externalId")
            case Some(Left(msg)) =>
              logger.error(s"accounting consumer rejected invoice $invoiceNo: $msg") *>
                Async[F].raiseError(new RuntimeException(s"accounting push failed for $invoiceNo: $msg"))
          }
    }
}
