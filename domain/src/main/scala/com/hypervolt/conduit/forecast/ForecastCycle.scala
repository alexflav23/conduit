package com.hypervolt.conduit.forecast

import cats.effect.Async
import cats.syntax.all._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// The forecast engine's rolling-origin cycle as an in-process job (doc 26 §5–6): calendar-derived origins
// (quarterly from the fixed start through the current quarter, a nowcast at the month edge, a forward origin at
// next-quarter start), each fitted → scored → its champion materialized, then the live origin published. Every
// origin captures its censored depletion snapshot (BacktestEngine.runOrigin / LiveForecast.publish), so the
// run history + depletion deltas accrue automatically as the consumer runs — no external script. Idempotent
// end to end (runs/predictions/scores/snapshots ON CONFLICT), so re-running converges to the same state.
final class ForecastCycle[F[_]: Async](xa: Transactor[F]) {

  private val engine = new BacktestEngine[F](xa)
  private val live   = new LiveForecastService[F](xa)

  private val FirstOrigin = LocalDate.of(2023, 4, 1)

  // (the backtest origins, the live origin, the per-origin horizon) for a given day — the calendar IS the schedule.
  def plan(today: LocalDate): (List[LocalDate], LocalDate, LocalDate => Int) = {
    val monthStart   = today.withDayOfMonth(1)
    val quarterStart = monthStart.withMonth(((monthStart.getMonthValue - 1) / 3) * 3 + 1)
    val nextQuarter  = quarterStart.plusMonths(3)
    val nowcast      = Option.when(monthStart.isAfter(quarterStart))(monthStart)
    val quarterly    = Iterator.iterate(FirstOrigin)(_.plusMonths(3)).takeWhile(!_.isAfter(quarterStart)).toList
    val origins      = quarterly ++ nowcast.toList ++ List(nextQuarter)
    val horizonOf = (o: LocalDate) =>
      if (o == nextQuarter) 6
      else if (nowcast.contains(o)) (3 - ChronoUnit.MONTHS.between(quarterStart, o)).toInt
      else 3
    (origins, monthStart, horizonOf)
  }

  private def liveHorizon(origin: LocalDate): Int = 6 - ((origin.getMonthValue - 1) % 3)

  // Fit + score + materialize every origin (the learning loop), then publish the live forecast. Snapshots ride
  // runOrigin / publish, so the depletion history is captured as a side effect of the engine simply running.
  def runOnce(today: LocalDate): F[Unit] = {
    val (origins, liveOrigin, horizonOf) = plan(today)
    origins.traverse_(o =>
      engine.runOrigin(o, horizonOf(o)) *>
        engine.scoreOrigin(o, asOf = today.withDayOfMonth(1)) *>
        engine.materializeSelections(o)
    ) *> live.publish(liveOrigin, liveHorizon(liveOrigin)).void
  }
}
