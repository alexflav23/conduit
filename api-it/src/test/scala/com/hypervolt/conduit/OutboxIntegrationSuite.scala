package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.db.EntityRepo
import com.hypervolt.conduit.event._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

object OutboxIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int            = 1 // tests share one database
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def event(partition: String, n: Int, aggId: UUID, eventId: UUID = UUID.randomUUID()): OutboxEvent =
    OutboxEvent(
      eventId = eventId,
      eventType = "test.event",
      schemaVersion = 1,
      aggregateType = "test",
      aggregateId = aggId,
      partitionKey = partition,
      scope = None,
      correlationId = None,
      causationId = None,
      payload = Json.obj("n" -> Json.fromInt(n)),
      occurredAt = Instant.now()
    )

  private def reset(xa: HikariTransactor[IO]): IO[Unit] =
    sql"TRUNCATE entity, outbox_event RESTART IDENTITY CASCADE".update.run.transact(xa).void

  private def countEntities(xa: HikariTransactor[IO]): IO[Long] =
    sql"SELECT count(*) FROM entity".query[Long].unique.transact(xa)

  test("a business write and its outbox row commit atomically (happy path)") { xa =>
    for {
      _  <- reset(xa)
      _  <- EntityRepo
              .insert("UK Ltd", "GB", "GBP", "operating")
              .flatMap(eid => OutboxRepo.append(event("entity", 1, eid)))
              .transact(xa)
      ec <- countEntities(xa)
      oc <- sql"SELECT count(*) FROM outbox_event".query[Long].unique.transact(xa)
    } yield expect(ec == 1L) and expect(oc == 1L)
  }

  test("a failure in the same transaction rolls back the business write (no dual-write drift)") { xa =>
    val dup = UUID.randomUUID()
    for {
      _       <- reset(xa)
      _       <- OutboxRepo.append(event("seed", 1, UUID.randomUUID(), dup)).transact(xa)
      attempt <- EntityRepo
                   .insert("Should Rollback", "GB", "GBP", "operating")
                   .flatMap(eid => OutboxRepo.append(event("entity", 2, eid, dup))) // duplicate PK -> fails
                   .transact(xa)
                   .attempt
      ec      <- countEntities(xa)
    } yield expect(attempt.isLeft) and expect(ec == 0L)
  }

  test("the relay publishes pending events in per-partition seq order") { xa =>
    val a = UUID.randomUUID()
    val b = UUID.randomUUID()
    val appended = List(("A", 1, a), ("B", 1, b), ("A", 2, a), ("B", 2, b), ("A", 3, a))
    for {
      _         <- reset(xa)
      _         <- appended.traverse_ { case (p, n, id) => OutboxRepo.append(event(p, n, id)).transact(xa).void }
      pub       <- InMemoryEventPublisher.create[IO]
      published <- new OutboxRelay[IO](xa, pub).runOnce().flatMap(_ => pub.published)
    } yield {
      val labels = published.map(e => (e.partitionKey, e.payload.hcursor.get[Int]("n").toOption.getOrElse(-1))).toList
      val aOnly  = labels.collect { case ("A", k) => k }
      val bOnly  = labels.collect { case ("B", k) => k }
      expect(labels == List(("A", 1), ("B", 1), ("A", 2), ("B", 2), ("A", 3))) and
        expect(aOnly == List(1, 2, 3)) and
        expect(bOnly == List(1, 2))
    }
  }

  test("re-running the relay does not re-publish already-published events") { xa =>
    for {
      _     <- reset(xa)
      _     <- OutboxRepo.append(event("A", 1, UUID.randomUUID())).transact(xa)
      pub   <- InMemoryEventPublisher.create[IO]
      relay  = new OutboxRelay[IO](xa, pub)
      first <- relay.runOnce()
      again <- relay.runOnce()
      total <- pub.published.map(_.size)
    } yield expect(first == 1) and expect(again == 0) and expect(total == 1)
  }
}
