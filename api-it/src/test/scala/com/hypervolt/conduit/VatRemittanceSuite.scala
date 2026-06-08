package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.hypervolt.conduit.tax.VatExposureRepo
import com.hypervolt.conduit.tax.VatRemittanceService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-VAT.3 — VAT accrues per jurisdiction at recognition, a remittance depletes it, and the per-jurisdiction
// projection ties to the immutable VAT:<entity> ledger balance (the proof). Recognise £200 VAT (GB) → remit £50 →
// outstanding £150, and Σ projection == ledger balance.
object VatRemittanceSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def setup(xa: HikariTransactor[IO]): IO[(UUID, UUID, UUID, List[String])] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
      b <- LotBatchRepo.create(
        NewBatch(
          s"B-${UUID.randomUUID()}",
          None,
          v,
          2,
          BigDecimal("300.00"),
          BigDecimal("1.0"),
          "spot",
          None,
          BigDecimal("0"),
          BigDecimal("0"),
          "GBP"
        ),
        LocalDate.parse("2026-01-01")
      )
      _       <- InventoryRepo.receive(Some(e), v, loc, 2)
      s1      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      s2      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      _       <- LotBatchRepo.assignSerial(s1, b)
      _       <- LotBatchRepo.assignSerial(s2, b)
      serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1, $s2)".query[String].to[List]
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                   VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield (e, ord, ol, serials)).transact(xa)

  test("VAT accrues per jurisdiction at recognition, a remittance depletes it, and the projection ties to the ledger") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val dispatch = new DispatchService[IO](xa)
      val rev      = new RevenueRecognitionService[IO](xa, ledger)
      val remit    = new VatRemittanceService[IO](xa, ledger)
      for {
        s <- setup(xa)
        (e, ord, ol, serials) = s
        did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        _   <- dispatch.deliver(did)
        _   <- rev.recognize(did).map(_.toOption.get)
        period <-
          sql"SELECT to_char(recognized_at,'YYYY-MM') FROM revenue_recognition WHERE dispatch_id = $did"
            .query[String]
            .unique
            .transact(xa)
        before <- VatExposureRepo.exposure(Some(e), None).transact(xa)
        // remit £50 of the £200 GB VAT
        _       <- remit.remit(e, "GB", period, BigDecimal("50.00"), "GBP", Some("HMRC-REF"), "test").map(_.toOption.get)
        after   <- VatExposureRepo.exposure(Some(e), None).transact(xa)
        recon   <- remit.reconcile(e)
        vatBal  <- ledger.balance(rev.vatAcc(e))
        control <- new ControlRunner[IO](xa).run("CTRL-VAT-NO-OVER-REMIT", None).map(_.toOption.get)
      } yield {
        val gbBefore =
          before.find(_.hcursor.get[String]("jurisdiction").toOption.contains("GB")).getOrElse(io.circe.Json.Null)
        val gbAfter =
          after.find(_.hcursor.get[String]("jurisdiction").toOption.contains("GB")).getOrElse(io.circe.Json.Null)
        expect(gbBefore.hcursor.get[BigDecimal]("accrued").toOption.contains(BigDecimal("200.0000"))) and
          expect(gbBefore.hcursor.get[BigDecimal]("outstanding").toOption.contains(BigDecimal("200.0000"))) and
          expect(gbAfter.hcursor.get[BigDecimal]("remitted").toOption.contains(BigDecimal("50.0000"))) and
          expect(gbAfter.hcursor.get[BigDecimal]("outstanding").toOption.contains(BigDecimal("150.0000"))) and
          // the immutable tie: projection outstanding == VAT:<entity> ledger balance (credits − debits)
          expect(recon.hcursor.get[Boolean]("matched").toOption.contains(true)) and
          expect(recon.hcursor.get[BigDecimal]("projected_outstanding").toOption.contains(BigDecimal("150.00"))) and
          expect(recon.hcursor.get[BigDecimal]("ledger_vat_balance").toOption.contains(BigDecimal("150.00"))) and
          expect(vatBal.creditsPosted - vatBal.debitsPosted == BigInt(15000)) and // £150 net on the ledger
          expect(control.result == "pass")                                        // remitted £50 ≤ accrued £200 — not over-remitted
      }
  }
}
