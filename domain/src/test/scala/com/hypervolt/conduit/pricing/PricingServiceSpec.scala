package com.hypervolt.conduit.pricing

import java.util.UUID
import weaver.SimpleIOSuite

object PricingServiceSpec extends SimpleIOSuite {

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()
  private val entity  = UUID.randomUUID()

  private def candidate(
      ch: Option[UUID],
      mk: Option[UUID],
      en: Option[UUID],
      price: String,
      minQty: Int = 1,
      version: Int = 1,
      maxDisc: String = "0",
      appliesTo: String = "open_list",
      upToQty: Option[Int] = None
  ): PriceRuleCandidate =
    PriceRuleCandidate(
      UUID.randomUUID(),
      Some(UUID.randomUUID()),
      appliesTo,
      ch,
      mk,
      en,
      BigDecimal(price),
      BigDecimal(maxDisc),
      minQty,
      upToQty,
      version,
      "GB_STANDARD",
      BigDecimal("20")
    )

  pureTest("the most specific rule wins (channel+market+entity beats channel-only beats global)") {
    val global   = candidate(None, None, None, "600")
    val chanOnly = candidate(Some(channel), None, None, "590")
    val exact    = candidate(Some(channel), Some(market), Some(entity), "587.50")
    val r        = PricingService.resolve(List(global, chanOnly, exact), channel, market, Some(entity))
    expect(r.exists(_.exVat == BigDecimal("587.50")))
  }

  pureTest("a higher volume break (min_qty) wins when qty qualifies") {
    val unit = candidate(Some(channel), Some(market), None, "587.50", minQty = 1)
    val bulk = candidate(Some(channel), Some(market), None, "550.00", minQty = 100)
    // both are in the candidate set because the query already filtered min_qty <= qty
    val r = PricingService.resolve(List(unit, bulk), channel, market, None)
    expect(r.exists(_.exVat == BigDecimal("550.00")))
  }

  pureTest("a customer-scoped agreement beats the open_list even when less channel-specific (doc 24 §2)") {
    val openExact = candidate(Some(channel), Some(market), Some(entity), "587.50", appliesTo = "open_list")
    val custWide  = candidate(None, None, None, "520.00", appliesTo = "customer_set")
    val r         = PricingService.resolve(List(openExact, custWide), channel, market, Some(entity))
    expect(r.exists(_.exVat == BigDecimal("520.00")))
  }

  pureTest("a within-band discount is standard; an out-of-band discount is an exception") {
    val res =
      PriceResolution(
        UUID.randomUUID(),
        None,
        BigDecimal("600.00"),
        BigDecimal("10.00"),
        "GB_STANDARD",
        BigDecimal("20")
      )
    expect(PricingService.categorise(res, BigDecimal("550.00")) == "standard") and // 8.33% <= 10%
      expect(PricingService.categorise(res, BigDecimal("500.00")) == "exception")  // 16.67% > 10%
  }

  pureTest("VAT and a quote total compute correctly (GB 20%)") {
    val res =
      PriceResolution(UUID.randomUUID(), None, BigDecimal("587.50"), BigDecimal("0"), "GB_STANDARD", BigDecimal("20"))
    val line  = PricingService.priceLine(res, QuoteLine("HV-310", 2, None))
    val quote = PricingService.assemble(List(line))
    expect(line.vat == BigDecimal("235.00")) and // 587.50 * 2 * 20%
      expect(quote.subtotalExVat == BigDecimal("1175.00")) and
      expect(quote.totalIncVat == BigDecimal("1410.00")) and
      expect(!quote.requiresException)
  }
}
