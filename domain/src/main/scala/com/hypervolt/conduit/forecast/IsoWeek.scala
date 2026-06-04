package com.hypervolt.conduit.forecast

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

// The weekly cycle code + window (doc 12 §2.1). The cycle window is the SUBMISSION window; it is distinct from
// forecast_entry.period_month (the horizon being forecast). Membership is decided at the cycle's reference TZ,
// but the act of submitting carries the server UTC instant (lateness is reporting-only, never a rejection).
object IsoWeek {

  def code(asOf: LocalDate): String =
    f"${asOf.get(IsoFields.WEEK_BASED_YEAR)}%04d-W${asOf.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}%02d"

  def start(asOf: LocalDate): LocalDate = asOf.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
  def end(asOf: LocalDate): LocalDate   = asOf.`with`(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
}
