package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.forecast.BacktestEngine
import doobie.util.transactor.Transactor
import java.time.LocalDate

// Runs the REAL rolling-origin loop over the scraped HubSpot history (2021→) seeded into the local stack:
// six quarterly origins, each trained on censored history and scored against the actuals that followed —
// including the product owner's case: train ≤ Q1'25 → predict Q2'25 → compare with the real Q2'25.
object RealBacktest extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  // Origins derive from the CALENDAR so quarter-close auto-extends (doc 26: the loop deepens itself):
  // quarterly from the fixed start through the current quarter (the open quarter scores on its closed
  // months), a NOWCAST origin at the current month boundary (freshest data edge), and a FORWARD origin at
  // the next quarter start with a 6-month horizon — fitting past the data's edge would zero-fill phantom
  // months and collapse every model.
  private val FirstOrigin = LocalDate.of(2023, 4, 1)

  override def run: IO[Unit] =
    IO(LocalDate.now()).flatMap { today =>
      val engine       = new BacktestEngine[IO](xa)
      val monthStart   = today.withDayOfMonth(1)
      val quarterStart = monthStart.withMonth(((monthStart.getMonthValue - 1) / 3) * 3 + 1)
      val nextQuarter  = quarterStart.plusMonths(3)
      val nowcast      = Option.when(monthStart.isAfter(quarterStart))(monthStart)
      val quarterly =
        Iterator.iterate(FirstOrigin)(_.plusMonths(3)).takeWhile(!_.isAfter(quarterStart)).toList
      val origins = quarterly ++ nowcast.toList ++ List(nextQuarter)
      val horizonOf = (o: LocalDate) =>
        if (o == nextQuarter) 6
        else if (nowcast.contains(o)) (3 - java.time.temporal.ChronoUnit.MONTHS.between(quarterStart, o)).toInt
        else 3
      origins.traverse_(o =>
        engine.runOrigin(o, horizonMonths = horizonOf(o)) *>
          engine.scoreOrigin(o, asOf = monthStart) *>
          engine.materializeSelections(o) *>
          IO.println(s"origin $o: fitted + scored + materialized (horizon ${horizonOf(o)})")
      ) *> IO.println("real backtest complete")
    }
}
