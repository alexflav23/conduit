package com.hypervolt.conduit.payment

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class PaymentResult(
    paymentId: UUID,
    invoiceNo: String,
    applied: BigDecimal,
    invoiceStatus: String,
    transferId: BigInt
)

private final case class InvHead(
    invoiceId: UUID,
    orderId: UUID,
    entity: Option[UUID],
    billTo: UUID,
    currency: String,
    total: BigDecimal,
    status: String
)

// Cash application (doc 13 §payments): a payment settles AR on the immutable ledger — DR cash/clearing, CR
// AR:<bill_to> — allocates to the invoice and flips it open→part_paid→paid. Conduit owns this so the full
// order→cash lifecycle (DSO, collection performance) is ours to analyse, not just mirrored from the ERP.
// Idempotent on the source ref (deterministic payment id + transfer id), so a redelivered webhook never
// double-credits AR. Stripe is one source feeding `apply`; bank/manual use the same path.
final class PaymentService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val zero = new UUID(0L, 0L)

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt
  private def paymentId(externalRef: Option[String]): UUID =
    externalRef
      .map(r => UUID.nameUUIDFromBytes(s"payment:$r".getBytes(StandardCharsets.UTF_8)))
      .getOrElse(UUID.randomUUID())

  // method → which asset account received the cash (Stripe lands in a clearing account; bank/card hit the bank).
  private def accountKind(method: String): String = if (method == "stripe") "stripe_clearing" else "bank"
  private def cashAccount(kind: String, entity: UUID): BigInt =
    if (kind == "stripe_clearing") TbIds.accountId(s"STRIPE_CLEARING:$entity") else TbIds.accountId(s"BANK:$entity")
  private def cashCode(kind: String): Int =
    if (kind == "stripe_clearing") LedgerAccountCode.StripeClearing else LedgerAccountCode.Bank
  private def ar(party: UUID): BigInt = TbIds.accountId(s"AR:$party")

  def apply(
      invoiceNo: String,
      amount: BigDecimal,
      method: String,
      externalRef: Option[String]
  ): F[Either[String, PaymentResult]] = {
    val pid = paymentId(externalRef)
    existing(pid).transact(xa).flatMap {
      case Some(r)             => r.asRight[String].pure[F] // idempotent: this source ref already applied
      case None if amount <= 0 => "amount must be > 0".asLeft[PaymentResult].pure[F]
      case None =>
        head(invoiceNo).transact(xa).flatMap {
          case None => s"unknown invoice $invoiceNo".asLeft[PaymentResult].pure[F]
          case Some(h) =>
            Currency.fromCode(h.currency) match {
              case None => s"unknown currency ${h.currency}".asLeft[PaymentResult].pure[F]
              case Some(ccy) =>
                val entity   = h.entity.getOrElse(zero)
                val kind     = accountKind(method)
                val ledgerId = Ledgers.forCurrency(ccy)
                val cashAcc  = cashAccount(kind, entity)
                val arAcc    = ar(h.billTo)
                val tId      = TbIds.transferId(pid, 0)
                val accounts = List(
                  LedgerAccount(cashAcc, ledgerId, cashCode(kind)),
                  LedgerAccount(arAcc, ledgerId, LedgerAccountCode.Ar)
                )
                val transfer = LedgerTransfer(tId, cashAcc, arAcc, minor(amount), ledgerId, LedgerTransferCode.Payment)
                ledger.createAccounts(accounts) *>
                  ledger.postTransfers(List(transfer)) *>
                  record(pid, invoiceNo, h, amount, method, kind, externalRef, tId).transact(xa)
            }
        }
    }
  }

  // Stripe payout: relieve the clearing account into the bank net of fees (fees → P&L). Clearing nets to zero.
  def recordPayout(
      payoutRef: String,
      entity: UUID,
      currency: String,
      gross: BigDecimal,
      fee: BigDecimal
  ): F[Either[String, Unit]] =
    Currency.fromCode(currency) match {
      case None => s"unknown currency $currency".asLeft[Unit].pure[F]
      case Some(ccy) =>
        val ledgerId = Ledgers.forCurrency(ccy)
        val clearing = TbIds.accountId(s"STRIPE_CLEARING:$entity")
        val bank     = TbIds.accountId(s"BANK:$entity")
        val feeAcc   = TbIds.accountId(s"FEE_EXPENSE:$entity")
        val ev       = UUID.nameUUIDFromBytes(s"payout:$payoutRef".getBytes(StandardCharsets.UTF_8))
        val accounts = List(
          LedgerAccount(clearing, ledgerId, LedgerAccountCode.StripeClearing),
          LedgerAccount(bank, ledgerId, LedgerAccountCode.Bank),
          LedgerAccount(feeAcc, ledgerId, LedgerAccountCode.FeeExpense)
        )
        val transfers = List(
          LedgerTransfer(
            TbIds.transferId(ev, 0),
            bank,
            clearing,
            minor(gross - fee),
            ledgerId,
            LedgerTransferCode.Payment
          ),
          LedgerTransfer(TbIds.transferId(ev, 1), feeAcc, clearing, minor(fee), ledgerId, LedgerTransferCode.Payment)
        ).filter(_.amount > 0)
        ledger.createAccounts(accounts) *> ledger.postTransfers(transfers).as(().asRight[String])
    }

  // Refund (doc 13 §void): money goes back to the customer — the reverse of a settlement. DR AR:<bill_to> /
  // CR cash. Recorded as a negative payment so AR-aging/DSO reflect it; idempotent on the refund ref. Often paired
  // with an invoice void (kind=refund), but standalone partial refunds are supported too.
  def refund(
      invoiceNo: String,
      amount: BigDecimal,
      method: String,
      externalRef: String
  ): F[Either[String, PaymentResult]] = {
    val pid = paymentId(Some(s"refund:$externalRef"))
    existing(pid).transact(xa).flatMap {
      case Some(r)             => r.asRight[String].pure[F] // idempotent: this refund already applied
      case None if amount <= 0 => "refund amount must be > 0".asLeft[PaymentResult].pure[F]
      case None =>
        head(invoiceNo).transact(xa).flatMap {
          case None => s"unknown invoice $invoiceNo".asLeft[PaymentResult].pure[F]
          case Some(h) =>
            Currency.fromCode(h.currency) match {
              case None => s"unknown currency ${h.currency}".asLeft[PaymentResult].pure[F]
              case Some(ccy) =>
                val entity   = h.entity.getOrElse(zero)
                val kind     = accountKind(method)
                val ledgerId = Ledgers.forCurrency(ccy)
                val cashAcc  = cashAccount(kind, entity)
                val arAcc    = ar(h.billTo)
                val tId      = TbIds.transferId(pid, 0)
                val accounts = List(
                  LedgerAccount(cashAcc, ledgerId, cashCode(kind)),
                  LedgerAccount(arAcc, ledgerId, LedgerAccountCode.Ar)
                )
                // reverse of settlement: DR AR / CR cash
                val transfer = LedgerTransfer(tId, arAcc, cashAcc, minor(amount), ledgerId, LedgerTransferCode.Reversal)
                ledger.createAccounts(accounts) *>
                  ledger.postTransfers(List(transfer)) *>
                  recordRefund(pid, invoiceNo, h, amount, kind, externalRef, tId).transact(xa)
            }
        }
    }
  }

  // ----- persistence -----

  private def existing(pid: UUID): ConnectionIO[Option[PaymentResult]] =
    sql"""SELECT p.id, COALESCE(oi.invoice_no,''), p.amount, COALESCE(oi.status,'open'), p.tb_transfer_id::text
          FROM payment p
            LEFT JOIN payment_allocation pa ON pa.payment_id = p.id
            LEFT JOIN order_invoice oi ON oi.id = pa.order_invoice_id
          WHERE p.id = $pid LIMIT 1"""
      .query[(UUID, String, BigDecimal, String, Option[String])]
      .option
      .map(_.map { case (id, no, amt, st, tid) => PaymentResult(id, no, amt, st, BigInt(tid.getOrElse("0"))) })

  private def head(invoiceNo: String): ConnectionIO[Option[InvHead]] =
    sql"""SELECT i.id, o.id, o.entity_id, o.bill_to_party_id, o.txn_currency, i.total_inc_vat, i.status
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id WHERE i.invoice_no = $invoiceNo
          ORDER BY i.issued_at DESC LIMIT 1"""
      .query[InvHead]
      .option

  private def record(
      pid: UUID,
      invoiceNo: String,
      h: InvHead,
      amount: BigDecimal,
      method: String,
      kind: String,
      externalRef: Option[String],
      tId: BigInt
  ): ConnectionIO[Either[String, PaymentResult]] =
    for {
      _ <-
        sql"""INSERT INTO payment (id, entity_id, bill_to_party_id, currency, amount, method, account_kind, external_ref, tb_transfer_id, status)
                 VALUES ($pid, ${h.entity}, ${h.billTo}, ${h.currency}, $amount, $method, $kind, $externalRef, ${tId.toString}::numeric, 'applied')""".update.run
      _ <-
        sql"INSERT INTO payment_allocation (payment_id, order_invoice_id, amount) VALUES ($pid, ${h.invoiceId}, $amount)".update.run
      allocated <-
        sql"SELECT COALESCE(SUM(amount),0) FROM payment_allocation WHERE order_invoice_id = ${h.invoiceId}"
          .query[BigDecimal]
          .unique
      newStatus = if (allocated >= h.total) "paid" else "part_paid"
      _ <-
        sql"""UPDATE order_invoice SET status = $newStatus, paid_at = CASE WHEN $newStatus = 'paid' THEN now() ELSE paid_at END
                 WHERE id = ${h.invoiceId}""".update.run
      _ <- OutboxRepo.append(event(pid, h, amount, newStatus, method))
    } yield PaymentResult(pid, invoiceNo, amount, newStatus, tId).asRight[String]

  // A refund is a NEGATIVE payment: it lowers the allocated total so AR-aging/DSO reflect the money returned.
  // The status recomputes from allocations, but never clobbers a 'void' invoice.
  private def recordRefund(
      pid: UUID,
      invoiceNo: String,
      h: InvHead,
      amount: BigDecimal,
      kind: String,
      externalRef: String,
      tId: BigInt
  ): ConnectionIO[Either[String, PaymentResult]] =
    for {
      _ <-
        sql"""INSERT INTO payment (id, entity_id, bill_to_party_id, currency, amount, method, account_kind, external_ref, tb_transfer_id, status)
                 VALUES ($pid, ${h.entity}, ${h.billTo}, ${h.currency}, ${-amount}, 'refund', $kind, ${s"refund:$externalRef"}, ${tId.toString}::numeric, 'refunded')""".update.run
      _ <-
        sql"INSERT INTO payment_allocation (payment_id, order_invoice_id, amount) VALUES ($pid, ${h.invoiceId}, ${-amount})".update.run
      allocated <-
        sql"SELECT COALESCE(SUM(amount),0) FROM payment_allocation WHERE order_invoice_id = ${h.invoiceId}"
          .query[BigDecimal]
          .unique
      newStatus = if (allocated >= h.total) "paid" else if (allocated <= 0) "open" else "part_paid"
      _ <- sql"""UPDATE order_invoice
                 SET status = CASE WHEN status = 'void' THEN 'void' ELSE $newStatus END,
                     paid_at = CASE WHEN $newStatus = 'paid' THEN paid_at ELSE NULL END
                 WHERE id = ${h.invoiceId}""".update.run
      _ <- OutboxRepo.append(event(pid, h, -amount, newStatus, "refund"))
    } yield PaymentResult(pid, invoiceNo, -amount, newStatus, tId).asRight[String]

  private def event(pid: UUID, h: InvHead, amount: BigDecimal, status: String, method: String): OutboxEvent =
    OutboxEvent(
      pid,
      "payment.received",
      1,
      "order",
      h.orderId,
      h.orderId.toString,
      None,
      None,
      None,
      Json.obj(
        "payment_id"       -> pid.toString.asJson,
        "order_invoice_id" -> h.invoiceId.toString.asJson,
        "bill_to_party_id" -> h.billTo.toString.asJson,
        "amount"           -> amount.asJson,
        "currency"         -> h.currency.asJson,
        "method"           -> method.asJson,
        "invoice_status"   -> status.asJson
      ),
      Instant.now()
    )
}
