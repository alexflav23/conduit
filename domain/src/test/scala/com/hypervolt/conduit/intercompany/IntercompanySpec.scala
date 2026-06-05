package com.hypervolt.conduit.intercompany

import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.RoundingPolicy
import java.util.UUID
import weaver.SimpleIOSuite

// Pure-core checks for the intercompany spine (doc 13 §1–2): topology resolution is config-driven (no code
// branch between year-1 and multi-tier) and transfer prices are method-correct AND batch-specific.
object IntercompanySpec extends SimpleIOSuite {

  private val gbp = Currency.fromCode("GBP").get
  private val usd = Currency.fromCode("USD").get

  private def id(n: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-${n}%012d")

  private def node(n: Int, ccy: String, juris: String, parent: Option[Int]): EntityNode =
    EntityNode(id(n), s"E$n", ccy, juris, parent.map(id))

  // ---- topology ----

  pureTest("year-1: an operating entity with no procurement parent is its own external root, zero hops") {
    val uk  = node(1, "GBP", "GB", None)
    val res = Topology.procurementChain(uk.id, Map(uk.id -> uk))
    expect(res == Right(Chain(uk, Nil)))
  }

  pureTest("single hop: UK <- Luxshare-UK yields one hop, seq 1, root = the seller") {
    val lux = node(2, "USD", "GB", None)
    val uk  = node(1, "GBP", "GB", Some(2))
    val by  = Map(uk.id -> uk, lux.id -> lux)
    Topology.procurementChain(uk.id, by) match {
      case Right(Chain(root, hop :: Nil)) =>
        expect(root == lux) and expect(hop.hopSeq == 1) and
          expect(hop.from == lux && hop.to == uk) and expect(hop.fromCurrency == "USD" && hop.toCurrency == "GBP")
      case other => failure(s"expected one hop, got $other")
    }
  }

  pureTest("multi-tier is config not code: UK <- SG <- (root) yields SG->UK with SG the external root") {
    val sg = node(3, "USD", "SG", None)
    val uk = node(1, "GBP", "GB", Some(3))
    val by = Map(uk.id -> uk, sg.id -> sg)
    Topology.procurementChain(uk.id, by) match {
      case Right(Chain(root, hop :: Nil)) =>
        expect(root == sg) and expect(hop.from == sg && hop.to == uk) and expect(hop.isCrossBorder)
      case other => failure(s"expected SG->UK hop, got $other")
    }
  }

  pureTest("deeper chain: DE <- SG <- hub yields two hops in root-nearest order with ascending seq") {
    val hub = node(4, "USD", "SG", None)
    val sg  = node(3, "USD", "SG", Some(4))
    val de  = node(1, "EUR", "DE", Some(3))
    val by  = Map(de.id -> de, sg.id -> sg, hub.id -> hub)
    Topology.procurementChain(de.id, by) match {
      case Right(Chain(root, h1 :: h2 :: Nil)) =>
        expect(root == hub) and
          expect(h1.from == hub && h1.to == sg && h1.hopSeq == 1) and
          expect(h2.from == sg && h2.to == de && h2.hopSeq == 2)
      case other => failure(s"expected two hops, got $other")
    }
  }

  pureTest("same-jurisdiction hop is not cross-border") {
    val lux = node(2, "USD", "GB", None)
    val uk  = node(1, "GBP", "GB", Some(2))
    Topology.procurementChain(uk.id, Map(uk.id -> uk, lux.id -> lux)) match {
      case Right(Chain(_, hop :: Nil)) => expect(!hop.isCrossBorder)
      case other                       => failure(s"unexpected $other")
    }
  }

  // ---- transfer pricing ----

  private val costPlus15 = TransferPricing.Policy(TransferPricing.Method.CostPlus, Some(BigDecimal(15)), None, None)

  pureTest("cost_plus adds the markup to the specific lot's landed cost") {
    expect(
      TransferPricing.unitPrice(costPlus15, BigDecimal(100), None, gbp, RoundingPolicy.HalfUp) == Right(
        BigDecimal("115.00")
      )
    )
  }

  pureTest("transfer price is batch-specific: two lots of the same SKU, different landed cost -> different TP") {
    val a = TransferPricing.unitPrice(costPlus15, BigDecimal(100), None, gbp, RoundingPolicy.HalfUp)
    val b = TransferPricing.unitPrice(costPlus15, BigDecimal(120), None, gbp, RoundingPolicy.HalfUp)
    expect(a == Right(BigDecimal("115.00"))) and expect(b == Right(BigDecimal("138.00"))) and expect(a != b)
  }

  pureTest("resale_minus takes the downstream resale price less the agreed margin") {
    val p = TransferPricing.Policy(TransferPricing.Method.ResaleMinus, None, Some(BigDecimal(25)), None)
    expect(
      TransferPricing.unitPrice(p, BigDecimal(80), Some(BigDecimal(200)), gbp, RoundingPolicy.HalfUp) == Right(
        BigDecimal("150.00")
      )
    )
  }

  pureTest("resale_minus without a resale anchor is an error") {
    val p = TransferPricing.Policy(TransferPricing.Method.ResaleMinus, None, Some(BigDecimal(25)), None)
    expect(TransferPricing.unitPrice(p, BigDecimal(80), None, gbp, RoundingPolicy.HalfUp).isLeft)
  }

  pureTest("fixed returns the agreed price (in the tp currency)") {
    val p = TransferPricing.Policy(TransferPricing.Method.Fixed, None, None, Some(BigDecimal("99.99")))
    expect(TransferPricing.unitPrice(p, BigDecimal(50), None, usd, RoundingPolicy.HalfUp) == Right(BigDecimal("99.99")))
  }

  pureTest("rounding lands at the currency minor units with the given policy") {
    val p = TransferPricing.Policy(TransferPricing.Method.CostPlus, Some(BigDecimal(10)), None, None)
    // 33.33 * 1.10 = 36.663 -> HalfUp 2dp = 36.66
    expect(
      TransferPricing.unitPrice(p, BigDecimal("33.33"), None, gbp, RoundingPolicy.HalfUp) == Right(BigDecimal("36.66"))
    )
  }

  pureTest("cost_plus without a markup is an error") {
    val p = TransferPricing.Policy(TransferPricing.Method.CostPlus, None, None, None)
    expect(TransferPricing.unitPrice(p, BigDecimal(100), None, gbp, RoundingPolicy.HalfUp).isLeft)
  }
}
