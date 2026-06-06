package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.credit.CashWaterfallRepo
import com.hypervolt.conduit.credit.CreditTermsService
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13 — invoice raised on dispatch with the contact's contractual due date, and the cash waterfall buckets open
// invoices by that due date. Different customer terms => different due dates => different waterfall buckets.
object CreditTermsSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def order(xa: HikariTransactor[IO], termsDays: Int): IO[(UUID, UUID, String)] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', false) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES (${s"Acct-${UUID.randomUUID()}"},'wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days) VALUES ($billTo, 'Acct', 'GBP', $termsDays)".update.run
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, status) VALUES ($ord, $v, 2, 500.00, 200.00, 'open') RETURNING id"
          .query[UUID]
          .unique
    } yield (ord, ol, billTo.toString)).transact(xa)

  test("dispatch raises the invoice with a due date from the contact's terms; the cash waterfall buckets it") { xa =>
    val disp = new DispatchService[IO](xa)
    for {
      s60 <- order(xa, 60)
      (ord60, ol60, _) = s60
      _   <- disp.dispatch(ord60, None, None, None, List(DispatchLineInput(ol60, 2, Nil)))
      due <- sql"SELECT due_date FROM order_invoice WHERE order_id = $ord60".query[LocalDate].unique.transact(xa)
      // a second account on 14-day terms — its invoice lands in an earlier bucket
      s14 <- order(xa, 14)
      (ord14, ol14, _) = s14
      _  <- disp.dispatch(ord14, None, None, None, List(DispatchLineInput(ol14, 2, Nil)))
      wf <- CashWaterfallRepo.waterfall(Some("GBP")).transact(xa)
    } yield {
      val today  = LocalDate.now()
      val months = wf.flatMap(_.hcursor.get[String]("due_month").toOption)
      val due60  = today.plusDays(60)
      val due14  = today.plusDays(14)
      val m      = (d: LocalDate) => f"${d.getYear}%04d-${d.getMonthValue}%02d"
      expect(due == due60) and                // 60-day terms honoured at dispatch
        expect(months.contains(m(due60))) and // both contacts' invoices appear in the waterfall...
        expect(months.contains(m(due14)))     // ...in their own contractual due-date buckets
    }
  }

  test("CreditTermsService upserts a contact's terms even with no prior profile") { xa =>
    val svc = new CreditTermsService[IO](xa)
    for {
      party <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES (${s"New-${UUID.randomUUID()}"},'wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      before <- svc.get(party) // no profile yet → default 30
      _      <- svc.set(party, 45, Some(BigDecimal(50000)), Some("GBP"))
      after  <- svc.get(party)
    } yield expect(before.paymentTermsDays == 30) and
      expect(after.paymentTermsDays == 45) and expect(after.creditLimit.contains(BigDecimal("50000.0000")))
  }
}
