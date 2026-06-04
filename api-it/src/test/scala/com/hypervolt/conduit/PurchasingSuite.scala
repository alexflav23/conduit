package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.purchasing.PurchasingService
import com.hypervolt.conduit.purchasing.ReceiveLine
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

object PurchasingSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("receiving lands the per-lot cost, increments stock, and auto-fills the oldest backorder") { xa =>
    val alloc = new AllocationService[IO](xa)
    val purch = new PurchasingService[IO](xa, alloc)
    for {
      setup <- (for {
                 e   <- sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id".query[UUID].unique
                 fam <- sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id".query[UUID].unique
                 v   <- sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', false) RETURNING id".query[UUID].unique
                 loc <- InventoryRepo.createLocation(Some(e), "W", "W")
                 p   <- sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('P','wholesaler',true) RETURNING id".query[UUID].unique
                 o   <- sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                              VALUES ('ORD-'||nextval('order_no_seq'),'trade',$e,$p,$p,'placed','GBP','stripe') RETURNING id""".query[UUID].unique
                 line <- sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, status) VALUES ($o,$v,5,587.50,'backordered') RETURNING id".query[UUID].unique
               } yield (e, v, loc, line)).transact(xa)
      (e, v, loc, line) = setup
      poIds   <- purch.createPO(e, None, "GBP")
      (poId, _) = poIds
      plId    <- purch.addPoLine(poId, v, 10, BigDecimal("100.00"))
      batchId <- purch.receive(poId, e, loc, ReceiveLine(plId, v, 10, BigDecimal("100.00"), BigDecimal("1.0"), BigDecimal("0"), BigDecimal("0"), Nil, "GBP"), LocalDate.parse("2026-06-01"))
      onHand     <- sql"SELECT qty_on_hand FROM stock_item WHERE entity_id=$e AND product_variant_id=$v AND location_id=$loc".query[Int].unique.transact(xa)
      landed     <- LotBatchRepo.landedCost(batchId).transact(xa)
      lineStatus <- sql"SELECT status FROM order_line WHERE id=$line".query[String].unique.transact(xa)
      lineAlloc  <- sql"SELECT qty_allocated FROM order_line WHERE id=$line".query[Int].unique.transact(xa)
    } yield expect(onHand == 10) and
      expect(landed.contains(BigDecimal("100.0000"))) and
      expect(lineStatus == "allocated") and
      expect(lineAlloc == 5)
  }
}
