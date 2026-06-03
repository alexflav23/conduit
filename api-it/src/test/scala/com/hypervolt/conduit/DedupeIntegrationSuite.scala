package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import com.hypervolt.conduit.event.IdempotentConsumer
import doobie.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

object DedupeIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("a redelivered event is processed exactly once (dedupe on event_id)") { xa =>
    val eventId = UUID.randomUUID()
    for {
      _        <- sql"DELETE FROM consumer_dedupe".update.run.transact(xa)
      counter  <- Ref.of[IO, Int](0)
      consumer  = new IdempotentConsumer[IO](xa, "ledger-poster")
      first    <- consumer.process(eventId)(counter.update(_ + 1))
      second   <- consumer.process(eventId)(counter.update(_ + 1)) // redelivery
      observed <- counter.get
    } yield expect(first) and expect(!second) and expect(observed == 1)
  }

  test("distinct events are each processed once") { xa =>
    for {
      _       <- sql"DELETE FROM consumer_dedupe".update.run.transact(xa)
      counter <- Ref.of[IO, Int](0)
      c        = new IdempotentConsumer[IO](xa, "ledger-poster")
      _       <- c.process(UUID.randomUUID())(counter.update(_ + 1))
      _       <- c.process(UUID.randomUUID())(counter.update(_ + 1))
      n       <- counter.get
    } yield expect(n == 2)
  }
}
