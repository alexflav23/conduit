package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.supply.ComponentBufferService
import com.hypervolt.conduit.supply.ProductionService
import com.hypervolt.conduit.supply.SupplyCommitmentService
import com.hypervolt.conduit.supply.WaterfallRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-I/J/K — the demand→revenue waterfall, production-shortfall carry-over, frozen-window divergence warnings,
// and the configurable component (parts) buffer. The most vital mechanism: forecast → CM commitment → produced
// → delivered → ordered → shipped → revenue, with the supply-side attrition and the parts buffer tracked.
object WaterfallSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val month = LocalDate.of(2026, 7, 1)
  private val asOf  = LocalDate.of(2026, 6, 1)

  private def supplier(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO supplier (name, billing_currency) VALUES ('Volex','USD') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def variant(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)

  private def commit(
      xa: HikariTransactor[IO],
      sup: UUID,
      v: UUID,
      target: LocalDate,
      qty: Int,
      zone: String
  ): IO[Unit] =
    sql"INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone) VALUES ($sup,$v,$target,$qty,$zone)".update.run
      .transact(xa)
      .void

  test("production shortfall carries unmet demand into the next window") { xa =>
    val prod = new ProductionService[IO](xa)
    for {
      sup <- supplier(xa); v                         <- variant(xa)
      _   <- commit(xa, sup, v, month, 100, "frozen")
      r   <- prod.report(sup, v, month, produced = 70) // built only 70 of 100
      next <-
        sql"SELECT qty FROM supply_commitment WHERE supplier_id=$sup AND product_variant_id=$v AND target_date=${month.plusWeeks(1)}"
          .query[Int]
          .unique
          .transact(xa)
    } yield expect(r.committed == 100) and expect(r.produced == 70) and expect(r.shortfall == 30) and
      expect(r.carriedTo.contains(month.plusWeeks(1))) and expect(next == 30) // the 30 rolled into the next window
  }

  test("the demand→revenue waterfall assembles every distinct stage for a SKU/month") { xa =>
    val prod = new ProductionService[IO](xa)
    for {
      sup <- supplier(xa); v <- variant(xa)
      mkt = UUID.randomUUID()
      sc <-
        sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)
      // forecast (H6Q market row), commitment, produced, delivered, an order, and a dispatch
      _ <-
        sql"INSERT INTO pipeline_coverage (level, market_id, product_variant_id, period_month, scenario_id, forecast_qty) VALUES ('market',$mkt,$v,$month,$sc,120)".update.run
          .transact(xa)
      // commit late in the month so the produced shortfall carries into August, not back into July's total
      cmTarget = month.plusDays(27)
      _ <- commit(xa, sup, v, cmTarget, 100, "flex")
      _ <- prod.report(sup, v, cmTarget, produced = 90)
      _ <-
        sql"INSERT INTO lot_batch (batch_no, product_variant_id, qty, unit_cost_usd, fx_rate, fx_basis, landed_unit_cost, currency, received_date) VALUES (${s"B-${UUID
          .randomUUID()}"},$v,80,100,1.0,'spot',100,'GBP',$month)".update.run.transact(xa)
      party <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, order_date)
                   VALUES (${s"O-${UUID.randomUUID()}"},'trade',$party,$party,$mkt,'placed','GBP','stripe',$month) RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat) VALUES ($ord,$v,60,500.00) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      d <-
        sql"INSERT INTO dispatch (dispatch_no, order_id, date, status) VALUES (${s"D-${UUID.randomUUID()}"},$ord,$month,'delivered') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      _  <- sql"INSERT INTO dispatch_line (dispatch_id, order_line_id, qty) VALUES ($d,$ol,50)".update.run.transact(xa)
      wf <- WaterfallRepo.waterfall(v, month).transact(xa)
    } yield {
      val s = wf.hcursor.downField("stages")
      expect(s.get[Int]("sales_forecast").contains(120)) and
        expect(s.get[Int]("cm_committed").contains(100)) and
        expect(s.get[Int]("cm_produced").contains(90)) and
        expect(s.get[Int]("delivered").contains(80)) and
        expect(s.get[Int]("ordered").contains(60)) and
        expect(s.get[Int]("shipped").contains(50)) and
        expect(wf.hcursor.get[String]("revenue_ex_vat").exists(s => BigDecimal(s) == BigDecimal(25000))) // 50 × £500
    }
  }

  test("a sales/automated delta against a frozen firm PO raises a divergence warning (not silently dropped)") { xa =>
    val svc = new SupplyCommitmentService[IO](xa)
    for {
      sup <- supplier(xa); v <- variant(xa)
      frozenTarget = asOf.plusDays(14)
      _    <- commit(xa, sup, v, frozenTarget, 100, "frozen")
      warn <- svc.checkDemand(sup, v, frozenTarget, asOf, demand = 140, source = "sales_input")
      ok   <- svc.checkDemand(sup, v, frozenTarget, asOf, demand = 100, source = "sales_input")
      rows <-
        sql"SELECT severity FROM commitment_warning WHERE supplier_id=$sup AND product_variant_id=$v"
          .query[String]
          .to[List]
          .transact(xa)
    } yield expect(warn.isDefined) and expect(ok.isEmpty) and expect(rows == List("block"))
  }

  test(
    "the component (parts) buffer tracks vs a configurable P50 target; converting parts to FG raises the liability"
  ) { xa =>
    val buf = new ComponentBufferService[IO](xa)
    for {
      sup  <- supplier(xa); v                       <- variant(xa)
      _    <- buf.setTarget(Some(sup), Some(v), 500, "p50")
      _    <- buf.setBuffer(sup, v, 300)
      st   <- buf.status(sup, v)
      conv <- buf.convertToFinishedGoods(sup, v, 100) // parts → FG (the invoice/liability trigger)
      over <- buf.convertToFinishedGoods(sup, v, 999)
      ev <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='component.converted_to_fg' AND aggregate_id=$sup"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(st.target == 500) and expect(st.partsOnSite == 300) and expect(st.deficit == 200) and
      expect(conv.map(_.partsOnSite) == Right(200)) and expect(over.isLeft) and expect(ev == 1L)
  }
}
