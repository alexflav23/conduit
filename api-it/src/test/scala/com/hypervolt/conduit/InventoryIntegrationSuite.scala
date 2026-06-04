package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.inventory._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

object InventoryIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def newEntity: ConnectionIO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('UK Ltd', 'GB', 'GBP', 'operating') RETURNING id"
      .query[UUID]
      .unique

  private def newVariant(serialised: Boolean): ConnectionIO[UUID] =
    for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"}, 'Fam') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"SKU-${UUID.randomUUID()}"}, 'v3', $serialised) RETURNING id"
          .query[UUID]
          .unique
    } yield v

  private def newOrder(entity: UUID): ConnectionIO[UUID] =
    for {
      p <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('P', 'wholesaler', true) RETURNING id"
          .query[UUID]
          .unique
      o <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, channel_id, market_id, status, txn_currency, payment_method)
                 VALUES ('ORD-' || nextval('order_no_seq'), 'trade', $entity, $p, $p, ${UUID.randomUUID()}, ${UUID
          .randomUUID()}, 'placed', 'GBP', 'stripe') RETURNING id""".query[UUID].unique
    } yield o

  private def newLine(order: UUID, variant: UUID, qty: Int, scheduled: Boolean): ConnectionIO[UUID] =
    sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, is_scheduled, status)
          VALUES ($order, $variant, $qty, 587.50, $scheduled, 'open') RETURNING id""".query[UUID].unique

  private def newTranche(line: UUID, seq: Int, qty: Int): ConnectionIO[UUID] =
    sql"INSERT INTO delivery_tranche (order_line_id, seq, qty, requested_date) VALUES ($line, $seq, $qty, '2026-07-01') RETURNING id"
      .query[UUID]
      .unique

  test("on-hand reconstructs from the immutable movement log") { xa =>
    (for {
      e <- newEntity
      v <- newVariant(false)
      l <- InventoryRepo.createLocation(Some(e), "W1", "Wh")
      _ <- InventoryRepo.receive(Some(e), v, l, 10)
      _ <- InventoryRepo.receive(Some(e), v, l, 5)
      m <- InventoryRepo.onHandFromMovements(v, l)
    } yield expect(m == 15)).transact(xa)
  }

  test("concurrent last-unit allocation never over-commits (exactly one winner)") { xa =>
    for {
      setup <- (for {
          e <- newEntity
          v <- newVariant(false)
          l <- InventoryRepo.createLocation(Some(e), "W", "W")
          _ <- InventoryRepo.receive(Some(e), v, l, 1)
          o <- newOrder(e)
        } yield (e, v, o)).transact(xa)
      (e, v, o) = setup
      alloc     = new AllocationService[IO](xa)
      lines          <- (1 to 8).toList.traverse(_ => newLine(o, v, 1, false).transact(xa))
      results        <- lines.parTraverse(line => alloc.allocate(line, None, e, v, 1, serialised = false))
      stockAllocated <- InventoryRepo.allocatedQty(e, v).transact(xa)
    } yield expect(results.map(_.allocated).sum == 1) and
      expect(stockAllocated == 1) and
      expect(results.count(_.allocated == 1) == 1)
  }

  test("a serialised line cannot dispatch without serials; with serials it ships and decrements stock") { xa =>
    val ser1 = s"SER-${UUID.randomUUID()}"
    val ser2 = s"SER-${UUID.randomUUID()}"
    for {
      setup <- (for {
          e  <- newEntity
          v  <- newVariant(true)
          l  <- InventoryRepo.createLocation(Some(e), "W", "W")
          _  <- InventoryRepo.receive(Some(e), v, l, 2)
          _  <- InventoryRepo.addSerial(ser1, "v3", v, Some(e), l)
          _  <- InventoryRepo.addSerial(ser2, "v3", v, Some(e), l)
          o  <- newOrder(e)
          ln <- newLine(o, v, 2, false)
        } yield (e, v, o, ln)).transact(xa)
      (e, v, o, ln) = setup
      alloc         = new AllocationService[IO](xa)
      disp          = new DispatchService[IO](xa)
      _           <- alloc.allocate(ln, None, e, v, 2, serialised = true)
      noSerials   <- disp.dispatch(o, None, None, None, List(DispatchLineInput(ln, 2, Nil)))
      withSerials <- disp.dispatch(o, None, None, None, List(DispatchLineInput(ln, 2, List(ser1, ser2))))
      serStatus   <- sql"SELECT status FROM serial_unit WHERE serial_no = $ser1".query[String].unique.transact(xa)
      onHand      <- sql"SELECT qty_on_hand FROM stock_item WHERE product_variant_id = $v".query[Int].unique.transact(xa)
    } yield expect(noSerials.isLeft) and expect(withSerials.isRight) and
      expect(serStatus == "dispatched") and expect(onHand == 0)
  }

  test("a 500-unit line scheduled 2x250 allocates and dispatches tranches independently, invoicing per drop") { xa =>
    for {
      setup <- (for {
          e  <- newEntity
          v  <- newVariant(false)
          l  <- InventoryRepo.createLocation(Some(e), "W", "W")
          _  <- InventoryRepo.receive(Some(e), v, l, 500)
          o  <- newOrder(e)
          ln <- newLine(o, v, 500, true)
          t1 <- newTranche(ln, 1, 250)
          t2 <- newTranche(ln, 2, 250)
        } yield (e, v, o, ln, t1, t2)).transact(xa)
      (e, v, o, ln, t1, t2) = setup
      alloc                 = new AllocationService[IO](xa)
      disp                  = new DispatchService[IO](xa)
      a1 <- alloc.allocate(ln, Some(t1), e, v, 250, serialised = false)
      a2 <- alloc.allocate(ln, Some(t2), e, v, 250, serialised = false)
      d1 <- disp.dispatch(o, Some(t1), None, None, List(DispatchLineInput(ln, 250, Nil)))
      dispatchId = d1.toOption.get
      _        <- disp.deliver(dispatchId)
      t1status <- sql"SELECT status FROM delivery_tranche WHERE id = $t1".query[String].unique.transact(xa)
      t2status <- sql"SELECT status FROM delivery_tranche WHERE id = $t2".query[String].unique.transact(xa)
      invoices <- sql"SELECT count(*) FROM order_invoice WHERE order_id = $o".query[Long].unique.transact(xa)
      delivered <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='dispatch.delivered' AND aggregate_id=$o"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(a1.allocated == 250) and expect(a2.allocated == 250) and
      expect(d1.isRight) and expect(t1status == "invoiced") and expect(t2status == "allocated") and
      expect(invoices == 1L) and expect(delivered == 1L)
  }
}
