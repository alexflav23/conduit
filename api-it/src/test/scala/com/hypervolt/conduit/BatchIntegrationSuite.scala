package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.batch._
import com.hypervolt.conduit.inventory.InventoryRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

object BatchIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def newVariant: ConnectionIO[UUID] =
    for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"}, 'Fam') RETURNING id".query[UUID].unique
      v   <- sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"SKU-${UUID.randomUUID()}"}, 'v3') RETURNING id".query[UUID].unique
    } yield v

  private def batch(no: String, v: UUID, price: String, fx: String, basis: String, freight: String, duty: String): NewBatch =
    NewBatch(no, None, v, 100, BigDecimal(price), BigDecimal(fx), basis, None, BigDecimal(freight), BigDecimal(duty), "GBP")

  test("two lots of one SKU carry different landed costs (price, freight, FX differ)") { xa =>
    (for {
      v  <- newVariant
      b1 <- LotBatchRepo.create(batch("B1", v, "100.00", "0.7900", "spot", "500.00", "200.00"), LocalDate.parse("2026-06-01"))
      b2 <- LotBatchRepo.create(batch("B2", v, "110.00", "0.8100", "hedged", "800.00", "300.00"), LocalDate.parse("2026-07-01"))
      c1 <- LotBatchRepo.landedCost(b1)
      c2 <- LotBatchRepo.landedCost(b2)
    } yield expect(c1.isDefined) and expect(c2.isDefined) and expect(c1 != c2)).transact(xa)
  }

  test("each serial resolves its own lot's landed cost (specific-identification, no averaging)") { xa =>
    (for {
      v   <- newVariant
      e   <- sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id".query[UUID].unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
      b1  <- LotBatchRepo.create(batch("B1", v, "100.00", "0.7900", "spot", "0", "0"), LocalDate.parse("2026-06-01"))
      b2  <- LotBatchRepo.create(batch("B2", v, "110.00", "0.8100", "spot", "0", "0"), LocalDate.parse("2026-07-01"))
      s1  <- InventoryRepo.addSerial(s"S-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      s2  <- InventoryRepo.addSerial(s"S-${UUID.randomUUID()}", "v3", v, Some(e), loc)
      _   <- LotBatchRepo.assignSerial(s1, b1)
      _   <- LotBatchRepo.assignSerial(s2, b2)
      cost1 <- LotBatchRepo.costOfSerial(s1)
      cost2 <- LotBatchRepo.costOfSerial(s2)
      batch1Cost <- LotBatchRepo.landedCost(b1)
    } yield expect(cost1 != cost2) and expect(cost1 == batch1Cost) and expect(cost1.contains(BigDecimal("79.0000")))).transact(xa)
  }

  test("genealogy resolves serial->batch->order->customer->lifecycle, and batch->serials") { xa =>
    val serial = s"SER-${UUID.randomUUID()}"
    (for {
      v     <- newVariant
      e     <- sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id".query[UUID].unique
      loc   <- InventoryRepo.createLocation(Some(e), "W", "W")
      party <- sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id".query[UUID].unique
      ord   <- sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                     VALUES ('ORD-'||nextval('order_no_seq'),'trade',$e,$party,$party,'placed','GBP','stripe') RETURNING id""".query[UUID].unique
      line  <- sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat) VALUES ($ord,$v,1,587.50) RETURNING id".query[UUID].unique
      b     <- LotBatchRepo.create(batch("BG", v, "100.00", "0.7900", "spot", "0", "0"), LocalDate.parse("2026-06-01"))
      sid   <- InventoryRepo.addSerial(serial, "v3", v, Some(e), loc)
      _     <- LotBatchRepo.assignSerial(sid, b)
      _     <- sql"UPDATE serial_unit SET order_line_id = $line, company_id = $party WHERE id = $sid".update.run
      _     <- Genealogy.record(sid, "manufactured", None, None, None)
      _     <- Genealogy.record(sid, "dispatched", Some("order"), Some(ord), None)
      g     <- Genealogy.ofSerial(serial)
      fromBatch <- Genealogy.serialsOfBatch("BG")
    } yield {
      val c = g.map(_.hcursor)
      expect(g.isDefined) and
        expect(c.flatMap(_.downField("batch").get[String]("batch_no").toOption).contains("BG")) and
        expect(c.flatMap(_.get[String]("customer_party").toOption).exists(_ == party.toString)) and
        expect(c.flatMap(_.downField("order_no").as[String].toOption).exists(_.startsWith("ORD-"))) and
        expect(g.flatMap(_.hcursor.downField("lifecycle").values.map(_.size)).contains(2)) and
        expect(fromBatch.exists(_.hcursor.get[String]("serial_no").toOption.contains(serial)))
    }).transact(xa)
  }
}
