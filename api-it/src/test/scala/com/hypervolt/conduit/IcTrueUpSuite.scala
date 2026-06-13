package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.intercompany.IcTrueUpService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 5 (spec doc 28 §5.6): §482 transfer-pricing true-up. A governed (maker<>checker) year-end
// adjustment moves the period's aggregate IC uplift to the arm's-length target — ONE matched pair
// (IC_AP/IC_AR/IC_MARGIN, sign-aware), eliminated at group, allocated conservingly across the period's
// matches FOR DOCUMENTATION (ic_match never rewritten). Distinct from the ASC-606 customer rebate.
object IcTrueUpSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def user(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"$n-${UUID.randomUUID()}"}, $n) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def entities(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      sg <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
              VALUES (${s"SG-${UUID.randomUUID().toString.take(6)}"}, 'SG', 'GBP', 'procurement') RETURNING id"""
          .query[UUID]
          .unique
      op <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
              VALUES (${s"UK-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
          .query[UUID]
          .unique
    } yield (sg, op)).transact(xa)

  // one recognised flash order: uplift = transfer 380×2 − landed 300×2 = 160. Two orders → period uplift 320.
  private def recognised(
      xa: HikariTransactor[IO],
      client: Client,
      sg: UUID,
      op: UUID,
      market: UUID,
      maker: UUID,
      check: UUID
  ): IO[UUID] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      ids <- (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
              .query[UUID]
              .unique
          v <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                     VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id""".query[UUID].unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('TuCust','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          loc <- InventoryRepo.createLocation(Some(op), s"W-${UUID.randomUUID().toString.take(6)}", "W")
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
          _       <- InventoryRepo.receive(Some(op), v, loc, 2)
          s1      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(op), loc)
          s2      <- InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(op), loc)
          _       <- LotBatchRepo.assignSerial(s1, b)
          _       <- LotBatchRepo.assignSerial(s2, b)
          serials <- sql"SELECT serial_no FROM serial_unit WHERE id IN ($s1, $s2)".query[String].to[List]
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                     VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $op, $billTo, $billTo, $market, 'placed', 'GBP', 'stripe', 1000.00, 200.00, 1200.00) RETURNING id"""
              .query[UUID]
              .unique
          ol <-
            sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00) RETURNING id"
              .query[UUID]
              .unique
        } yield (v, ord, ol, serials)).transact(xa)
      (v, ord, ol, serials) = ids
      lst <-
        ProcurementCatalogue
          .propose(sg, market, "GBP", List(ProcurementCatalogue.PriceListLine(v, BigDecimal("380.00"))), maker)
          .transact(xa)
          .map(_.toOption.get)
      _   <- ProcurementCatalogue.activate(lst, check).transact(xa)
      did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
      _   <- dispatch.deliver(did)
      r   <- rev.recognize(did)
      _   <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"recognize failed: $r"))
    } yield did
  }

  private def bal(client: Client, key: String): IO[(BigInt, BigInt)] =
    TigerBeetleLedger.fromClient[IO](client).balance(TbIds.accountId(key)).map(b => (b.debitsPosted, b.creditsPosted))

  private def control(xa: HikariTransactor[IO], code: String): IO[Long] =
    new ControlRunner[IO](xa).run(code, None).map(_.toOption.get.violations)

  test("an upward true-up posts a conserving pair, eliminates at group, allocates for docs, never rewrites the match") {
    case (xa, client) =>
      val svc = new IcTrueUpService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa)
        (sg, op) = es
        market       <- IO(UUID.randomUUID())
        maker        <- user(xa, "tu-maker")
        check        <- user(xa, "tu-check")
        d1           <- recognised(xa, client, sg, op, market, maker, check)
        d2           <- recognised(xa, client, sg, op, market, maker, check) // period uplift = 160 + 160 = 320
        marginBefore <- bal(client, s"IC_MARGIN:$sg")
        // arm's-length review: the period margin should have been 500, not 320 → adjustment +180
        proposer <- user(xa, "tu-proposer")
        approver <- user(xa, "tu-approver")
        tid <-
          svc
            .propose(
              sg,
              op,
              "GBP",
              LocalDate.now().minusDays(1),
              LocalDate.now().plusDays(1),
              BigDecimal("500.00"),
              proposer
            )
            .map(_.toOption.get)
        selfNo      <- svc.approve(tid, proposer)
        r           <- svc.approve(tid, approver)
        marginAfter <- bal(client, s"IC_MARGIN:$sg")
        icAp        <- bal(client, s"IC_AP:$op:$sg")
        icAr        <- bal(client, s"IC_AR:$sg:$op")
        lines <-
          sql"SELECT COALESCE(SUM(allocated),0), count(*) FROM ic_true_up_line WHERE true_up_id = $tid"
            .query[(BigDecimal, Int)]
            .unique
            .transact(xa)
        // the matches are untouched (L6): their uplift_total still sums to the original 320
        matchSum <-
          sql"SELECT COALESCE(SUM(uplift_total),0) FROM ic_match WHERE dispatch_id IN ($d1, $d2)"
            .query[BigDecimal]
            .unique
            .transact(xa)
        ctrl    <- control(xa, "CTRL-IC-TRUEUP")
        closure <- control(xa, "CTRL-LINEAGE-CLOSURE")
      } yield expect(selfNo.isLeft) and expect(r.isRight) and
        expect.same(r.toOption.get.prior, BigDecimal("320.0000")) and
        expect.same(r.toOption.get.adjustment, BigDecimal("180.0000")) and
        expect.same(marginAfter._2 - marginBefore._2, BigInt(18000)) and // +£180 credited to the principal's margin
        expect.same(icAp._2, icAr._1) and                                // the pair eliminates: IC_AP credit == IC_AR debit
        expect.same(lines._1, BigDecimal("180.0000")) and expect.same(
        lines._2,
        2
      ) and                                                // conserving allocation over 2 matches
        expect.same(matchSum, BigDecimal("320.0000")) and  // ic_match NOT rewritten
        expect.same(ctrl, 0L) and expect.same(closure, 0L) // lineage closes over the true-up legs
  }

  test("DETECTION: a tampered adjustment fails CTRL-IC-TRUEUP; a self-approval is impossible to record") {
    case (xa, client) =>
      val svc = new IcTrueUpService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        es <- entities(xa)
        (sg, op) = es
        market <- IO(UUID.randomUUID())
        maker  <- user(xa, "tu2-maker")
        check  <- user(xa, "tu2-check")
        _      <- recognised(xa, client, sg, op, market, maker, check)
        prop   <- user(xa, "tu2-prop")
        appr   <- user(xa, "tu2-appr")
        tid <-
          svc
            .propose(
              sg,
              op,
              "GBP",
              LocalDate.now().minusDays(1),
              LocalDate.now().plusDays(1),
              BigDecimal("260.00"),
              prop
            )
            .map(_.toOption.get)
        _     <- svc.approve(tid, appr)
        clean <- control(xa, "CTRL-IC-TRUEUP")
        // tamper: break the adjustment = target − prior identity
        _      <- sql"UPDATE ic_true_up SET adjustment = adjustment + 5 WHERE id = $tid".update.run.transact(xa)
        broken <- control(xa, "CTRL-IC-TRUEUP")
        _ <-
          sql"UPDATE ic_true_up SET adjustment = target_uplift - prior_uplift WHERE id = $tid".update.run.transact(xa)
        fixed <- control(xa, "CTRL-IC-TRUEUP")
      } yield expect.same(clean, 0L) and expect(broken > 0L) and expect.same(fixed, 0L)
  }
}
