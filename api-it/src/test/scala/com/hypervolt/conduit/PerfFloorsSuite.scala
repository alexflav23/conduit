package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Clock
import cats.syntax.all._
import com.hypervolt.conduit.assurance.FingerprintService
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.demo.DemoBook
import com.hypervolt.conduit.gl.GlProjectionService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-Assurance E (spec doc 29): perf floors on the hot paths the CTO exercises live. Asserted with GENEROUS
// ceilings (so they never flake on a loaded CI box) and the measured time is always printed; set
// PERF_STRICT=1 to tighten to the doc's real targets (recognition <250ms, reads <1s). A floor breach is a
// loud regression signal, not a precise benchmark.
object PerfFloorsSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val strict = sys.env.get("PERF_STRICT").contains("1")
  // (generous ceiling, strict target) in millis
  private def ceil(generousMs: Long, strictMs: Long): Long = if (strict) strictMs else generousMs

  private def timed[A](io: IO[A]): IO[(A, Long)] =
    (Clock[IO].monotonic, io, Clock[IO].monotonic).mapN((t0, a, t1) => (a, (t1 - t0).toMillis))

  private def floor(label: String, ms: Long, ceilingMs: Long) = {
    val msg = label + " took " + ms + "ms (ceiling " + ceilingMs + "ms)"
    expect(ms <= ceilingMs, msg)
  }

  // a fresh flash order, recognised under timing — the money write hot path
  private def freshRecognizeMs(xa: HikariTransactor[IO], client: Client): IO[Long] = {
    val dispatch = new DispatchService[IO](xa)
    val rev      = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      sg <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
                  VALUES (${s"SG-${UUID.randomUUID().toString.take(6)}"}, 'SG', 'GBP', 'procurement') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      op <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
                  VALUES (${s"UK-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      market <- IO(UUID.randomUUID())
      maker <-
        sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"pf-${UUID.randomUUID()}"}, 'pf') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      check <-
        sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"pf2-${UUID.randomUUID()}"}, 'pf2') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      ids <- (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
              .query[UUID]
              .unique
          v <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                     VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id""".query[UUID].unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('PerfCust','wholesaler',true) RETURNING id"
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
      ms  <- timed(rev.recognize(did)).map(_._2)
    } yield ms
  }

  test("perf floors: recognition, control re-run, trial balance and fingerprint stay under their ceilings") {
    case (xa, client) =>
      val runner = new ControlRunner[IO](xa)
      val gl     = new GlProjectionService[IO](xa)
      val fp     = new FingerprintService[IO](xa)
      for {
        _              <- DemoBook.seed(xa, TigerBeetleLedger.fromClient[IO](client))
        op             <- sql"SELECT id FROM entity WHERE name = 'Hypervolt UK (demo)'".query[UUID].unique.transact(xa)
        recMs          <- freshRecognizeMs(xa, client)
        (_, lineageMs) <- timed(runner.run("CTRL-LINEAGE-CLOSURE", None))
        (_, tbMs)      <- timed(gl.trialBalance(op))
        (_, fpMs)      <- timed(fp.compute("perf-sha"))
        _ <- IO.println(
          s"PERF recognize=${recMs}ms lineage_control=${lineageMs}ms trial_balance=${tbMs}ms fingerprint=${fpMs}ms (strict=$strict)"
        )
      } yield floor("recognition", recMs, ceil(2000, 250)) and
        floor("CTRL-LINEAGE-CLOSURE", lineageMs, ceil(3000, 1000)) and
        floor("trial_balance", tbMs, ceil(2000, 1000)) and
        floor("fingerprint", fpMs, ceil(2000, 1000))
  }
}
