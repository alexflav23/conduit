package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
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

  test("outbound mirror: the tranche-defined shipping fee conserves across partial dispatches") { xa =>
    val alloc = new AllocationService[IO](xa)
    val disp  = new DispatchService[IO](xa)
    for {
      setup <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E2','GB','GBP','operating') RETURNING id"
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
          loc <- InventoryRepo.createLocation(Some(e), "W2", "W2")
          _   <- InventoryRepo.receive(Some(e), v, loc, 500)
          pty <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          o <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id, status, txn_currency, payment_method)
                VALUES ('ORD-' || nextval('order_no_seq'), 'trade', $e, $pty, $pty, ${UUID.randomUUID()}, ${UUID
              .randomUUID()}, 'placed', 'GBP', 'stripe') RETURNING id""".query[UUID].unique
          ln <-
            sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, is_scheduled, status)
                VALUES ($o, $v, 300, 587.50, true, 'open') RETURNING id""".query[UUID].unique
          t <-
            sql"""INSERT INTO delivery_tranche (order_line_id, seq, qty, requested_date, transport_mode, freight_amount)
                VALUES ($ln, 1, 300, '2026-07-01', 'truck', 600.00) RETURNING id""".query[UUID].unique
        } yield (e, v, o, ln, t)).transact(xa)
      (e, v, o, ln, t) = setup
      _  <- alloc.allocate(ln, Some(t), e, v, 300, serialised = false)
      d1 <- disp.dispatch(o, Some(t), None, None, List(DispatchLineInput(ln, 200, Nil)))
      d2 <- disp.dispatch(o, Some(t), None, None, List(DispatchLineInput(ln, 100, Nil))) // completes the tranche
      costs <-
        sql"SELECT shipping_cost FROM dispatch WHERE tranche_id = $t ORDER BY id"
          .query[BigDecimal]
          .to[List]
          .transact(xa)
      bal <- sql"SELECT balance_after FROM delivery_tranche WHERE id = $t".query[BigDecimal].unique.transact(xa)
    } yield expect(d1.isRight) and expect(d2.isRight) and
      expect(costs.map(_.setScale(2)) == List(BigDecimal("400.00"), BigDecimal("200.00"))) and // conserving: Σ=600
      expect(bal.toInt == 200)                                                                 // roll-forward: 500 received − 300 dispatched
  }
}
