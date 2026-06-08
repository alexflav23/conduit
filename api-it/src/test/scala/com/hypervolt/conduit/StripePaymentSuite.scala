package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.payment.StripePaymentHandler
import com.hypervolt.conduit.payment.StripeWebhook
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M13-Pay.2 — the Stripe webhook drives a REAL ledger settlement end-to-end (doc 13 §payments). A verified
// payment_intent.succeeded → parser → handler → PaymentService: DR Stripe clearing / CR AR, invoice flips to
// paid. A redelivered webhook is a no-op (idempotent on the Stripe ref). A payout.paid relieves the clearing
// into bank net of fees. The API never touches TigerBeetle — this settlement runs in the consumer process.
object StripePaymentSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], com.tigerbeetle.Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

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
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Stripe Cust','wholesaler',true) RETURNING id"
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
      arAcc           = TbIds.accountId(s"AR:$billTo")
      revAcc          = TbIds.accountId(s"REVENUE:$e")
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
    } yield (e, billTo, no)

  private def arBalance(ledger: TigerBeetleLedger[IO], billTo: UUID): IO[BigInt] =
    ledger.balance(TbIds.accountId(s"AR:$billTo")).map(b => b.debitsPosted - b.creditsPosted)

  private def chargeWebhook(eventId: String, intentId: String, invoiceNo: String, amountMinor: Long): String =
    s"""{"id":"$eventId","type":"payment_intent.succeeded","data":{"object":{
       |  "id":"$intentId","object":"payment_intent","amount_received":$amountMinor,"currency":"gbp",
       |  "metadata":{"invoice_no":"$invoiceNo"}}}}""".stripMargin

  test("a Stripe charge webhook settles AR end-to-end and a redelivery is idempotent") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val handler = new StripePaymentHandler[IO](new PaymentService[IO](xa, ledger))
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1200.00"))
        (entity, billTo, no) = s
        evt                  = StripeWebhook.parse(chargeWebhook("evt_a", "pi_abc", no, 120000L)).toOption.get
        r1       <- handler.handle(evt)
        r2       <- handler.handle(evt) // redelivery — must be a no-op
        ar       <- arBalance(ledger, billTo)
        clr      <- ledger.balance(TbIds.accountId(s"STRIPE_CLEARING:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        status   <- sql"SELECT status FROM order_invoice WHERE invoice_no = $no".query[String].unique.transact(xa)
        payCount <- sql"SELECT count(*) FROM payment".query[Long].unique.transact(xa)
      } yield expect(r1.isRight) and expect(r2.isRight) and
        expect(ar == BigInt(0)) and       // AR fully settled
        expect(clr == BigInt(120000)) and // cash sat in Stripe clearing
        expect(status == "paid") and
        expect(payCount == 1L) // idempotent: one payment despite two webhooks
  }

  test("a payout.paid webhook relieves the Stripe clearing into bank net of fees") {
    case (xa, client) =>
      val ledger  = TigerBeetleLedger.fromClient[IO](client)
      val handler = new StripePaymentHandler[IO](new PaymentService[IO](xa, ledger))
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1000.00"))
        (entity, _, no) = s
        _ <- handler.handle(StripeWebhook.parse(chargeWebhook("evt_b", "pi_def", no, 100000L)).toOption.get)
        payout = s"""{"id":"evt_c","type":"payout.paid","data":{"object":{
             |  "id":"po_1","amount":97100,"currency":"gbp",
             |  "metadata":{"entity_id":"$entity","fee":2900}}}}""".stripMargin
        r    <- handler.handle(StripeWebhook.parse(payout).toOption.get)
        clr  <- ledger.balance(TbIds.accountId(s"STRIPE_CLEARING:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        bank <- ledger.balance(TbIds.accountId(s"BANK:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        fee  <- ledger.balance(TbIds.accountId(s"FEE_EXPENSE:$entity")).map(b => b.debitsPosted - b.creditsPosted)
      } yield expect(r.isRight) and
        expect(clr == BigInt(0)) and // clearing fully relieved
        expect(bank == BigInt(97100)) and
        expect(fee == BigInt(2900))
  }
}
