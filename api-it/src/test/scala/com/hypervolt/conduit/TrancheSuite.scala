package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.purchasing.PurchasingService
import com.hypervolt.conduit.purchasing.TrancheLine
import com.hypervolt.conduit.purchasing.TrancheService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M9c — inbound tranches as first-class citizens (user spec): two CMs in parallel (Volex Poland by truck,
// Luxshare Suzhou by sea), each tranche carrying its own inbound freight that lands CONSERVINGLY on the
// landed cost per line, GRNs stamped with their tranche, the SKU balance rolled forward on every receipt,
// and a re-receive an idempotent no-op.
object TrancheSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("two tranches over two lanes: conserving freight, tranche-stamped GRNs, rolled-forward balances") { xa =>
    val purch    = new PurchasingService[IO](xa, new AllocationService[IO](xa))
    val tranches = new TrancheService[IO](xa, purch)
    for {
      setup <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id"
              .query[UUID]
              .unique
          v <-
            sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID
              .randomUUID()}"}, 'v3', false) RETURNING id"
              .query[UUID]
              .unique
          loc <- InventoryRepo.createLocation(Some(e), "W", "W")
          sup <-
            sql"INSERT INTO supplier (name, billing_currency) VALUES ('Volex PLC', 'GBP') RETURNING id"
              .query[UUID]
              .unique
        } yield (e, v, loc, sup)).transact(xa)
      (e, v, loc, sup) = setup
      poIds <- purch.createPO(e, Some(sup), "GBP")
      (poId, _) = poIds
      plId <- purch.addPoLine(poId, v, 500, BigDecimal("100.00"))

      // tranche 1: Volex Poland by truck — 300 units, £900 freight
      t1 <- tranches.plan(
        poId,
        "truck",
        "Volex Poland",
        BigDecimal("900.00"),
        "GBP",
        List(TrancheLine(v, 300, plId, BigDecimal("100.00"), BigDecimal(1)))
      )
      n1 <- tranches.receive(
        t1,
        e,
        loc,
        List(TrancheLine(v, 300, plId, BigDecimal("100.00"), BigDecimal(1))),
        receivedDate = LocalDate.of(2026, 6, 1)
      )
      again <- tranches.receive(
        t1,
        e,
        loc,
        List(TrancheLine(v, 300, plId, BigDecimal("100.00"), BigDecimal(1))),
        receivedDate = LocalDate.of(2026, 6, 1)
      ) // idempotent

      // tranche 2: Luxshare Suzhou by sea — 200 units, £1,400 freight (a different lane costs differently)
      t2 <- tranches.plan(
        poId,
        "sea",
        "Luxshare Suzhou",
        BigDecimal("1400.00"),
        "GBP",
        List(TrancheLine(v, 200, plId, BigDecimal("100.00"), BigDecimal(1)))
      )
      _ <- tranches.receive(
        t2,
        e,
        loc,
        List(TrancheLine(v, 200, plId, BigDecimal("100.00"), BigDecimal(1))),
        receivedDate = LocalDate.of(2026, 6, 8)
      )

      freightByTranche <-
        sql"""SELECT tranche_id, SUM(amount) FROM landed_cost_component
            WHERE po_id = $poId AND type = 'freight' GROUP BY tranche_id"""
          .query[(UUID, BigDecimal)]
          .to[List]
          .transact(xa)
      grns <-
        sql"SELECT count(*) FROM goods_receipt WHERE po_id = $poId AND tranche_id IS NOT NULL"
          .query[Long]
          .unique
          .transact(xa)
      balances <-
        sql"""SELECT t.seq, l.balance_after FROM purchase_tranche_line l
            JOIN purchase_tranche t ON t.id = l.tranche_id WHERE t.po_id = $poId ORDER BY t.seq"""
          .query[(Int, BigDecimal)]
          .to[List]
          .transact(xa)
      statuses <-
        sql"SELECT status FROM purchase_tranche WHERE po_id = $poId ORDER BY seq"
          .query[String]
          .to[List]
          .transact(xa)
    } yield expect(n1 > 0) and expect(again == 0) and                              // re-receive is a no-op
      expect(freightByTranche.toMap.get(t1).contains(BigDecimal("900.0000"))) and  // conserving: Σ == total
      expect(freightByTranche.toMap.get(t2).contains(BigDecimal("1400.0000"))) and // per-lane cost intact
      expect(grns == 2L) and
      expect(balances.map(_._2.toInt) == List(300, 500)) and // the roll-forward: 300 after t1, 500 after t2
      expect(statuses == List("received", "received"))
  }
}
