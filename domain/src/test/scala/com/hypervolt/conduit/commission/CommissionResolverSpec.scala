package com.hypervolt.conduit.commission

import java.time.Instant
import java.util.UUID
import weaver.SimpleIOSuite

object CommissionResolverSpec extends SimpleIOSuite {

  private val team    = UUID.randomUUID()
  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()
  private val now     = Instant.parse("2026-06-03T00:00:00Z")

  private def scheme(
      rate: String,
      exc: String = "zero",
      from: String = "2026-01-01T00:00:00Z",
      to: Option[String] = None
  ): CommissionScheme =
    CommissionScheme(
      UUID.randomUUID(),
      "gross_margin",
      BigDecimal(rate),
      exc,
      Instant.parse(from),
      to.map(Instant.parse)
    )

  private def assign(s: CommissionScheme, t: Option[UUID], ch: Option[UUID], mk: Option[UUID]): ResolvableScheme =
    ResolvableScheme(s, SchemeAssignment(s.id, t, ch, mk, None))

  pureTest("a more specific assignment beats a general one, within the validity window") {
    val general = scheme("5")
    val teamCh  = scheme("8")
    val exact   = scheme("10")
    val candidates = List(
      assign(general, None, None, None),
      assign(teamCh, Some(team), Some(channel), None),
      assign(exact, Some(team), Some(channel), Some(market))
    )
    expect(
      CommissionResolver
        .resolve(candidates, Some(team), channel, market, None, now)
        .map(_.ratePct)
        .contains(BigDecimal("10"))
    )
  }

  pureTest("a scheme outside its validity window does not resolve") {
    val expired = scheme("9", from = "2025-01-01T00:00:00Z", to = Some("2026-01-01T00:00:00Z"))
    expect(
      CommissionResolver
        .resolve(List(assign(expired, Some(team), None, None)), Some(team), channel, market, None, now)
        .isEmpty
    )
  }

  pureTest("wholesale and retail teams resolve different schemes") {
    val retailTeam = UUID.randomUUID()
    val wholesale  = scheme("5")
    val retail     = scheme("12")
    val candidates = List(assign(wholesale, Some(team), None, None), assign(retail, Some(retailTeam), None, None))
    val wsRate     = CommissionResolver.resolve(candidates, Some(team), channel, market, None, now).map(_.ratePct)
    val rtRate     = CommissionResolver.resolve(candidates, Some(retailTeam), channel, market, None, now).map(_.ratePct)
    expect(wsRate.contains(BigDecimal("5"))) and expect(rtRate.contains(BigDecimal("12"))) and expect(wsRate != rtRate)
  }

  pureTest("gross-margin commission; zero on an unapproved exception line") {
    val s = scheme("10", exc = "zero")
    val standard = CommissionResolver.lineCommission(
      s,
      CommissionLineInput(BigDecimal("587.50"), BigDecimal("400.00"), 2, "standard", exceptionApproved = false)
    )
    val unapproved = CommissionResolver.lineCommission(
      s,
      CommissionLineInput(BigDecimal("587.50"), BigDecimal("400.00"), 2, "exception", exceptionApproved = false)
    )
    // gross margin = (587.50-400)*2 = 375.00 ; 10% = 37.50
    expect(standard._1 == BigDecimal("375.00")) and expect(standard._2 == BigDecimal("37.50")) and
      expect(unapproved._2 == BigDecimal("0.00"))
  }
}
