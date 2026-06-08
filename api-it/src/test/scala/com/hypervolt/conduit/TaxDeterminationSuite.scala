package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.tax.RateTableProvider
import com.hypervolt.conduit.tax.RateTableTaxEngine
import com.hypervolt.conduit.tax.TaxDeterminationService
import com.hypervolt.conduit.tax.TaxProvider
import com.hypervolt.conduit.tax.TaxQuoteLineReq
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxQuoteResponse
import com.hypervolt.conduit.tax.TaxShipPoint
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-Tax.2 — the determination service: provider routing, immutable persisted quotes, supersession, the nexus
// rolling totals, and (the audit anchor) byte-exact reproducibility by replay. Validates doc 16 §4 / §7.
object TaxDeterminationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val providers: Map[String, TaxProvider] = Map(RateTableProvider.name -> RateTableProvider)
  private val asOf                                = LocalDate.parse("2026-06-01")

  private def svc(xa: HikariTransactor[IO]) = new TaxDeterminationService[IO](xa, providers)

  private def entity(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def line(amt: String, category: String = "goods_standard"): TaxQuoteLineReq =
    TaxQuoteLineReq("l1", None, Some(category), None, 1, BigDecimal(amt))

  private def req(
      e: UUID,
      to: String,
      lines: List[TaxQuoteLineReq],
      context: String = "order_placed",
      order: Option[UUID] = None,
      region: Option[String] = None,
      postcode: Option[String] = None,
      currency: String = "GBP",
      status: String = "consumer",
      at: LocalDate = asOf
  ): TaxQuoteRequest =
    TaxQuoteRequest(
      context,
      e,
      TaxShipPoint("GB", None, None),
      TaxShipPoint(to, region, postcode),
      status,
      None,
      None,
      currency,
      at,
      lines,
      orderId = order
    )

  test("determine persists an immutable quote + lines (snapshots) and emits tax.quoted") { xa =>
    for {
      e <- entity(xa)
      ord = UUID.randomUUID()
      r <- svc(xa).determine(req(e, "GB", List(line("100.00")), order = Some(ord)))
      row <-
        sql"SELECT supply_kind, total_tax, provider, jsonb_array_length(response_snapshot->'lines') FROM tax_quote WHERE order_id = $ord"
          .query[(String, BigDecimal, String, Int)]
          .unique
          .transact(xa)
      lineCount <-
        sql"SELECT count(*) FROM tax_quote_line tql JOIN tax_quote tq ON tq.id = tql.tax_quote_id WHERE tq.order_id = $ord"
          .query[Int]
          .unique
          .transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type = 'tax.quoted' AND partition_key = ${ord.toString}"
          .query[Int]
          .unique
          .transact(xa)
    } yield expect(r.exists(_.taxTotal == BigDecimal("20.00"))) and
      expect(row == (("domestic", BigDecimal("20.0000"), "rate_table", 1))) and
      expect(lineCount == 1) and
      expect(events == 1)
  }

  test("a re-quote for the same order/context supersedes the prior (append-only versioning)") { xa =>
    for {
      e <- entity(xa)
      ord = UUID.randomUUID()
      _ <- svc(xa).determine(req(e, "GB", List(line("100.00")), order = Some(ord)))
      _ <- svc(xa).determine(req(e, "GB", List(line("200.00")), order = Some(ord)))
      rows <-
        sql"SELECT total_tax, superseded_by IS NOT NULL FROM tax_quote WHERE order_id = $ord ORDER BY determined_at"
          .query[(BigDecimal, Boolean)]
          .to[List]
          .transact(xa)
    } yield expect(rows.length == 2) and
      expect(rows.head == ((BigDecimal("20.0000"), true))) and // first, superseded
      expect(rows(1) == ((BigDecimal("40.0000"), false)))      // second, current
  }

  test(
    "a rate-table quote is reproducible: re-running determine over the request snapshot reproduces the response exactly"
  ) { xa =>
    for {
      e <- entity(xa)
      ord = UUID.randomUUID()
      _ <- svc(xa).determine(
        req(
          e,
          "US",
          List(line("123.45")),
          order = Some(ord),
          region = Some("CA"),
          postcode = Some("90001"),
          currency = "USD"
        )
      )
      snap <-
        sql"SELECT request_snapshot, response_snapshot FROM tax_quote WHERE order_id = $ord"
          .query[(Json, Json)]
          .unique
          .transact(xa)
      (reqJson, respJson) = snap
      storedResp          = respJson.as[TaxQuoteResponse].toOption.get
      replayReq           = reqJson.as[TaxQuoteRequest].toOption.get
      replayed <- RateTableTaxEngine.quoteC(replayReq).transact(xa)
    } yield expect(replayed == storedResp)
  }

  test("US nexus: a sale that crosses the configured threshold flips the profile and emits threshold_crossed") { xa =>
    for {
      e <- entity(xa)
      _ <-
        sql"INSERT INTO nexus_profile (entity_id, jurisdiction, region, threshold_amount, status) VALUES ($e,'US','CA',50.0000,'monitoring')".update.run
          .transact(xa)
      _ <- svc(xa).determine(
        req(e, "US", List(line("100.00")), region = Some("CA"), postcode = Some("90001"), currency = "USD")
      )
      np <-
        sql"SELECT status, sales_to_date, crossed_at IS NOT NULL FROM nexus_profile WHERE entity_id = $e AND region = 'CA'"
          .query[(String, BigDecimal, Boolean)]
          .unique
          .transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type = 'tax.nexus.threshold_crossed' AND partition_key = ${e.toString}"
          .query[Int]
          .unique
          .transact(xa)
    } yield expect(np == (("crossed", BigDecimal("100.0000"), true))) and expect(events == 1)
  }

  test("effective-dating: a future-dated rate change re-taxes correctly under the rate in force at as_of") { xa =>
    for {
      e <- entity(xa)
      _ <-
        sql"INSERT INTO tax_category (code, name, default_kind) VALUES ('effdate_cat','Eff-date test','standard') ON CONFLICT DO NOTHING".update.run
          .transact(xa)
      _ <-
        sql"""INSERT INTO tax_rate (tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind, effective_from, effective_to)
              VALUES ('VAT','GB',NULL,NULL,'national','effdate_cat','UK VAT 20', 20.0000, 'standard', DATE '1970-01-01', DATE '2027-01-01')""".update.run
          .transact(xa)
      _ <-
        sql"""INSERT INTO tax_rate (tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind, effective_from, effective_to)
              VALUES ('VAT','GB',NULL,NULL,'national','effdate_cat','UK VAT 17.5', 17.5000, 'standard', DATE '2027-01-01', NULL)""".update.run
          .transact(xa)
      before <- svc(xa).determine(req(e, "GB", List(line("100.00", "effdate_cat")), at = LocalDate.parse("2026-12-01")))
      after  <- svc(xa).determine(req(e, "GB", List(line("100.00", "effdate_cat")), at = LocalDate.parse("2027-06-01")))
    } yield expect(before.exists(_.taxTotal == BigDecimal("20.00"))) and expect(
      after.exists(_.taxTotal == BigDecimal("17.50"))
    )
  }
}
