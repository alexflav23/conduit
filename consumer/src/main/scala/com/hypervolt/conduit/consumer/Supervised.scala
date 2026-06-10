package com.hypervolt.conduit.consumer

import cats.effect.Temporal
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import scala.concurrent.duration._

// Per-consumer supervision (doc 19 §C): one consumer's death must never take the others down — least of all
// the outbox relay. Each task restarts independently with exponential backoff (named in the error log, so the
// dying consumer is identifiable from one line); a task that ran cleanly for a while earns its backoff reset.
object Supervised {

  def apply[F[_]: Temporal: Logger](
      name: String,
      task: F[Unit],
      initialDelay: FiniteDuration = 10.seconds,
      maxDelay: FiniteDuration = 5.minutes,
      resetAfter: FiniteDuration = 10.minutes
  ): F[Unit] = {
    def attempt(delay: FiniteDuration): F[Unit] =
      Temporal[F].monotonic.flatMap(start =>
        task.attempt.flatMap { outcome =>
          Temporal[F].monotonic.flatMap { end =>
            val next = if (end - start > resetAfter) initialDelay else (delay * 2).min(maxDelay)
            val log = outcome match {
              case Left(e)  => Logger[F].error(e)(s"consumer '$name' died — restarting in $delay")
              case Right(_) => Logger[F].error(s"consumer '$name' exited unexpectedly — restarting in $delay")
            }
            log *> Temporal[F].sleep(delay) *> attempt(next)
          }
        }
      )
    attempt(initialDelay)
  }
}
