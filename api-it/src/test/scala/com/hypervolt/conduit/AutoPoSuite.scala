package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.supply.AutoPoProposer
import com.hypervolt.conduit.supply.SerialShelfRepo
import com.hypervolt.conduit.supply.SupplyCommitmentService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-L — auto-PO proposer within the time-fence headroom + real-time per-account serial consumption. The
// serial→customer attribution is owned by Conduit (set at dispatch), so per-account stock is automatic.
object AutoPoSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def supplier(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO supplier (name, billing_currency) VALUES ('Volex','USD') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)
  private def variant(xa: HikariTransactor[IO], serialised: Boolean = true): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', $serialised) RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)
  private def scenarioP50(xa: HikariTransactor[IO]): IO[UUID] =
    sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)
  private def coverageRow(
      xa: HikariTransactor[IO],
      market: UUID,
      v: UUID,
      period: LocalDate,
      sc: UUID,
      qty: Int
  ): IO[Unit] =
    sql"INSERT INTO pipeline_coverage (level, market_id, product_variant_id, period_month, scenario_id, forecast_qty) VALUES ('market',$market,$v,$period,$sc,$qty)".update.run
      .transact(xa)
      .void

  test("auto-PO proposes net need WITHIN the flex headroom and blocks the remainder (raising a divergence warning)") {
    xa =>
      val sc     = new SupplyCommitmentService[IO](xa)
      val pro    = new AutoPoProposer[IO](xa, sc)
      val market = UUID.randomUUID()
      for {
        sup <- supplier(xa); v <- variant(xa)
        p50 <- scenarioP50(xa)
        period = LocalDate.of(2026, 9, 1)
        asOf   = period.minusDays(90) // period sits in the FLEX window
        _ <- coverageRow(xa, market, v, period, p50, 200) // H6Q demand 200
        _ <-
          sql"INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone) VALUES ($sup,$v,$period,100,'flex')".update.run
            .transact(xa) // committed 100
        loc <- InventoryRepo.createLocation(None, "W", "W").transact(xa)
        _ <-
          sql"INSERT INTO stock_item (product_variant_id, location_id, qty_on_hand) VALUES ($v,$loc,30)".update.run
            .transact(xa) // available 30
        props <- pro.propose(sup, market, period, p50, asOf)
        warn <-
          sql"SELECT count(*) FROM commitment_warning WHERE supplier_id=$sup AND product_variant_id=$v"
            .query[Long]
            .unique
            .transact(xa)
        ev <-
          sql"SELECT count(*) FROM outbox_event WHERE event_type='supply.po.proposed' AND aggregate_id=$sup"
            .query[Long]
            .unique
            .transact(xa)
      } yield {
        val pp = props.head
        // net need = 200 - 100 committed - 30 on hand = 70 ; flex headroom 20% of 100 = 20 ; propose 20, block 50
        expect(pp.netNeed == 70) and expect(pp.proposedDelta == 20) and expect(pp.blocked == 50) and
          expect(pp.zone == "flex") and expect(warn == 1L) and expect(ev == 1L)
      }
  }

  test("in the free window the auto-PO proposes the full net need with nothing blocked") { xa =>
    val sc     = new SupplyCommitmentService[IO](xa)
    val pro    = new AutoPoProposer[IO](xa, sc)
    val market = UUID.randomUUID()
    for {
      sup <- supplier(xa); v <- variant(xa)
      p50 <- scenarioP50(xa)
      period = LocalDate.of(2027, 2, 1)
      asOf   = period.minusDays(210) // free window
      _     <- coverageRow(xa, market, v, period, p50, 150)
      props <- pro.propose(sup, market, period, p50, asOf)
    } yield {
      val pp = props.head
      expect(pp.netNeed == 150) and expect(pp.proposedDelta == 150) and expect(pp.blocked == 0) and expect(
        pp.zone == "free"
      )
    }
  }

  test("real-time per-account shelf: dispatch attributes serials to the customer; activation consumes on-shelf") { xa =>
    val disp = new DispatchService[IO](xa)
    for {
      v <- variant(xa, serialised = true)
      acct <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      loc     <- InventoryRepo.createLocation(None, "W", "W").transact(xa)
      s1      <- InventoryRepo.addSerial(s"S-${UUID.randomUUID()}", "v3", v, None, loc).transact(xa)
      s2      <- InventoryRepo.addSerial(s"S-${UUID.randomUUID()}", "v3", v, None, loc).transact(xa)
      s3      <- InventoryRepo.addSerial(s"S-${UUID.randomUUID()}", "v3", v, None, loc).transact(xa)
      serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1,$s2,$s3)".query[String].to[List].transact(xa)
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                    VALUES (${s"O-${UUID.randomUUID()}"},'trade',$acct,$acct,'placed','GBP','stripe') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat) VALUES ($ord,$v,3,500.00) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 3, serials))).map(_.toOption.get)
      // Conduit attributed all 3 serials to the customer at dispatch (no MRPeasy lookup)
      attributed <-
        sql"SELECT count(*) FROM serial_unit WHERE company_id=$acct AND dispatch_id=$did"
          .query[Long]
          .unique
          .transact(xa)
      shelf0 <- SerialShelfRepo.shelf(acct).transact(xa)
      // the activation stream flips one serial to 'activated' (real-time consumption)
      _      <- sql"UPDATE serial_unit SET status='activated', activated_at=now() WHERE id=$s1".update.run.transact(xa)
      shelf1 <- SerialShelfRepo.shelf(acct).transact(xa)
    } yield {
      expect(attributed == 3L) and
        expect(shelf0.hcursor.get[Int]("shipped").contains(3)) and
        expect(shelf0.hcursor.get[Int]("on_shelf").contains(3)) and
        expect(shelf1.hcursor.get[Int]("activated").contains(1)) and
        expect(shelf1.hcursor.get[Int]("on_shelf").contains(2)) // consumed one in real time
    }
  }
}
