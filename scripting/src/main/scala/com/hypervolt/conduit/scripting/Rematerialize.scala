package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.forecast.BacktestEngine
import doobie.util.transactor.Transactor
import java.time.LocalDate

// Re-runs ONLY the selection materialization over the existing model_accuracy ledger — for iterating on
// selection/guard logic without the 20-minute fit+score refit. The per-model evidence is untouched.
object Rematerialize extends IOApp.Simple {

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
      LocalDate.of(2026, 6, 1),
      LocalDate.of(2026, 7, 1)
    )
    origins.traverse_(o => engine.materializeSelections(o) *> IO.println(s"origin $o: rematerialized")) *>
      IO.println("rematerialization complete")
  }
}
