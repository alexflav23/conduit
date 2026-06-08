package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.payment.PaymentService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13 — payment / cash application (doc 13 §payments). A payment settles AR on the immutable ledger
// (DR cash/clearing, CR AR), allocates to the invoice and flips its status; idempotent on the source ref;
// partials accumulate; Stripe payouts relieve the clearing into bank net of fees.
object PaymentSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  type Client = com.tigerbeetle.Client

  // An entity + bill-to + an issued invoice with an AR debit already posted (as recognition would).
  private def invoiceWithAr(
      xa: HikariTransactor[IO],
      ledger: TigerBeetleLedger[IO],
      total: BigDecimal
  ): IO[(UUID, UUID, String)] =
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Pay Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                        VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', $total) RETURNING id"""
              .query[UUID]
              .unique
          no = s"INV-${UUID.randomUUID()}"
          _ <-
            sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status) VALUES ($ord, $no, $total, 0, $total, 'open')".update.run
        } yield (e, billTo, no)).transact(xa)
      (e, billTo, no) = ids
      // post the AR debit (DR AR / CR Revenue) so there is a receivable to settle
      arAcc  = TbIds.accountId(s"AR:$billTo")
      revAcc = TbIds.accountId(s"REVENUE:$e")
      _ <- ledger.createAccounts(
        List(
          com.hypervolt.conduit.ledger.LedgerAccount(
            arAcc,
            com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.fromCode("GBP").get),
            com.hypervolt.conduit.ledger.LedgerAccountCode.Ar
          ),
          com.hypervolt.conduit.ledger.LedgerAccount(
            revAcc,
            com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.fromCode("GBP").get),
            com.hypervolt.conduit.ledger.LedgerAccountCode.Revenue
          )
        )
      )
      _ <- ledger.postTransfers(
        List(
          com.hypervolt.conduit.ledger.LedgerTransfer(
            TbIds.transferId(UUID.randomUUID(), 0),
            arAcc,
            revAcc,
            (total.setScale(2) * 100).toBigInt,
            com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.fromCode("GBP").get),
            com.hypervolt.conduit.ledger.LedgerTransferCode.Generic
          )
        )
      )
    } yield (e, billTo, no)

  private def arBalance(ledger: TigerBeetleLedger[IO], billTo: UUID): IO[BigInt] =
    ledger.balance(TbIds.accountId(s"AR:$billTo")).map(b => b.debitsPosted - b.creditsPosted)

  test("a full payment settles AR to zero, marks the invoice paid, and is idempotent on the source ref") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val pay    = new PaymentService[IO](xa, ledger)
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1200.00"))
        (_, billTo, no) = s
        before   <- arBalance(ledger, billTo)
        r1       <- pay.apply(no, BigDecimal("1200.00"), "bank", Some(s"REMIT-$no"))
        r2       <- pay.apply(no, BigDecimal("1200.00"), "bank", Some(s"REMIT-$no")) // redelivery — must be a no-op
        after    <- arBalance(ledger, billTo)
        status   <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        payCount <- sql"SELECT count(*) FROM payment".query[Long].unique.transact(xa)
      } yield expect(before == BigInt(120000)) and                                                      // £1200 receivable
        expect(r1.isRight) and expect(r2.toOption.map(_.paymentId) == r1.toOption.map(_.paymentId)) and // idempotent
        expect(after == BigInt(0)) and                                                                  // AR fully settled
        expect(status == "paid") and
        expect(payCount == 1L) // exactly one payment despite two calls
  }

  test("partial payments accumulate: part_paid then paid; the cash waterfall drops the invoice once paid") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val pay    = new PaymentService[IO](xa, ledger)
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1000.00"))
        (_, billTo, no) = s
        r1     <- pay.apply(no, BigDecimal("400.00"), "bank", Some(s"P1-$no"))
        st1    <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        mid    <- arBalance(ledger, billTo)
        r2     <- pay.apply(no, BigDecimal("600.00"), "bank", Some(s"P2-$no"))
        st2    <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        endB   <- arBalance(ledger, billTo)
        openWf <- com.hypervolt.conduit.credit.CashWaterfallRepo.waterfall(Some("GBP")).transact(xa)
      } yield expect(r1.isRight && r2.isRight) and
        expect(st1 == "part_paid" && mid == BigInt(60000)) and // £600 still owed
        expect(st2 == "paid" && endB == BigInt(0)) and
        expect(
          !openWf.exists(_.hcursor.get[String]("due_month").toOption.isEmpty)
        ) // paid invoice no longer in the open waterfall (has no due_date row anyway)
  }

  test("Stripe payout relieves the clearing account into bank net of fees; clearing nets to zero") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val pay    = new PaymentService[IO](xa, ledger)
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1000.00"))
        (entity, _, no) = s
        _    <- pay.apply(no, BigDecimal("1000.00"), "stripe", Some(s"PI-$no")) // DR STRIPE_CLEARING / CR AR
        clr0 <- ledger.balance(TbIds.accountId(s"STRIPE_CLEARING:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        _    <- pay.recordPayout(s"PO-$no", entity, "GBP", BigDecimal("1000.00"), BigDecimal("29.00"))
        clr1 <- ledger.balance(TbIds.accountId(s"STRIPE_CLEARING:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        bank <- ledger.balance(TbIds.accountId(s"BANK:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        fee  <- ledger.balance(TbIds.accountId(s"FEE_EXPENSE:$entity")).map(b => b.debitsPosted - b.creditsPosted)
      } yield expect(clr0 == BigInt(100000)) and // gross sat in clearing
        expect(clr1 == BigInt(0)) and            // fully relieved on payout
        expect(bank == BigInt(97100)) and        // net to bank (£1000 − £29)
        expect(fee == BigInt(2900))              // fee to P&L
  }
}
