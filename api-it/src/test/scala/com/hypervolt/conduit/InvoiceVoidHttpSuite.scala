package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.InvoiceVoidRoutes
import com.hypervolt.conduit.consumer.InvoiceVoidConsumer
import com.hypervolt.conduit.event.EventEnvelope
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
import com.hypervolt.conduit.revenue.InvoiceVoidProcessor
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.parser.{parse => parseJson}
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.headers.Authorization
import weaver.IOSuite

// M13-Void.4 — the void entry point. The API records the intent as invoice.void_requested (no TigerBeetle in the
// API); the consumer then performs the immutable reversal (+ cash refund). edit:order to void; a refund needs
// approve:order (maker-checker). This suite drives the real route, then the real consumer extractor + processor.
object InvoiceVoidHttpSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], com.tigerbeetle.Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

  private def voider(xa: HikariTransactor[IO], canApprove: Boolean): IO[String] = {
    val kc = s"voider-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Voider"))
      r   <- AdminRepo.createRole(s"voidrole-${UUID.randomUUID()}", Some("void"))
      _   <- AdminRepo.addPermission(r, "order", "edit", None, Nil, Nil, "all")
      _ <-
        if (canApprove) AdminRepo.addPermission(r, "order", "approve", None, Nil, Nil, "all").void
        else ().pure[doobie.ConnectionIO]
      _ <- AdminRepo.assign(uid, r, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // An invoice with AR posted (as recognition would) and a bank payment already applied (so a refund has cash).
  private def paidInvoice(xa: HikariTransactor[IO], ledger: TigerBeetleLedger[IO]): IO[(UUID, UUID, String, UUID)] =
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('VoidHttp Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                        VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1200.00) RETURNING id"""
              .query[UUID]
              .unique
          no = s"INV-${UUID.randomUUID()}"
          inv <-
            sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status) VALUES ($ord, $no, 1200.00, 0, 1200.00, 'open') RETURNING id"
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
            BigInt(120000),
            Ledgers.forCurrency(gbp),
            LedgerTransferCode.Generic
          )
        )
      )
      _ <- new PaymentService[IO](xa, ledger).apply(no, BigDecimal("1200.00"), "bank", Some(s"PMT-$no"))
    } yield (e, billTo, no, inv)

  private def post(routes: org.http4s.HttpRoutes[IO], no: String, kc: String, body: String): IO[(Int, String)] =
    routes.orNotFound
      .run(
        Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/invoices/$no/void"))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
          .withEntity(parseJson(body).toOption.get)
      )
      .flatMap(r => r.bodyText.compile.string.map(b => (r.status.code, b)))

  test(
    "edit:order can request a void → 202 + invoice.void_requested in the outbox; the consumer then voids + refunds"
  ) {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val routes = new InvoiceVoidRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
      val processor =
        new InvoiceVoidProcessor[IO](xa, new InvoiceReversalService[IO](xa, ledger), new PaymentService[IO](xa, ledger))
      for {
        kc <- voider(xa, canApprove = true)
        s  <- paidInvoice(xa, ledger)
        (entity, billTo, no, inv) = s
        arPaid    <- ledger.balance(TbIds.accountId(s"AR:$billTo")).map(b => b.debitsPosted - b.creditsPosted)
        (code, _) <- post(routes, no, kc, """{"kind":"refund","reason":"customer returned the unit"}""")
        evtRow <-
          sql"""SELECT payload::text FROM outbox_event WHERE event_type='invoice.void_requested' AND payload->>'invoice_no' = $no LIMIT 1"""
            .query[String]
            .unique
            .transact(xa)
        // replay what the consumer would do off that event
        env = EventEnvelope(
          UUID.randomUUID().toString,
          "invoice.void_requested",
          1,
          "order",
          UUID.randomUUID().toString,
          "k",
          None,
          None,
          None,
          "relay",
          0L,
          evtRow.getBytes(StandardCharsets.UTF_8)
        )
        instr = InvoiceVoidConsumer.voidRequested(env).get
        _       <- processor.process(instr._1, instr._2, instr._3, instr._4, instr._5)
        arEnd   <- ledger.balance(TbIds.accountId(s"AR:$billTo")).map(b => b.debitsPosted - b.creditsPosted)
        bankEnd <- ledger.balance(TbIds.accountId(s"BANK:$entity")).map(b => b.debitsPosted - b.creditsPosted)
        status  <- sql"SELECT status FROM order_invoice WHERE id = $inv".query[String].unique.transact(xa)
      } yield expect(arPaid == BigInt(0)) and // paid: AR settled
        expect(code == 202) and
        expect(instr._1 == inv && instr._3 == "refund") and
        expect(arEnd == BigInt(0)) and   // recognition reversed (-) then refund (+) net 0
        expect(bankEnd == BigInt(0)) and // cash returned to the customer
        expect(status == "void")
  }

  test("a refund without approve:order is forbidden; a plain void with edit:order is allowed") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val routes = new InvoiceVoidRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
      for {
        kc <- voider(xa, canApprove = false)
        s  <- paidInvoice(xa, ledger)
        (_, _, no, _) = s
        (refundCode, _) <- post(routes, no, kc, """{"kind":"refund","reason":"x"}""")
        (voidCode, _)   <- post(routes, no, kc, """{"kind":"mistake","reason":"wrong line"}""")
        (badKind, _)    <- post(routes, no, kc, """{"kind":"explode","reason":"x"}""")
      } yield expect(refundCode == 403) and expect(voidCode == 202) and expect(badKind == 400)
  }
}
