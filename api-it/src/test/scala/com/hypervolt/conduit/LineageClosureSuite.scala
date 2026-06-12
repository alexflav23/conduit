package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.commission.CommissionLineInput
import com.hypervolt.conduit.commission.CommissionScheme
import com.hypervolt.conduit.commission.CommissionService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.hypervolt.conduit.stockops.StockOpsService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-Assurance A2 (spec doc 29): CTRL-LINEAGE-CLOSURE. Forward: every posted transfer is claimed by a business
// fact. Backward: every claimed leg has its two-sided gl_entry mirror. The suite proves the control DETECTS
// corruption — a deleted leg, an orphan transfer, a one-sided mirror, a stripped reversal leg — each named
// with the precise identity of the break, and proves a full multi-poster world closes cleanly.
object LineageClosureSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val CLOSURE = "CTRL-LINEAGE-CLOSURE"

  private def runControl(xa: HikariTransactor[IO], code: String): IO[Long] =
    new ControlRunner[IO](xa).run(code, None).map(_.toOption.get.violations)

  private def violationRows(xa: HikariTransactor[IO]): IO[List[(String, String, String, Option[String])]] =
    sql"SELECT kind, fact_table, fact_id, leg FROM lineage_closure_violation"
      .query[(String, String, String, Option[String])]
      .to[List]
      .transact(xa)

  private def user(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"$name-${UUID.randomUUID()}"}, $name) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def principalAndOperating(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      sg <- sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
              VALUES ('HV Procurement SG', 'SG', 'GBP', 'procurement') RETURNING id""".query[UUID].unique
      op <- sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
              VALUES ('HV Operating UK', 'GB', 'GBP', 'operating', $sg) RETURNING id""".query[UUID].unique
    } yield (sg, op)).transact(xa)

  private def stockedOrder(
      xa: HikariTransactor[IO],
      entity: UUID,
      market: UUID
  ): IO[(UUID, UUID, UUID, UUID, List[String])] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                 VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id""".query[UUID].unique
      billTo <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      loc <- InventoryRepo.createLocation(Some(entity), s"W-${UUID.randomUUID().toString.take(6)}", "W")
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
      _       <- InventoryRepo.receive(Some(entity), v, loc, 2)
      s1      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(entity), loc)
      s2      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(entity), loc)
      _       <- LotBatchRepo.assignSerial(s1, b)
      _       <- LotBatchRepo.assignSerial(s2, b)
      serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1, $s2)".query[String].to[List]
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
              VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $entity, $billTo, $billTo, $market, 'placed', 'GBP', 'stripe', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      ol <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
          .query[UUID]
          .unique
    } yield (v, billTo, ord, ol, serials)).transact(xa)

  private def flashRecognized(
      xa: HikariTransactor[IO],
      client: Client,
      price: BigDecimal
  ): IO[(UUID, UUID, UUID, UUID, UUID, List[String])] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      ents <- principalAndOperating(xa)
      (sg, op) = ents
      market <- IO(UUID.randomUUID())
      maker  <- user(xa, "sg-maker")
      check  <- user(xa, "sg-checker")
      fix    <- stockedOrder(xa, op, market)
      (v, _, ord, ol, serials) = fix
      lst <-
        ProcurementCatalogue
          .propose(sg, market, "GBP", List(ProcurementCatalogue.PriceListLine(v, price)), maker)
          .transact(xa)
          .map(_.toOption.get)
      _   <- ProcurementCatalogue.activate(lst, check).transact(xa)
      did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      _   <- dispatch.deliver(did)
      r   <- rev.recognize(did)
      _   <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"recognition failed: $r"))
    } yield (sg, op, ord, did, ol, serials)
  }

  private def invoiceOf(xa: HikariTransactor[IO], ord: UUID): IO[(UUID, String)] =
    sql"SELECT id, invoice_no FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
      .query[(UUID, String)]
      .unique
      .transact(xa)

  test("a recognized + voided flash lifecycle closes: every leg claimed, every claim mirrored, no orphans") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, _, ord, _, _, _) = f
        afterRecognize <- runControl(xa, CLOSURE)
        inv            <- invoiceOf(xa, ord)
        void = new InvoiceReversalService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
        r         <- void.reverse(inv._1, "cancellation", "customer cancelled", "test")
        afterVoid <- runControl(xa, CLOSURE)
        icMatch   <- runControl(xa, "CTRL-IC-MATCH")
        rows      <- violationRows(xa)
      } yield expect(r.isRight) and
        expect.same(afterRecognize, 0L) and
        expect.same(afterVoid, 0L) and
        expect.same(icMatch, 0L) and
        expect(rows.isEmpty)
  }

  test("a return with a flash unwind closes — the unwind legs are claimed on the rma line") {
    case (xa, client) =>
      val svc = new ReturnService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, op, ord, _, ol, serials) = f
        maker <- user(xa, "rma-maker")
        check <- user(xa, "rma-checker")
        _ <-
          TigerBeetleLedger
            .fromClient[IO](client)
            .createAccounts(
              List(
                com.hypervolt.conduit.ledger.LedgerAccount(
                  svc.cosClearing(op),
                  com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.GBP),
                  com.hypervolt.conduit.ledger.LedgerAccountCode.CosClearing
                )
              )
            )
        rma <-
          svc.raise(ord, "full_unit", "serial", "changed_mind", maker, List(RaiseLine(ol, serials.headOption, None, 1)))
        lineId <- sql"SELECT id FROM rma_line WHERE rma_id = $rma".query[UUID].unique.transact(xa)
        _      <- svc.assess(rma, List((lineId, "a")), check)
        _      <- svc.approve(rma, check, None)
        _      <- svc.receive(rma)
        dp     <- svc.disposition(rma, lineId, "restock", None, check)
        claims <-
          sql"""SELECT restock_tb_transfer_id IS NOT NULL,
                       unwind_op_tb_transfer_id IS NOT NULL, unwind_pr_tb_transfer_id IS NOT NULL
                FROM rma_line WHERE id = $lineId"""
            .query[(Boolean, Boolean, Boolean)]
            .unique
            .transact(xa)
        closed <- runControl(xa, CLOSURE)
      } yield expect(dp.isRight) and
        expect(claims._1) and expect(claims._2) and expect(claims._3) and
        expect.same(closed, 0L)
  }

  test("payments, payouts, commission (pending/post/true-up) and a stock-count variance all close") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val payments = new PaymentService[IO](xa, ledger)
      val comm     = new CommissionService[IO](xa, ledger, expenseEntity = "uk")
      val stockOps = new StockOpsService[IO](xa, ledger)
      val scheme =
        CommissionScheme(
          UUID.randomUUID(),
          "gross_margin",
          BigDecimal("10"),
          "zero",
          Instant.parse("2026-01-01T00:00:00Z"),
          None
        )
      val line =
        CommissionLineInput(BigDecimal("587.50"), BigDecimal("400.00"), 2, "standard", exceptionApproved = false)
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, op, ord, _, _, _) = f
        inv  <- invoiceOf(xa, ord)
        paid <- payments.apply(inv._2, BigDecimal("600.00"), "stripe", Some(s"pi-${UUID.randomUUID()}"))
        po   <- payments.recordPayout(s"po-${UUID.randomUUID()}", op, "GBP", BigDecimal("600.00"), BigDecimal("12.50"))
        sid <-
          sql"""INSERT INTO commission_scheme (name, basis, rate_pct, exception_treatment, valid_from)
                VALUES ('WS 10%', 'gross_margin', 10, 'zero', '2026-01-01T00:00:00Z') RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        aid <- sql"INSERT INTO sales_agent (name) VALUES ('Agent L') RETURNING id".query[UUID].unique.transact(xa)
        _ <- ledger.createAccounts(
          List(
            com.hypervolt.conduit.ledger.LedgerAccount(
              comm.expenseAccount("GBP"),
              com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.GBP),
              com.hypervolt.conduit.ledger.LedgerAccountCode.CommPayable
            ),
            com.hypervolt.conduit.ledger.LedgerAccount(
              comm.payableAccount(aid, "GBP"),
              com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.GBP),
              com.hypervolt.conduit.ledger.LedgerAccountCode.CommPayable
            )
          )
        )
        entryId <- comm.accrue(aid, sid, None, "GBP", scheme, line)
        _       <- comm.post(entryId, BigDecimal("37.50"))
        // a zero-delta true-up posts nothing and must CLAIM nothing
        zeroTu <- comm.trueUp(
          aid,
          sid,
          None,
          "GBP",
          BigDecimal("10"),
          BigDecimal("587.50"),
          2,
          BigDecimal("37.50"),
          BigDecimal("400.00")
        )
        realTu <- comm.trueUp(
          aid,
          sid,
          None,
          "GBP",
          BigDecimal("10"),
          BigDecimal("587.50"),
          2,
          BigDecimal("37.50"),
          BigDecimal("390.00")
        )
        zeroClaim <-
          sql"SELECT tb_transfer_id IS NULL FROM commission_entry WHERE id = ${zeroTu._1}"
            .query[Boolean]
            .unique
            .transact(xa)
        countMaker <- user(xa, "count-maker")
        countCheck <- user(xa, "count-checker")
        loc        <- InventoryRepo.createLocation(Some(op), s"W-${UUID.randomUUID().toString.take(6)}", "W").transact(xa)
        cfix       <- stockedOrder(xa, op, UUID.randomUUID()).map(_._1)
        _ <- ledger.createAccounts(
          List(
            com.hypervolt.conduit.ledger.LedgerAccount(
              stockOps.writeOffAccount(op),
              com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.GBP),
              com.hypervolt.conduit.ledger.LedgerAccountCode.InvWriteOff
            )
          )
        )
        countId <- stockOps.submitCount(op, loc, List((cfix, 8, 10)), countMaker)
        ok      <- stockOps.approveCount(countId, countCheck)
        varianceClaim <-
          sql"SELECT tb_transfer_id IS NOT NULL FROM stock_count_line WHERE count_id = $countId"
            .query[Boolean]
            .unique
            .transact(xa)
        closed <- runControl(xa, CLOSURE)
      } yield expect(paid.isRight) and expect(po.isRight) and expect(ok.isRight) and
        expect.same(zeroTu._2, BigDecimal("0.00")) and expect(zeroClaim) and
        expect(realTu._2 > 0) and expect(varianceClaim) and
        expect.same(closed, 0L)
  }

  test("DETECTION: deleting one posted leg's mirror fails the control with the precise identity of the break") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, _, _, did, _, _) = f
        cogsTid <-
          sql"SELECT cogs_transfer_id FROM revenue_recognition WHERE dispatch_id = $did"
            .query[BigDecimal]
            .unique
            .transact(xa)
        saved <-
          sql"""SELECT side, account_key, account_role, entity_id, currency, amount_minor, phase, posted, transfer_code, event_id, occurred_at
                FROM gl_entry WHERE tb_transfer_id = $cogsTid"""
            .query[(String, String, Int, Option[UUID], String, BigDecimal, String, Boolean, Int, UUID, Instant)]
            .to[List]
            .transact(xa)
        _      <- sql"DELETE FROM gl_entry WHERE tb_transfer_id = $cogsTid".update.run.transact(xa)
        broken <- runControl(xa, CLOSURE)
        named <- violationRows(xa).map(
          _.exists(r =>
            r._1 == "missing_leg" && r._2 == "revenue_recognition" && r._3 == did.toString && r._4.contains("cogs")
          )
        )
        _ <- saved.traverse_ { row =>
          sql"""INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, entity_id, currency, amount_minor, phase, posted, transfer_code, event_id, occurred_at)
                VALUES ($cogsTid, ${row._1}, ${row._2}, ${row._3}, ${row._4}, ${row._5}, ${row._6}, ${row._7}, ${row._8}, ${row._9}, ${row._10}, ${row._11})""".update.run
            .transact(xa)
        }
        restored <- runControl(xa, CLOSURE)
      } yield expect(saved.size == 2) and
        expect(broken > 0L) and expect(named) and
        expect.same(restored, 0L)
  }

  test("DETECTION: an orphan transfer and a one-sided mirror are both named") {
    case (xa, _) =>
      val orphanTid   = BigDecimal(BigInt("424242424242424242424242"))
      val oneSidedTid = BigDecimal(BigInt("424242424242424242424243"))
      val ev          = UUID.randomUUID()
      def seed(tid: BigDecimal, side: String) =
        sql"""INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, currency, amount_minor, phase, posted, transfer_code, event_id, occurred_at)
              VALUES ($tid, $side, 'AR:fake', 1, 'GBP', 100, 'single', true, 0, $ev, now())""".update.run
      for {
        _      <- (seed(orphanTid, "debit") *> seed(orphanTid, "credit") *> seed(oneSidedTid, "debit")).transact(xa)
        broken <- runControl(xa, CLOSURE)
        rows   <- violationRows(xa)
        orphan   = rows.exists(r => r._1 == "orphan_transfer" && r._3 == ev.toString)
        oneSided = rows.exists(r => r._1 == "one_sided_mirror" && r._3 == ev.toString)
        _        <- sql"DELETE FROM gl_entry WHERE event_id = $ev".update.run.transact(xa)
        restored <- runControl(xa, CLOSURE)
      } yield expect(broken >= 3L) and expect(orphan) and expect(oneSided) and expect.same(restored, 0L)
  }

  test("DETECTION: stripping a reversal leg off the match surfaces as an incomplete fact (and its orphan)") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, _, ord, did, _, _) = f
        inv <- invoiceOf(xa, ord)
        void = new InvoiceReversalService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
        r <- void.reverse(inv._1, "cancellation", "seeded corruption target", "test")
        savedLeg <-
          sql"SELECT rev_op_leg_tb_transfer_id FROM ic_match WHERE dispatch_id = $did"
            .query[BigDecimal]
            .unique
            .transact(xa)
        _      <- sql"UPDATE ic_match SET rev_op_leg_tb_transfer_id = NULL WHERE dispatch_id = $did".update.run.transact(xa)
        broken <- runControl(xa, CLOSURE)
        named <- violationRows(xa).map(
          _.exists(r =>
            r._1 == "incomplete_fact" && r._2 == "ic_match" && r._3 == did.toString && r._4.contains("reversal_pair")
          )
        )
        _ <-
          sql"UPDATE ic_match SET rev_op_leg_tb_transfer_id = $savedLeg WHERE dispatch_id = $did".update.run
            .transact(xa)
        restored <- runControl(xa, CLOSURE)
      } yield expect(r.isRight) and expect(broken > 0L) and expect(named) and expect.same(restored, 0L)
  }

  test("DETECTION: CTRL-IC-MATCH rejects an over-unwind and a wrong-signed unwind") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (_, _, _, did, _, _) = f
        clean <- runControl(xa, "CTRL-IC-MATCH")
        _ <-
          sql"UPDATE ic_match SET returned_uplift = uplift_total + 1 WHERE dispatch_id = $did".update.run.transact(xa)
        over      <- runControl(xa, "CTRL-IC-MATCH")
        _         <- sql"UPDATE ic_match SET returned_uplift = -1 WHERE dispatch_id = $did".update.run.transact(xa)
        wrongSign <- runControl(xa, "CTRL-IC-MATCH")
        _         <- sql"UPDATE ic_match SET returned_uplift = 0 WHERE dispatch_id = $did".update.run.transact(xa)
        restored  <- runControl(xa, "CTRL-IC-MATCH")
      } yield expect.same(clean, 0L) and expect(over > 0L) and expect(wrongSign > 0L) and expect.same(restored, 0L)
  }

  test("DETECTION: CTRL-IC-CATALOGUE rejects a self-approved active list") {
    case (xa, _) =>
      for {
        pe    <- principalAndOperating(xa).map(_._1)
        maker <- user(xa, "rogue-maker")
        listId <-
          sql"""INSERT INTO transfer_price_list (procurement_entity_id, market_id, currency, status, proposed_by, approved_by)
                VALUES ($pe, ${UUID.randomUUID()}, 'GBP', 'active', $maker, $maker) RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        broken   <- runControl(xa, "CTRL-IC-CATALOGUE")
        _        <- sql"DELETE FROM transfer_price_list WHERE id = $listId".update.run.transact(xa)
        restored <- runControl(xa, "CTRL-IC-CATALOGUE")
      } yield expect(broken > 0L) and expect.same(restored, 0L)
  }
}
