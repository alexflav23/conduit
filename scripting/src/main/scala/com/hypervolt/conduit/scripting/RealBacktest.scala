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

  override def run: IO[Unit] = {
    val engine = new BacktestEngine[IO](xa)
    val origins = List(
      LocalDate.of(2023, 4, 1),
      LocalDate.of(2023, 7, 1),
      LocalDate.of(2023, 10, 1),
      LocalDate.of(2024, 1, 1),
      LocalDate.of(2024, 4, 1),
      LocalDate.of(2024, 7, 1),
      LocalDate.of(2024, 10, 1),
      LocalDate.of(2025, 1, 1),
      LocalDate.of(2025, 4, 1),
      LocalDate.of(2025, 7, 1),
      LocalDate.of(2025, 10, 1),
      LocalDate.of(2026, 1, 1),
      LocalDate.of(2026, 4, 1),
      LocalDate.of(2026, 7, 1) // the FORWARD origin: 6-month horizon covers Q3'26 and Q4'26
    )
    origins.traverse_(o =>
      // the forward origin carries a 6-month horizon (Q3'26 = months 0-2, Q4'26 = 3-5) — fitting Q4 from an
      // origin past the data's edge would zero-fill phantom months and collapse every model
      engine.runOrigin(o, horizonMonths = if (o == LocalDate.of(2026, 7, 1)) 6 else 3) *>
        engine.scoreOrigin(o, asOf = LocalDate.of(2026, 6, 1)) *>
        IO.println(s"origin $o: fitted + scored")
    ) *> IO.println("real backtest complete")
  }
}
