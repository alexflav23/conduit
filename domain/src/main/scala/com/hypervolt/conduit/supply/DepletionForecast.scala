package com.hypervolt.conduit.supply

import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate

// Forecasted depletion (the sophisticated bit): project an account's on-shelf stock forward off its own measured
// draw rate. The draw rate is an EWMA of recent weekly activations (recent weeks weighted heavier), with a trend
// term (last-4wk vs prior-4wk) and a confidence cone from the week-to-week variance. Empirical and account-specific
// — no fragile model-run selection — so the runway and stockout date are reproducible from the serial register.
object DepletionForecast {

  private val Alpha        = 0.35 // EWMA smoothing — higher = more weight on recent weeks
  private val HorizonWeeks = 16

  // Reconstruct the ACTUAL on-shelf curve for the recent weeks, working backward from today's balance using the
  // real weekly shipped (+) and activated (−), so it joins seamlessly to the forward projection.
  private def history(
      weeklyAct: Map[LocalDate, Int],
      weeklyShip: Map[LocalDate, Int],
      onShelf: Int,
      weeks: List[LocalDate]
  ): List[Json] = {
    var bal = onShelf.toDouble
    val rev = weeks.reverse.map { w =>
      val point = Json.obj(
        "week"      -> w.toString.asJson,
        "on_shelf"  -> math.round(math.max(0.0, bal)).asJson,
        "activated" -> weeklyAct.getOrElse(w, 0).asJson
      )
      bal =
        bal - weeklyShip.getOrElse(w, 0) + weeklyAct.getOrElse(w, 0) // step back to the prior week's closing balance
      point
    }
    rev.reverse
  }

  // weekly/weeklyShip: (week-start, units) ascending. onShelf: current on-shelf units. anchor: "today".
  def project(
      weekly: List[(LocalDate, Int)],
      weeklyShip: List[(LocalDate, Int)],
      onShelf: Int,
      anchor: LocalDate
  ): Json = {
    val recent    = weekly.takeRight(26)
    val rates     = recent.map(_._2.toDouble)
    val histWeeks = (weekly.map(_._1) ++ weeklyShip.map(_._1)).distinct.sorted.takeRight(26)
    val histJson  = history(weekly.toMap, weeklyShip.toMap, onShelf, histWeeks)
    if (rates.isEmpty || onShelf <= 0)
      return Json.obj(
        "on_shelf"      -> onShelf.asJson,
        "weekly_rate"   -> 0.0.asJson,
        "daily_rate"    -> 0.0.asJson,
        "runway_days"   -> Json.Null,
        "stockout_date" -> Json.Null,
        "trend_pct"     -> 0.0.asJson,
        "method"        -> "EWMA of recent weekly activations".asJson,
        "anchor"        -> anchor.toString.asJson,
        "history"       -> Json.fromValues(histJson),
        "projection"    -> Json.arr()
      )

    val ewma = rates.foldLeft(rates.head)((acc, r) => Alpha * r + (1 - Alpha) * acc)
    val mean = rates.sum / rates.length
    val std  = math.sqrt(rates.map(r => (r - mean) * (r - mean)).sum / rates.length)

    val last4  = rates.takeRight(4)
    val prior4 = rates.dropRight(4).takeRight(4)
    val trendPct =
      if (prior4.nonEmpty && prior4.sum > 0)
        ((last4.sum / last4.length) - (prior4.sum / prior4.length)) / (prior4.sum / prior4.length) * 100
      else 0.0

    val weeklyRate = math.max(ewma, 0.0)
    val dailyRate  = weeklyRate / 7.0
    val runwayDays = if (weeklyRate > 0) onShelf / dailyRate else Double.PositiveInfinity
    val stockout   = if (runwayDays.isFinite) Some(anchor.plusDays(math.round(runwayDays))) else None

    // Forward cone: deplete on-shelf each week by the rate; low/high bands widen with √week × weekly std.
    val proj = (1 to HorizonWeeks).map { w =>
      val wk   = anchor.plusWeeks(w.toLong)
      val band = std * math.sqrt(w.toDouble)
      val mid  = math.max(0.0, onShelf - weeklyRate * w)
      val low  = math.max(0.0, onShelf - (weeklyRate + band) * w)
      val high = math.max(0.0, onShelf - math.max(0.0, weeklyRate - band) * w)
      Json.obj(
        "week"               -> wk.toString.asJson,
        "expected_draw"      -> math.round(weeklyRate).asJson,
        "projected_on_shelf" -> math.round(mid).asJson,
        "low"                -> math.round(low).asJson,
        "high"               -> math.round(high).asJson
      )
    }

    Json.obj(
      "on_shelf"      -> onShelf.asJson,
      "weekly_rate"   -> math.round(weeklyRate).asJson,
      "daily_rate"    -> (math.round(dailyRate * 10) / 10.0).asJson,
      "runway_days"   -> (if (runwayDays.isFinite) math.round(runwayDays).asJson else Json.Null),
      "stockout_date" -> stockout.map(_.toString).asJson,
      "trend_pct"     -> (math.round(trendPct * 10) / 10.0).asJson,
      "method"        -> "EWMA of recent weekly activations + variance cone".asJson,
      "anchor"        -> anchor.toString.asJson,
      "history"       -> Json.fromValues(histJson),
      "projection"    -> Json.fromValues(proj)
    )
  }
}
