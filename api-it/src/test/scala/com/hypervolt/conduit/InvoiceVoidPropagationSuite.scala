package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.accounting.AccountingConsumer
import com.hypervolt.conduit.accounting.InvoiceDispatcher
import com.hypervolt.conduit.accounting.InvoiceRequest
import com.hypervolt.conduit.shadow.ShadowGuard
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13-Void.3 — invalidation propagates beyond the ledger: a refund returns cash (the reverse of settlement), a
// voided invoice drops out of the open-AR read-models, and the void fans out to the ERP (Xero) so it stops
// counting the invoice. All idempotent.
object InvoiceVoidPropagationSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], com.tigerbeetle.Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

  // An issued invoice with the AR debit already posted (as recognition would). Returns (entity, billTo, no, invId).
  private def invoiceWithAr(
      xa: HikariTransactor[IO],
      ledger: TigerBeetleLedger[IO],
      total: BigDecimal
  ): IO[(UUID, UUID, String, UUID)] =
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Refund Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                        VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', $total) RETURNING id"""
              .query[UUID]
              .unique
          no = s"INV-${UUID.randomUUID()}"
          inv <-
            sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status, due_date) VALUES ($ord, $no, $total, 0, $total, 'open', current_date + 30) RETURNING id"
              .query[UUID]
              .unique
        } yield (e, billTo, no, inv)).transact(xa)
      (e, billTo, no, inv) = ids
      arAcc                = TbIds.accountId(s"AR:$billTo")
      revAcc               = TbIds.accountId(s"REVENUE:$e")
      _ <- ledger.createAccounts(
        List(
          LedgerAccount(arAcc, Ledgers.forCurrency(gbp), LedgerAccountCode.Ar),
          LedgerAccount(revAcc, Ledgers.forCurrency(gbp), LedgerAccountCode.Revenue)
        )
      )
      _ <- ledger.postTransfers(
        List(
          LedgerTransfer(
            TbIds.transferId(UUID.randomUUID(), 0),
            arAcc,
            revAcc,
            (total.setScale(2) * 100).toBigInt,
            Ledgers.forCurrency(gbp),
            LedgerTransferCode.Generic
          )
        )
      )
    } yield (e, billTo, no, inv)

  private def bal(ledger: TigerBeetleLedger[IO], acc: BigInt): IO[BigInt] =
    ledger.balance(acc).map(b => b.debitsPosted - b.creditsPosted)

  test("a refund returns cash: it reverses the settlement (DR AR / CR bank) and recomputes the invoice, idempotently") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val pay    = new PaymentService[IO](xa, ledger)
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1200.00"))
        (entity, billTo, no, _) = s
        _        <- pay.apply(no, BigDecimal("1200.00"), "bank", Some(s"PMT-$no"))
        arPaid   <- bal(ledger, TbIds.accountId(s"AR:$billTo"))
        stPaid   <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        r1       <- pay.refund(no, BigDecimal("1200.00"), "bank", s"RF-$no")
        r2       <- pay.refund(no, BigDecimal("1200.00"), "bank", s"RF-$no") // redelivery — no-op
        arBack   <- bal(ledger, TbIds.accountId(s"AR:$billTo"))
        bankBack <- bal(ledger, TbIds.accountId(s"BANK:$entity"))
        stBack   <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        payRows  <- sql"SELECT count(*) FROM payment WHERE method='refund'".query[Long].unique.transact(xa)
      } yield expect(arPaid == BigInt(0) && stPaid == "paid") and // settled
        expect(r1.isRight && r2.isRight) and
        expect(r1.toOption.map(_.paymentId) == r2.toOption.map(_.paymentId)) and // idempotent
        expect(arBack == BigInt(120000)) and                                     // receivable restored
        expect(bankBack == BigInt(0)) and                                        // cash returned
        expect(stBack == "open") and                                             // fully unwound → open again
        expect(payRows == 1L)                                                    // one refund despite two calls
  }

  test("a voided invoice drops out of the open-AR read-model (status filter)") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val voider = new InvoiceReversalService[IO](xa, ledger)
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("500.00"))
        (_, _, _, inv) = s
        openBefore <-
          sql"SELECT count(*) FROM order_invoice WHERE id = $inv AND status IN ('open','part_paid')"
            .query[Long]
            .unique
            .transact(xa)
        _ <- voider.reverse(inv, "cancellation", "customer cancelled", "finance:e2e")
        openAfter <-
          sql"SELECT count(*) FROM order_invoice WHERE id = $inv AND status IN ('open','part_paid')"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(openBefore == 1L) and expect(openAfter == 0L)
  }

  test("invoice.voided fans out to the ERP: the dispatcher voids the external invoice exactly once") {
    case (xa, _) =>
      Ref.of[IO, List[String]](Nil).flatMap { recorded =>
        val fake = new AccountingConsumer[IO] {
          def createInvoice(req: InvoiceRequest): IO[Either[String, String]] = IO.pure(Right("X"))
          def voidInvoice(externalRef: String, invoiceNo: String, reason: String): IO[Either[String, Unit]] =
            recorded.update(_ :+ invoiceNo).as(Right(()))
        }
        val dispatcher =
          new InvoiceDispatcher[IO](xa, fake, s"void-group-${UUID.randomUUID()}", ShadowGuard.disabled[IO])
        val eid = UUID.randomUUID()
        for {
          d1   <- dispatcher.handleVoid(eid, "INV-XERO-1", "mistake")
          d2   <- dispatcher.handleVoid(eid, "INV-XERO-1", "mistake") // redelivery — deduped on event_id
          logd <- recorded.get
        } yield expect(d1) and expect(!d2) and expect(logd == List("INV-XERO-1"))
      }
  }
}
