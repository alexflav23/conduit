package com.hypervolt.conduit.forecast

import cats.Show
import java.time.LocalDate
import java.util.UUID
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// The headline H6Q invariant (doc 12 §4.4): the org axis and the agent axis are built from the SAME leaves, so
// for any (market, period, scenario) they must tie on EVERY quantity; parents equal the sum of their children;
// ratios are recomputed from summed components, never averaged.
object CoverageSpec extends SimpleIOSuite with Checkers {

  private implicit def showAll[A]: Show[A] = Show.fromToString

  private val month = LocalDate.of(2026, 7, 1)

  // A pool of fixed dimension values so leaves actually share parents (random UUIDs everywhere would never
  // aggregate). One market, one scenario, a few channels/segments/companies/branches/agents.
  private val market   = UUID.fromString("00000000-0000-0000-0000-0000000000a0")
  private val scenario = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
  private def pool(n: Int, tag: Int): Vector[UUID] =
    (0 until n).toVector.map(i => UUID.fromString(f"00000000-0000-0000-0000-${tag}%04d${i}%08d"))

  private val channels  = pool(2, 1)
  private val segments  = Vector("wholesale", "retail")
  private val companies = pool(3, 2)
  private val branches  = pool(4, 3)
  private val agents    = pool(3, 4)
  private val variants  = pool(3, 5)

  private val leafGen: Gen[Leaf] = for {
    ch  <- Gen.oneOf(channels)
    seg <- Gen.oneOf(segments)
    co  <- Gen.oneOf(companies)
    br  <- Gen.oneOf(branches)
    ag  <- Gen.oneOf(agents)
    vr  <- Gen.oneOf(variants)
    fq  <- Gen.choose(0, 500)
    wp  <- Gen.choose(0, 2000).map(i => BigDecimal(i) / 10)
    sh  <- Gen.choose(0, 500)
    av  <- Gen.choose(0, 500)
    src <- Gen.oneOf("manual", "hyperview")
  } yield Leaf(market, Some(ch), Some(ch), Some(seg), None, co, Some(br), ag, vr, month, scenario, fq, wp, sh, av, src)

  private val leavesGen: Gen[List[Leaf]] = Gen.choose(1, 60).flatMap(n => Gen.listOfN(n, leafGen))

  test("branch-axis Σ ≡ agent-axis Σ ≡ market row, on every quantity (the reconciliation guarantee)") {
    forall(leavesGen) { leaves =>
      val rows = Coverage.rollup(leaves)
      def sumAt(level: String)(f: CoverageRow => BigDecimal): BigDecimal =
        rows.filter(_.level == level).map(f).foldLeft(BigDecimal(0))(_ + _)

      val branchF  = sumAt("branch")(r => BigDecimal(r.forecastQty))
      val agentF   = sumAt("agent")(r => BigDecimal(r.forecastQty))
      val marketF  = sumAt("market")(r => BigDecimal(r.forecastQty))
      val branchWp = sumAt("branch")(_.weightedPipelineQty)
      val agentWp  = sumAt("agent")(_.weightedPipelineQty)
      val branchSh = sumAt("branch")(r => BigDecimal(r.shippedQty))
      val agentSh  = sumAt("agent")(r => BigDecimal(r.shippedQty))
      val branchAv = sumAt("branch")(r => BigDecimal(r.activatedQty))
      val agentAv  = sumAt("agent")(r => BigDecimal(r.activatedQty))

      val grandForecast = leaves.map(_.forecastQty).sum

      expect(branchF == agentF) and expect(branchF == marketF) and expect(branchF == BigDecimal(grandForecast)) and
        expect(branchWp == agentWp) and expect(branchSh == agentSh) and expect(branchAv == agentAv)
    }
  }

  test("a parent level equals the sum of its children (company == Σ its branches, matched on the full key)") {
    forall(leavesGen) { leaves =>
      val rows      = Coverage.rollup(leaves)
      val branches  = rows.filter(_.level == "branch")
      val companies = rows.filter(_.level == "company")
      // a branch belongs to its company within the same (market, channel, sub_channel, segment) parent key.
      val ok = companies.forall { c =>
        val kids = branches.filter(b =>
          b.marketId == c.marketId && b.channelId == c.channelId &&
            b.subChannelId == c.subChannelId && b.segment == c.segment && b.companyId == c.companyId
        )
        kids.map(_.forecastQty).sum == c.forecastQty && kids.map(_.shippedQty).sum == c.shippedQty
      }
      expect(ok)
    }
  }

  pureTest("coverage ratio: 0-forecast guards to None; recomputed from components") {
    expect(Coverage.ratio(10, BigDecimal(5), 0).isEmpty) and
      expect(Coverage.ratio(60, BigDecimal(30), 120).contains(BigDecimal("0.7500")))
  }

  pureTest("ratios are recomputed on rollup, not averaged (a 0-forecast leaf can't corrupt the mean)") {
    val a = Leaf(
      market,
      None,
      None,
      None,
      None,
      companies(0),
      Some(branches(0)),
      agents(0),
      variants(0),
      month,
      scenario,
      0,
      BigDecimal(0),
      10,
      0,
      "manual"
    )
    val b = Leaf(
      market,
      None,
      None,
      None,
      None,
      companies(0),
      Some(branches(1)),
      agents(0),
      variants(0),
      month,
      scenario,
      100,
      BigDecimal(0),
      50,
      0,
      "manual"
    )
    val mkt = Coverage.rollup(List(a, b)).find(_.level == "market").get
    // summed: forecast 100, shipped 60 → 0.60 ; a naive average of child ratios (∞ and 0.50) would be wrong.
    expect(mkt.coveragePct.contains(BigDecimal("0.6000")))
  }
}
