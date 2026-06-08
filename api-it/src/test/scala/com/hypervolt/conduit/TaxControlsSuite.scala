package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.tax.RateTableProvider
import com.hypervolt.conduit.tax.TaxDeterminationService
import com.hypervolt.conduit.tax.TaxProvider
import com.hypervolt.conduit.tax.TaxQuoteLineReq
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxShipPoint
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-Tax.5 — the ICFR controls (doc 16 §8) re-performed by the ControlRunner: the VAT-conservation invariant
// passes on real determinations and CATCHES a deliberately inconsistent quote; the reproducibility-evidence
// control holds for every persisted quote.
object TaxControlsSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val providers: Map[String, TaxProvider] = Map(RateTableProvider.name -> RateTableProvider)

  private def entity(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def quoteReq(e: UUID, to: String, region: Option[String], pc: Option[String], ccy: String): TaxQuoteRequest =
    TaxQuoteRequest(
      "order_placed",
      e,
      TaxShipPoint("GB", None, None),
      TaxShipPoint(to, region, pc),
      "consumer",
      None,
      None,
      ccy,
      LocalDate.parse("2026-06-01"),
      List(TaxQuoteLineReq("l1", None, Some("goods_standard"), None, 1, BigDecimal("100.00")))
    )

  test("CTRL-TAX-VAT-CONSERVE passes for real determinations, then catches a deliberately broken quote") { xa =>
    val svc    = new TaxDeterminationService[IO](xa, providers)
    val runner = new ControlRunner[IO](xa)
    for {
      e            <- entity(xa)
      _            <- svc.determine(quoteReq(e, "GB", None, None, "GBP"))                // UK single component
      _            <- svc.determine(quoteReq(e, "US", Some("CA"), Some("90001"), "USD")) // US 3-component stack
      conserveGood <- runner.run("CTRL-TAX-VAT-CONSERVE", None).map(_.toOption.get)
      reproGood    <- runner.run("CTRL-TAX-REPRO", None).map(_.toOption.get)
      // a tampered quote: total_tax (5) ≠ Σ line tax (1) — must be flagged.
      bad <-
        sql"""INSERT INTO tax_quote (context, entity_id, ship_from_jurisdiction, ship_to_jurisdiction, party_tax_status,
                supply_kind, provider, currency, total_tax, rounding_policy, rates_asof, request_snapshot, response_snapshot)
              VALUES ('order_placed', $e, 'GB', 'GB', 'consumer', 'domestic', 'rate_table', 'GBP', 5.00, 'line',
                DATE '2026-06-01', '{}'::jsonb, '{"provider":"rate_table"}'::jsonb) RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"INSERT INTO tax_quote_line (tax_quote_id, taxable_amount, line_tax_total, effective_rate_pct) VALUES ($bad, 100.00, 1.00, 1.00)".update.run
          .transact(xa)
      conserveBad <- runner.run("CTRL-TAX-VAT-CONSERVE", None).map(_.toOption.get)
    } yield expect(conserveGood.result == "pass") and
      expect(conserveGood.violations == 0L) and
      expect(reproGood.result == "pass") and
      expect(conserveBad.result == "fail") and
      expect(conserveBad.violations >= 1L)
  }
}
