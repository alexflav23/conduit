package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.order.OrderLifecycleRepo
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M13-Void.5a — the Order Collection Ledger. The order is the root; the back-and-forth (invoice → collect → void →
// refund → re-invoice → collect) is REPLAYED from the immutable event stream + typed facts. This proves a full
// cycle is tracked against the order_id: the event timeline is ordered and complete, and the per-invoice cycles
// carry the right state (void/replaced-by/refunded vs paid).
object OrderLifecycleSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], com.tigerbeetle.Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

  // An order + an issued invoice with AR + recognition posted, so pay/void/refund all have something to act on.
  private def issuedInvoice(
      xa: HikariTransactor[IO],
      ledger: TigerBeetleLedger[IO],
      orderId: UUID,
      billTo: UUID,
      entity: UUID,
      total: BigDecimal
  ): IO[(String, UUID)] =
    for {
      no <- IO(s"INV-${UUID.randomUUID()}")
      inv <-
        sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status, due_date) VALUES ($orderId, $no, $total, 0, $total, 'open', current_date + 30) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"""INSERT INTO revenue_recognition (dispatch_id, order_id, invoice_no, currency, revenue_ex_vat, vat, cogs, gross_margin)
              VALUES (NULL, $orderId, $no, 'GBP', $total, 0, 0, $total)""".update.run.transact(xa).attempt.void
      _ <- ledger.createAccounts(
        List(
          LedgerAccount(TbIds.accountId(s"AR:$billTo"), Ledgers.forCurrency(gbp), LedgerAccountCode.Ar),
          LedgerAccount(TbIds.accountId(s"REVENUE:$entity"), Ledgers.forCurrency(gbp), LedgerAccountCode.Revenue)
        )
      )
      _ <- ledger.postTransfers(
        List(
          LedgerTransfer(
            TbIds.transferId(UUID.randomUUID(), 0),
            TbIds.accountId(s"AR:$billTo"),
            TbIds.accountId(s"REVENUE:$entity"),
            (total.setScale(2) * 100).toBigInt,
            Ledgers.forCurrency(gbp),
            LedgerTransferCode.Generic
          )
        )
      )
      _ <-
        OutboxRepo
          .append(
            OutboxEvent(
              UUID.randomUUID(),
              "order.invoiced",
              1,
              "order",
              orderId,
              orderId.toString,
              None,
              None,
              None,
              Json.obj("invoice_no" -> no.asJson, "order_invoice_id" -> inv.toString.asJson),
              Instant.now()
            )
          )
          .transact(xa)
    } yield (no, inv)

  test(
    "the collection ledger replays a full back-and-forth (invoice → pay → void → refund → re-invoice → pay) per order"
  ) {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val pay    = new PaymentService[IO](xa, ledger)
      val voider = new InvoiceReversalService[IO](xa, ledger)
      for {
        ids <- (for {
            e <-
              sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
                .query[UUID]
                .unique
            billTo <-
              sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cycle Cust','wholesaler',true) RETURNING id"
                .query[UUID]
                .unique
            ord <-
              sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                          VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1200.00) RETURNING id"""
                .query[UUID]
                .unique
          } yield (e, billTo, ord)).transact(xa)
        (entity, billTo, ord) = ids
        // ---- cycle 1: invoice, pay, then void (refund kind) + refund the cash ----
        c1 <- issuedInvoice(xa, ledger, ord, billTo, entity, BigDecimal("1200.00"))
        (no1, inv1) = c1
        _ <- pay.apply(no1, BigDecimal("1200.00"), "bank", Some(s"P1-$no1"))
        _ <- voider.reverse(inv1, "refund", "customer returned the unit", "finance:e2e")
        _ <- pay.refund(no1, BigDecimal("1200.00"), "bank", s"RF-$no1")
        // ---- cycle 2: re-invoice (replacement) + pay ----
        c2 <- issuedInvoice(xa, ledger, ord, billTo, entity, BigDecimal("1200.00"))
        (no2, _) = c2
        _ <-
          sql"UPDATE order_invoice SET replaced_by_invoice_id = (SELECT id FROM order_invoice WHERE invoice_no=$no2) WHERE invoice_no=$no1".update.run
            .transact(xa)
        _ <- pay.apply(no2, BigDecimal("1200.00"), "bank", Some(s"P2-$no2"))
        // ---- replay the ledger ----
        timeline <- OrderLifecycleRepo.timeline(ord).transact(xa)
        cycles   <- OrderLifecycleRepo.cycles(ord).transact(xa)
      } yield {
        val types   = timeline.map(_.hcursor.get[String]("event_type").toOption.getOrElse(""))
        val seqs    = timeline.flatMap(_.hcursor.get[Long]("seq").toOption)
        val origins = timeline.flatMap(_.hcursor.get[String]("origin").toOption).toSet
        val byNo    = cycles.map(c => c.hcursor.get[String]("invoice_no").toOption.getOrElse("") -> c).toMap
        val cyc1    = byNo(no1).hcursor
        val cyc2    = byNo(no2).hcursor
        expect(seqs == seqs.sorted) and // chronological replay
          expect(types.contains("order.invoiced")) and
          expect(types.count(_ == "payment.received") >= 2) and // pay, refund, pay
          expect(types.contains("invoice.voided")) and
          // lineage precision: each event records its origin + a timezone-complete UTC instant
          expect(origins.contains("payment:bank")) and     // the refund/payment rail
          expect(origins.contains("user:finance:e2e")) and // the void's actor
          expect(timeline.forall(_.hcursor.get[String]("occurred_at").exists(_.endsWith("Z")))) and
          expect(cycles.size == 2) and // two collection cycles on one order
          expect(cyc1.get[String]("status").toOption.contains("void")) and
          expect(cyc1.get[String]("void_kind").toOption.contains("refund")) and
          expect(cyc1.get[String]("replaced_by").toOption.contains(no2)) and // the back-and-forth link
          expect(cyc1.get[BigDecimal]("refunded").toOption.exists(_ > 0)) and
          expect(cyc1.get[BigDecimal]("outstanding").toOption.contains(BigDecimal(0))) and // void → not owed
          expect(cyc2.get[String]("status").toOption.contains("paid")) and
          expect(cyc2.get[BigDecimal]("outstanding").toOption.contains(BigDecimal(0))) // re-invoice collected
      }
  }
}
