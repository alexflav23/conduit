package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import com.hypervolt.conduit.forecast.LiveForecastService
import doobie.util.transactor.Transactor
import java.time.LocalDate

// Publishes the live tournament-policy forecasts for the whole forecastable population into the H6Q spine
// (forecast_entry source='model', append-only supersession) — the rows the desk's H6Q board reads. Origin =
// the current month boundary; horizon covers the rest of the open quarter plus the next one.
object LivePublish extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  override def run: IO[Unit] =
    IO(LocalDate.now().withDayOfMonth(1)).flatMap(origin =>
      new LiveForecastService[IO](xa)
        // rest of the open quarter + the next full quarter
        .publish(origin, horizonMonths = 6 - ((origin.getMonthValue - 1) % 3))
        .flatMap(n => IO.println(s"published $n forecast_entry rows (origin $origin)"))
    )
}
