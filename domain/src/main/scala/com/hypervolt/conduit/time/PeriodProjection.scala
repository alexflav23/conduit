package com.hypervolt.conduit.time

import java.time.Instant
import java.time.ZoneId

// The instant is immutable; the period is a projection (doc 14 §2). A transaction's day/week/month/
// quarter is computed from its UTC `occurred_at` under a reporting timezone + fiscal calendar — never
// baked into the row. Reslicing under a different TZ is therefore a re-projection, not a migration.
// Fiscal calendar = calendar year (Jan start) for now; a fiscal offset slots in here without schema change.
sealed abstract class PeriodScope(val code: String)
object PeriodScope {
  case object Day     extends PeriodScope("day")
  case object Month   extends PeriodScope("month")
  case object Quarter extends PeriodScope("quarter")
  case object Year    extends PeriodScope("year")
}

object PeriodProjection {

  def periodKey(occurredAt: Instant, reportingTz: ZoneId, scope: PeriodScope): String = {
    val zdt = occurredAt.atZone(reportingTz)
    scope match {
      case PeriodScope.Day     => zdt.toLocalDate.toString
      case PeriodScope.Month   => f"${zdt.getYear}-${zdt.getMonthValue}%02d"
      case PeriodScope.Quarter => s"${zdt.getYear}-Q${(zdt.getMonthValue - 1) / 3 + 1}"
      case PeriodScope.Year    => zdt.getYear.toString
    }
  }
}
