package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.tax.RateTableTaxEngine
import com.hypervolt.conduit.tax.TaxQuoteLineReq
import com.hypervolt.conduit.tax.TaxQuoteRequest
import com.hypervolt.conduit.tax.TaxShipPoint
import doobie.hikari.HikariTransactor
import doobie.implicits._
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13-Tax.1 — the rate-table engine against the REAL seeded rates (validates the migration + seeds + the
// effective-dated multi-level lookup). Year-1 is UK VAT 20, but the same seeded tables already drive a US
// multi-level destination stack, Canada GST+PST, reverse charge and import duty/VAT — opening a market is data.
object TaxEngineSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val asOf = LocalDate.parse("2026-06-01")

  private def req(
      context: String,
      from: String,
      to: String,
      status: String,
      currency: String,
      lines: List[TaxQuoteLineReq],
      region: Option[String] = None,
      postcode: Option[String] = None,
      vatId: Option[String] = None
  ): TaxQuoteRequest =
    TaxQuoteRequest(
      context,
      UUID.randomUUID(),
      TaxShipPoint(from, None, None),
      TaxShipPoint(to, region, postcode),
      status,
      vatId,
      None,
      currency,
      asOf,
      lines
    )

  private def line(amt: String): TaxQuoteLineReq =
    TaxQuoteLineReq("l1", None, Some("goods_standard"), None, 1, BigDecimal(amt))

  test("UK domestic: £100 → £20.00 VAT, one national component") { xa =>
    RateTableTaxEngine
      .quoteC(req("order_placed", "GB", "GB", "consumer", "GBP", List(line("100.00"))))
      .transact(xa)
      .map(r =>
        expect(r.supplyKind == "domestic") and
          expect(r.taxTotal == BigDecimal("20.00")) and
          expect(r.lines.head.components.map(_.taxType) == List("VAT")) and
          expect(r.lines.head.components.head.amount == BigDecimal("20.00"))
      )
  }

  test("US California ZIP 90001: state + county + district stack to 8.5% = $8.50") { xa =>
    RateTableTaxEngine
      .quoteC(req("order_placed", "GB", "US", "consumer", "USD", List(line("100.00")), Some("CA"), Some("90001")))
      .transact(xa)
      .map { r =>
        val levels = r.lines.head.components.map(_.level)
        expect(r.supplyKind == "us_destination") and
          expect(levels == List("state", "county", "district")) and
          expect(r.lines.head.lineTaxTotal == BigDecimal("8.50")) and
          expect(
            r.lines.head.components.map(_.amount) == List(BigDecimal("6.00"), BigDecimal("0.25"), BigDecimal("2.25"))
          )
      }
  }

  test("US California without a matching ZIP: only the state component applies (6%)") { xa =>
    RateTableTaxEngine
      .quoteC(req("order_placed", "GB", "US", "consumer", "USD", List(line("100.00")), Some("CA"), Some("60601")))
      .transact(xa)
      .map(r =>
        expect(r.lines.head.components.map(_.level) == List("state")) and
          expect(r.lines.head.lineTaxTotal == BigDecimal("6.00"))
      )
  }

  test("Canada BC: GST 5% (federal) + PST 7% (provincial) = two components, 12%") { xa =>
    RateTableTaxEngine
      .quoteC(req("order_placed", "GB", "CA", "consumer", "CAD", List(line("100.00")), Some("BC")))
      .transact(xa)
      .map { r =>
        val comps = r.lines.head.components
        expect(r.supplyKind == "ca_federal_provincial") and
          expect(comps.map(_.taxType).toSet == Set("GST", "PST")) and
          expect(comps.map(_.amount).sum == BigDecimal("12.00")) and
          expect(r.lines.head.effectiveRatePct == BigDecimal("12.0"))
      }
  }

  test("intra-EU B2B with a valid VAT id is reverse-charged: 0 tax, buyer accounts") { xa =>
    RateTableTaxEngine
      .quoteC(
        req(
          "order_placed",
          "DE",
          "FR",
          "business_with_vat_id",
          "EUR",
          List(line("100.00")),
          vatId = Some("FR12345678901")
        )
      )
      .transact(xa)
      .map(r =>
        expect(r.reverseCharge) and expect(r.taxTotal == BigDecimal(0)) and expect(
          r.supplyKind == "intra_eu_b2b_reverse"
        )
      )
  }

  test("export EU/UK → ROW is zero-rated") { xa =>
    RateTableTaxEngine
      .quoteC(req("order_placed", "GB", "JP", "business", "GBP", List(line("100.00"))))
      .transact(xa)
      .map(r => expect(r.supplyKind == "export") and expect(r.taxTotal == BigDecimal(0)))
  }

  test("import into GB (default HS): 2% duty then 20% import VAT on (value + duty)") { xa =>
    RateTableTaxEngine
      .quoteC(req("intercompany_import", "CN", "GB", "business", "GBP", List(line("1000.00"))))
      .transact(xa)
      .map { r =>
        expect(r.supplyKind == "import") and
          expect(r.dutyTotal == BigDecimal("20.00")) and              // 2% of 1000
          expect(r.importVatTotal.contains(BigDecimal("204.00"))) and // 20% of 1020
          expect(r.importVatRecoverable.contains(true))
      }
  }

  test("import of a charger (HS 8504) into GB: duty-free, import VAT on the value only") { xa =>
    RateTableTaxEngine
      .quoteC(
        req(
          "intercompany_import",
          "CN",
          "GB",
          "business",
          "GBP",
          List(TaxQuoteLineReq("l1", None, Some("goods_standard"), Some("8504.40.90"), 1, BigDecimal("1000.00")))
        )
      )
      .transact(xa)
      .map(r =>
        expect(r.dutyTotal == BigDecimal("0.00")) and
          expect(r.importVatTotal.contains(BigDecimal("200.00")))
      )
  }
}
