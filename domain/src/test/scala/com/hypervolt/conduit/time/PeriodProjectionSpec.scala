package com.hypervolt.conduit.time

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import weaver.SimpleIOSuite

object PeriodProjectionSpec extends SimpleIOSuite {

  // The acceptance criterion: the SAME events bucket into different months under two reporting
  // timezones purely by re-projection (doc 07 M1 / doc 14 §2.2).
  pureTest("the same instant reslices into different months under two reporting timezones") {
    val t   = Instant.parse("2026-06-30T23:30:00Z")
    val hon = PeriodProjection.periodKey(t, ZoneId.of("Pacific/Honolulu"), PeriodScope.Month)
    val lon = PeriodProjection.periodKey(t, ZoneId.of("Europe/London"), PeriodScope.Month)
    expect(hon == "2026-06") and expect(lon == "2026-07") and expect(hon != lon)
  }

  pureTest("day / quarter / year keys") {
    val t = Instant.parse("2026-06-30T23:30:00Z")
    val z = ZoneId.of("Europe/London")
    expect(PeriodProjection.periodKey(t, z, PeriodScope.Day) == "2026-07-01") and
      expect(PeriodProjection.periodKey(t, z, PeriodScope.Quarter) == "2026-Q3") and
      expect(PeriodProjection.periodKey(t, z, PeriodScope.Year) == "2026")
  }

  private def period(status: PeriodStatus): AccountingPeriod =
    AccountingPeriod(UUID.randomUUID(), PeriodScope.Month, "2026-06", ZoneId.of("Europe/London"), status)

  pureTest("posting to a locked period is rejected; open and closed are postable") {
    expect(PeriodGuard.ensurePostable(period(PeriodStatus.Locked)).isLeft) and
      expect(PeriodGuard.ensurePostable(period(PeriodStatus.Open)).isRight) and
      expect(PeriodGuard.ensurePostable(period(PeriodStatus.Closed)).isRight)
  }
}
