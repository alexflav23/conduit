package com.hypervolt.conduit.tax

import cats.Show
import com.hypervolt.conduit.money.RoundingPolicy
import java.time.LocalDate
import java.util.UUID
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// The pure rate computation (doc 16 §4.4a): one tax_rate row → one component; multi-level destinations sum their
// components; rounding is per regime (line vs invoice) and the invoice boundary conserves to the penny (doc 14 §1.3).
object TaxComputeSpec extends SimpleIOSuite with Checkers {

  private implicit def showAll[A]: Show[A] = Show.fromToString

  private def line(ref: String, amt: String): TaxQuoteLineReq =
    TaxQuoteLineReq(ref, None, Some("goods_standard"), None, 1, BigDecimal(amt))

  private def req(currency: String, lines: List[TaxQuoteLineReq]): TaxQuoteRequest =
    TaxQuoteRequest(
      "order_placed",
      UUID.randomUUID(),
      TaxShipPoint("GB", None, None),
      TaxShipPoint("GB", None, None),
      "consumer",
      None,
      None,
      currency,
      LocalDate.parse("2026-06-01"),
      lines
    )

  private def rate(level: String, region: Option[String], name: String, pct: String): RateRow =
    RateRow(
      "VAT",
      "GB",
      region,
      None,
      level,
      Some("goods_standard"),
      name,
      BigDecimal(pct),
      "standard",
      recoverable = true
    )

  private val domesticFacts =
    SupplyFacts("domestic", "VAT", "GB_STANDARD", reverseCharge = false, zeroRated = false, isImport = false)
  private val usFacts = SupplyFacts(
    "us_destination",
    "sales_tax",
    "US_DESTINATION",
    reverseCharge = false,
    zeroRated = false,
    isImport = false
  )

  pureTest("UK 20% on £100 is a single £20.00 national component") {
    val r = req("GBP", List(line("l1", "100.00")))
    val resp = RateTableTaxEngine.compute(
      r,
      domesticFacts,
      List(r.lines.head -> List(rate("national", None, "UK VAT", "20"))),
      "line",
      RoundingPolicy.HalfUp
    )
    expect(resp.taxTotal == BigDecimal("20.00")) and
      expect(resp.lines.head.components.length == 1) and
      expect(resp.lines.head.effectiveRatePct == BigDecimal("20")) and
      expect(resp.lines.head.regimeCode.contains("GB_STANDARD"))
  }

  pureTest("US California ZIP stacks state 6% + county 0.25% + district 2.25% = $8.50 on $100") {
    val r = req("USD", List(line("l1", "100.00")))
    val rows = List(
      RateRow(
        "sales_tax",
        "US",
        Some("CA"),
        None,
        "state",
        Some("goods_standard"),
        "California",
        BigDecimal("6.0"),
        "standard",
        recoverable = true
      ),
      RateRow(
        "sales_tax",
        "US",
        Some("CA"),
        Some("900"),
        "county",
        Some("goods_standard"),
        "Los Angeles",
        BigDecimal("0.25"),
        "standard",
        recoverable = true
      ),
      RateRow(
        "sales_tax",
        "US",
        Some("CA"),
        Some("900"),
        "district",
        Some("goods_standard"),
        "LA Metro",
        BigDecimal("2.25"),
        "standard",
        recoverable = true
      )
    )
    val resp  = RateTableTaxEngine.compute(r, usFacts, List(r.lines.head -> rows), "line", RoundingPolicy.HalfUp)
    val comps = resp.lines.head.components
    expect(comps.map(_.level) == List("state", "county", "district")) and
      expect(comps.map(_.amount) == List(BigDecimal("6.00"), BigDecimal("0.25"), BigDecimal("2.25"))) and
      expect(resp.lines.head.lineTaxTotal == BigDecimal("8.50")) and
      expect(resp.lines.head.effectiveRatePct == BigDecimal("8.50"))
  }

  pureTest("most specific row wins within a level: a category-specific reduced rate beats the generic one") {
    val r = req("GBP", List(line("l1", "100.00")))
    val rows = List(
      RateRow(
        "VAT",
        "GB",
        None,
        None,
        "national",
        None,
        "UK VAT generic",
        BigDecimal("20"),
        "standard",
        recoverable = true
      ),
      RateRow(
        "VAT",
        "GB",
        None,
        None,
        "national",
        Some("goods_standard"),
        "UK VAT specific",
        BigDecimal("5"),
        "reduced",
        recoverable = true
      )
    )
    val resp = RateTableTaxEngine.compute(r, domesticFacts, List(r.lines.head -> rows), "line", RoundingPolicy.HalfUp)
    expect(resp.lines.head.components.length == 1) and expect(resp.taxTotal == BigDecimal("5.00"))
  }

  // The penny invariant under the invoice rounding boundary: sum exact line taxes, round the total once, re-allocate.
  private val multiLine: Gen[List[BigDecimal]] = for {
    n  <- Gen.choose(2, 8)
    ws <- Gen.listOfN(n, Gen.choose(1L, 50000L).map(c => (BigDecimal(c) / 100).setScale(2)))
  } yield ws

  test("invoice-boundary rounding conserves: Σ line tax == rounded grand total, and Σ components == line tax") {
    forall(multiLine) { amounts =>
      val lines = amounts.zipWithIndex.map {
        case (a, i) => TaxQuoteLineReq(s"l$i", None, Some("goods_standard"), None, 1, a)
      }
      val r             = req("GBP", lines)
      val perLine       = lines.map(l => l -> List(rate("national", None, "UK VAT", "20")))
      val resp          = RateTableTaxEngine.compute(r, domesticFacts, perLine, "invoice", RoundingPolicy.HalfUp)
      val expectedTotal = (amounts.sum * 20 / 100).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      expect(resp.lines.map(_.lineTaxTotal).sum == resp.taxTotal) and
        expect(resp.taxTotal == expectedTotal) and
        expect(resp.lines.forall(l => l.components.map(_.amount).sum == l.lineTaxTotal))
    }
  }
}
