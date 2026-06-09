package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.gl.GlProjectionService
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13 — GL/AR/AP trial balance read OFF the TigerBeetle ledger (doc 07 M13). After a recognised sale the
// projection must tie out (Σ debits == Σ credits) and show AR + Revenue/VAT/COGS/INV from the ledger itself.
object GlProjectionSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def setup(xa: HikariTransactor[IO]): IO[(UUID, UUID, UUID, UUID, List[String])] =
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
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('GL Cust','wholesaler',true) RETURNING id"
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
    } yield (e, billTo, ord, ol, serials)).transact(xa)

  test("the GL trial balance reads off the ledger and ties out (debits == credits) after a recognised sale") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val disp   = new DispatchService[IO](xa)
      val rev    = new RevenueRecognitionService[IO](xa, ledger)
      val gl     = new GlProjectionService[IO](xa)
      for {
        s <- setup(xa)
        (entity, billTo, ord, ol, serials) = s
        did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        _   <- rev.recognize(did)
        tb  <- gl.trialBalance(entity)
      } yield {
        val accs = tb.hcursor.downField("accounts").as[List[io.circe.Json]].getOrElse(Nil)
        def bal(label: String): Option[BigDecimal] =
          accs
            .find(_.hcursor.get[String]("account").toOption.contains(label))
            .flatMap(_.hcursor.get[BigDecimal]("balance").toOption)
        val revKey = "REVENUE:" + entity
        val invKey = "INV:" + entity
        val arKey  = "AR:" + billTo
        expect(tb.hcursor.get[Boolean]("balanced").toOption.contains(true)) and // gl_entry mirror ties out
          expect(tb.hcursor.get[BigDecimal]("total_debits") == tb.hcursor.get[BigDecimal]("total_credits")) and
          expect(bal(revKey).contains(BigDecimal("-1000.00"))) and // credit balance
          expect(bal(invKey).contains(BigDecimal("-600.00"))) and  // relieved at cost
          expect(bal(arKey).contains(BigDecimal("1200.00")))       // AR debit, inc VAT
      }
  }
}
