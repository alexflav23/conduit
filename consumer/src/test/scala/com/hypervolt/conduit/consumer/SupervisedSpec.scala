package com.hypervolt.conduit.consumer

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import scala.concurrent.duration._
import weaver.SimpleIOSuite

// doc 19 §C — the crash-loop fix: a consumer that keeps dying restarts with backoff under its own name and
// NEVER takes a sibling down. The previous shape (bare parSequence_) cancelled the outbox relay when any of
// the nine consumers died.
object SupervisedSpec extends SimpleIOSuite {

  implicit private val logger: Logger[IO] = NoOpLogger[IO]

  test("a dying task is restarted with backoff instead of propagating") {
    Ref.of[IO, Int](0).flatMap { attempts =>
      val task = attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("boom"))
      Supervised("dying", task.void, initialDelay = 5.millis, maxDelay = 20.millis)
        .timeoutTo(300.millis, IO.unit) *>
        attempts.get.map(n => expect(n >= 3))
    }
  }

  test("a crash-looping consumer never takes a healthy sibling down") {
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled.flatMap {
      case (deaths, beats) =>
        val dying   = deaths.update(_ + 1) *> IO.raiseError(new RuntimeException("boom")).void
        val healthy = (beats.update(_ + 1) *> IO.sleep(10.millis)).foreverM.void
        List(
          Supervised("dying", dying, initialDelay = 5.millis, maxDelay = 10.millis),
          Supervised("healthy", healthy, initialDelay = 5.millis, maxDelay = 10.millis)
        ).parSequence_.timeoutTo(300.millis, IO.unit) *>
          (deaths.get, beats.get).tupled.map {
            case (d, b) => expect(d >= 3) and expect(b >= 10) // both kept going, independently
          }
    }
  }
}
