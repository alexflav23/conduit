package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.api.routes.StripeWebhookRoutes
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.payment.SignatureValidity
import com.hypervolt.conduit.payment.SignatureVerifier
import com.hypervolt.conduit.payment.StripeInboundProcessor
import com.hypervolt.conduit.payment.StripePaymentHandler
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import org.http4s.Method
import org.http4s.Request
import org.http4s.implicits._
import weaver.IOSuite

// M13-Pay.2 — the full public path: webhook route records the event idempotently (no ledger touch in the API),
// the consumer drain settles it on TigerBeetle. Signature gating rejects unverified payloads before any record.
object StripeWebhookRouteSuite extends IOSuite {

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
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Hook Cust','wholesaler',true) RETURNING id"
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

  private def charge(eventId: String, intentId: String, invoiceNo: String, minor: Long): String =
    s"""{"id":"$eventId","type":"payment_intent.succeeded","data":{"object":{
       |  "id":"$intentId","amount_received":$minor,"currency":"gbp","metadata":{"invoice_no":"$invoiceNo"}}}}""".stripMargin

  private def post(routes: org.http4s.HttpRoutes[IO], body: String): IO[Int] =
    routes.orNotFound.run(Request[IO](Method.POST, uri"/api/v1/stripe/webhook").withEntity(body)).map(_.status.code)

  test("webhook records then the drain settles AR; redelivery stays a single processed event") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val routes = new StripeWebhookRoutes[IO](xa, None).routes // dev: no secret → signature skipped
      val processor =
        new StripeInboundProcessor[IO](xa, new StripePaymentHandler[IO](new PaymentService[IO](xa, ledger)))
      for {
        s <- invoiceWithAr(xa, ledger, BigDecimal("1200.00"))
        (_, billTo, no) = s
        body            = charge("evt_hook_1", "pi_hook_1", no, 120000L)
        c1   <- post(routes, body)
        c2   <- post(routes, body)  // redelivery — recorded once (ON CONFLICT DO NOTHING)
        rows <- sql"SELECT count(*) FROM stripe_event WHERE id='evt_hook_1'".query[Long].unique.transact(xa)
        n    <- processor.runOnce()
        ar   <- ledger.balance(TbIds.accountId(s"AR:$billTo")).map(b => b.debitsPosted - b.creditsPosted)
        st   <- sql"SELECT status FROM order_invoice WHERE invoice_no=$no".query[String].unique.transact(xa)
        evSt <- sql"SELECT status FROM stripe_event WHERE id='evt_hook_1'".query[String].unique.transact(xa)
        n2   <- processor.runOnce() // nothing left to do
      } yield expect(c1 == 200) and expect(c2 == 200) and expect(rows == 1L) and
        expect(n == 1) and expect(ar == BigInt(0)) and expect(st == "paid") and
        expect(evSt == "processed") and expect(n2 == 0)
  }

  test("an invalid signature is rejected with 400 and nothing is recorded") {
    case (xa, _) =>
      val reject: SignatureVerifier = (_, _) => SignatureValidity.Invalid("bad sig")
      val routes                    = new StripeWebhookRoutes[IO](xa, Some(reject)).routes
      for {
        code <- post(routes, charge("evt_bad", "pi_bad", "INV-x", 100L))
        rows <- sql"SELECT count(*) FROM stripe_event WHERE id='evt_bad'".query[Long].unique.transact(xa)
      } yield expect(code == 400) and expect(rows == 0L)
  }
}
