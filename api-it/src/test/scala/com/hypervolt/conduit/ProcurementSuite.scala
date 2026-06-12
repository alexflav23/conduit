package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.DataLayer
import com.hypervolt.conduit.access.Grant
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.access.Projection
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-Procurement (spec doc 28): the principal/LRD structure. The central catalogue is governed maker<>checker;
// a customer dispatch under a procurement parent books the flash-title uplift pair — operating COGS lands at
// the TRANSFER price, the principal books exactly the markup, one ic_match binds dispatch -> IC legs ->
// origin batches; an unpriced hop FAILS CLOSED; and the whole structure is invisible without inter_entity.
object ProcurementSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

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

  test("the catalogue is governed: maker proposes, cannot self-activate; the checker activates; v2 supersedes") {
    case (xa, _) =>
      for {
        pe     <- principalAndOperating(xa).map(_._1)
        market <- IO(UUID.randomUUID())
        maker  <- user(xa, "sg-maker")
        check  <- user(xa, "sg-checker")
        fam <-
          sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        v <-
          sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"K-${UUID.randomUUID()}"},'v3') RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        line = List(ProcurementCatalogue.PriceListLine(v, BigDecimal("380.00")))
        v1   <- ProcurementCatalogue.propose(pe, market, "GBP", line, maker).transact(xa).map(_.toOption.get)
        self <- ProcurementCatalogue.activate(v1, maker).transact(xa)
        ok   <- ProcurementCatalogue.activate(v1, check).transact(xa)
        v2   <- ProcurementCatalogue.propose(pe, market, "GBP", line, maker).transact(xa).map(_.toOption.get)
        _    <- ProcurementCatalogue.activate(v2, check).transact(xa)
        statuses <-
          sql"SELECT id, status FROM transfer_price_list WHERE procurement_entity_id = $pe ORDER BY version"
            .query[(UUID, String)]
            .to[List]
            .transact(xa)
      } yield expect(self.isLeft) and expect(ok.isRight) and
        expect(statuses.map(_._2) == List("superseded", "active")) // append-only: v1 superseded, never edited
  }

  test(
    "flash title at dispatch: operating COGS = transfer price; the principal books exactly the markup; one match binds the chain"
  ) {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val dispatch = new DispatchService[IO](xa)
      val rev      = new RevenueRecognitionService[IO](xa, ledger)
      for {
        ents <- principalAndOperating(xa)
        (sg, op) = ents
        market <- IO(UUID.randomUUID())
        maker  <- user(xa, "sg-maker")
        check  <- user(xa, "sg-checker")
        fix    <- stockedOrder(xa, op, market)
        (v, _, ord, ol, serials) = fix
        // the catalogue: SG sells this variant into the market at £380 (landed £300 → markup £80/unit)
        lst <-
          ProcurementCatalogue
            .propose(sg, market, "GBP", List(ProcurementCatalogue.PriceListLine(v, BigDecimal("380.00"))), maker)
            .transact(xa)
            .map(_.toOption.get)
        _   <- ProcurementCatalogue.activate(lst, check).transact(xa)
        did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        _   <- dispatch.deliver(did)
        r1  <- rev.recognize(did)
        r2  <- rev.recognize(did) // redelivery — must be a complete no-op, match included
        rr <-
          sql"SELECT cogs, gross_margin FROM revenue_recognition WHERE dispatch_id = $did"
            .query[(BigDecimal, BigDecimal)]
            .unique
            .transact(xa)
        m <-
          sql"""SELECT landed_total, transfer_total, uplift_total, cardinality(origin_batch_ids),
                          op_leg_tb_transfer_id IS NOT NULL AND pr_leg_tb_transfer_id IS NOT NULL
                   FROM ic_match WHERE dispatch_id = $did"""
            .query[(BigDecimal, BigDecimal, BigDecimal, Int, Boolean)]
            .unique
            .transact(xa)
        matches <- sql"SELECT count(*) FROM ic_match WHERE dispatch_id = $did".query[Int].unique.transact(xa)
        opCogs  <- ledger.balance(rev.cogsAcc(op))
        icMargin <- ledger.balance(
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_MARGIN:$sg")
        )
        icAp <- ledger.balance(com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AP:$op:$sg"))
        icAr <- ledger.balance(com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AR:$sg:$op"))
      } yield expect(r1.isRight) and expect(r2.isRight) and expect(matches == 1) and
        expect(rr._1 == BigDecimal("760.00")) and        // operating COGS = TRANSFER (2 × 380), not landed
        expect(rr._2 == BigDecimal("240.00")) and        // the LRD margin: 1000 − 760
        expect(m._1 == BigDecimal("600.00")) and         // landed (2 × 300)
        expect(m._2 == BigDecimal("760.00")) and         // transfer
        expect(m._3 == BigDecimal("160.00")) and         // uplift = the principal's margin, conserved exactly
        expect(m._4 > 0) and expect(m._5) and            // origin-batch genealogy + both journal legs bound
        expect(opCogs.debitsPosted == BigInt(76000)) and // 600 landed + 160 uplift = 760 (minor units)
        expect(icMargin.creditsPosted == BigInt(16000)) and
        expect(icAp.creditsPosted == BigInt(16000)) and // operating owes the principal the markup
        expect(icAr.debitsPosted == BigInt(16000))      // the pair eliminates at group: AP == AR
  }

  test("an unpriced internal hop fails closed — no catalogue line, no policy, no recognition") {
    case (xa, client) =>
      val ledger   = TigerBeetleLedger.fromClient[IO](client)
      val dispatch = new DispatchService[IO](xa)
      val rev      = new RevenueRecognitionService[IO](xa, ledger)
      for {
        ents <- principalAndOperating(xa)
        (_, op) = ents
        market <- IO(UUID.randomUUID())
        fix    <- stockedOrder(xa, op, market)
        (_, _, ord, ol, serials) = fix
        did <- dispatch.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 2, serials))).map(_.toOption.get)
        _   <- dispatch.deliver(did)
        r   <- rev.recognize(did)
        rec <- sql"SELECT count(*) FROM revenue_recognition WHERE dispatch_id = $did".query[Int].unique.transact(xa)
      } yield expect(r.isLeft) and expect(rec == 0) and
        expect(r.swap.toOption.get.contains("fails closed"))
  }

  test("the wall: without the inter_entity layer the catalogue's numbers do not exist in the payload") {
    case (_, _) =>
      val row = io.circe.Json.obj(
        "id"         -> UUID.randomUUID().toString.asJson,
        "market_id"  -> UUID.randomUUID().toString.asJson,
        "unit_price" -> BigDecimal("380.00").asJson,
        "currency"   -> "GBP".asJson,
        "status"     -> "active".asJson
      )
      def grantWith(layers: Set[DataLayer]) =
        Grant(
          List(
            com.hypervolt.conduit.access
              .Permission(
                "transfer_price_list",
                com.hypervolt.conduit.access.Action.View,
                None,
                layers,
                Set.empty,
                com.hypervolt.conduit.access.Breadth.All
              )
          ),
          Set.empty,
          Set.empty,
          Set.empty,
          None
        )
      val walled = Principal(UUID.randomUUID(), Set.empty, List(grantWith(Set(DataLayer.Volume))))
      val insider =
        Principal(UUID.randomUUID(), Set.empty, List(grantWith(Set(DataLayer.Volume, DataLayer.InterEntity))))
      val stripped = Projection.projectFor(walled, "transfer_price_list", row)
      val seen     = Projection.projectFor(insider, "transfer_price_list", row)
      IO.pure(
        expect(stripped.hcursor.downField("unit_price").failed) and // ABSENT — not null, not zero (doc 05)
          expect(stripped.hcursor.downField("status").failed) and
          expect(seen.hcursor.get[BigDecimal]("unit_price").exists(_ == BigDecimal("380.00")))
      )
  }

  // ----- cancellations & alterations (doc 28 §2.5): the corresponding journals, extensively -----

  private def flashRecognized(
      xa: HikariTransactor[IO],
      client: Client,
      price: BigDecimal
  ): IO[(UUID, UUID, UUID, UUID, UUID, List[String], RevenueRecognitionService[IO])] = {
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
    } yield (sg, op, ord, did, ol, serials, rev)
  }

  private def netZero(client: Client, account: BigInt): IO[Boolean] =
    TigerBeetleLedger.fromClient[IO](client).balance(account).map(b => b.debitsPosted == b.creditsPosted)

  test("a cancellation void reverses EVERY leg — the principal gives back the margin, inventory restates at landed") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00"))
        (sg, op, ord, did, _, _, rev) = f
        invId <-
          sql"SELECT id FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
            .query[UUID]
            .unique
            .transact(xa)
        void = new InvoiceReversalService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
        r1 <- void.reverse(invId, "cancellation", "customer cancelled", "test")
        r2 <- void.reverse(invId, "cancellation", "again", "test") // idempotent — same result, no extra legs
        zeros <- List(
          rev.cogsAcc(op),
          rev.inv(op),
          rev.revenue(op),
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AP:$op:$sg"),
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AR:$sg:$op"),
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_MARGIN:$sg")
        ).traverse(netZero(client, _))
        m <-
          sql"""SELECT reversed_at IS NOT NULL, reversal_id IS NOT NULL,
                          rev_op_leg_tb_transfer_id IS NOT NULL AND rev_pr_leg_tb_transfer_id IS NOT NULL
                   FROM ic_match WHERE dispatch_id = $did"""
            .query[(Boolean, Boolean, Boolean)]
            .unique
            .transact(xa)
      } yield expect(r1.isRight) and expect(r2.isRight) and
        expect(zeros.forall(identity)) and             // every account nets to zero — op COGS, INV, IC pair, the margin
        expect(m._1) and expect(m._2) and expect(m._3) // the genealogy survives: match stamped, never deleted
  }

  test("a below-cost catalogue (negative uplift) voids to zero too — the flipped pair reverses flipped") {
    case (xa, client) =>
      for {
        f <- flashRecognized(xa, client, BigDecimal("250.00")) // landed 300 → uplift −100
        (sg, op, ord, _, _, _, rev) = f
        invId <-
          sql"SELECT id FROM order_invoice WHERE order_id = $ord ORDER BY issued_at DESC LIMIT 1"
            .query[UUID]
            .unique
            .transact(xa)
        void = new InvoiceReversalService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
        r <- void.reverse(invId, "correction", "priced below cost", "test")
        zeros <- List(
          rev.cogsAcc(op),
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AP:$op:$sg"),
          com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_MARGIN:$sg")
        ).traverse(netZero(client, _))
      } yield expect(r.isRight) and expect(zeros.forall(identity))
  }

  test("a returned unit unwinds its pro-rata uplift share — the genealogy accumulates on the match") {
    case (xa, client) =>
      val svc = new ReturnService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        f <- flashRecognized(xa, client, BigDecimal("380.00")) // uplift 160 over 2 units → 80/unit
        (sg, op, ord, did, ol, serials, _) = f
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
        ret    <- sql"SELECT returned_uplift FROM ic_match WHERE dispatch_id = $did".query[BigDecimal].unique.transact(xa)
        icAp <-
          TigerBeetleLedger
            .fromClient[IO](client)
            .balance(com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_AP:$op:$sg"))
        margin <-
          TigerBeetleLedger
            .fromClient[IO](client)
            .balance(com.hypervolt.conduit.ledger.TbIds.accountId(s"IC_MARGIN:$sg"))
      } yield expect(dp.isRight) and
        expect(ret == BigDecimal("80.0000")) and      // exactly one unit's share, accumulated on the match
        expect(icAp.debitsPosted == BigInt(8000)) and // the operating entity owes 80 less
        expect(margin.debitsPosted == BigInt(8000))   // the principal gave back exactly its share
  }
}
