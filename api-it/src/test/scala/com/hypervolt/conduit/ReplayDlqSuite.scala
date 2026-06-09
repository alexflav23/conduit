package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.event.CompletenessRepo
import com.hypervolt.conduit.event.DlqStore
import com.hypervolt.conduit.event.IdempotentConsumer
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.event.ReplayService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M-NFR.2 (doc 19 §C.4) — the ops/DR spine: projections rebuild by replaying the immutable outbox log through the
// SAME consumer handler (no second write path); poison messages park on the DLQ and drain on a fix-then-replay; and
// completeness is a re-performable control. All Postgres-only — the log IS the truth.
object ReplayDlqSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def ddl(xa: HikariTransactor[IO]): IO[Unit] =
    (sql"CREATE TABLE IF NOT EXISTS replay_demo (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), event_id UUID NOT NULL)".update.run *>
      sql"TRUNCATE replay_demo".update.run).transact(xa).void

  private def appendEvents(xa: HikariTransactor[IO], aggType: String, n: Int): IO[List[UUID]] =
    (1 to n).toList.traverse { i =>
      val id = UUID.randomUUID()
      OutboxRepo
        .append(
          OutboxEvent(
            id,
            "demo.created",
            1,
            aggType,
            UUID.randomUUID(),
            id.toString,
            None,
            None,
            None,
            Json.obj("n" -> i.asJson),
            Instant.now(),
            "test"
          )
        )
        .transact(xa)
        .as(id)
    }

  // The read-model handler: one row per APPLICATION (no unique on event_id), so a double-apply would be visible.
  private def apply1(xa: HikariTransactor[IO], id: UUID): IO[Unit] =
    sql"INSERT INTO replay_demo (event_id) VALUES ($id)".update.run.transact(xa).void

  private def count(xa: HikariTransactor[IO]): IO[Long] =
    sql"SELECT count(*) FROM replay_demo".query[Long].unique.transact(xa)

  test("projection rebuild: dedupe blocks double-apply on redelivery; truncate+reset+replay reconstructs identically") {
    xa =>
      val ic  = new IdempotentConsumer[IO](xa, "rebuild-grp")
      val rep = new ReplayService[IO](xa)
      val agg = "demo-rebuild-" + UUID.randomUUID()
      for {
        _   <- ddl(xa)
        ids <- appendEvents(xa, agg, 5)
        _   <- ids.traverse_(id => ic.process(id)(apply1(xa, id)))               // initial live build
        c1  <- count(xa)
        _   <- ids.traverse_(id => ic.process(id)(apply1(xa, id)))               // redelivery — must be deduped
        c2  <- count(xa)
        _   <- sql"TRUNCATE replay_demo".update.run.transact(xa)                 // discard the read model
        n   <- rep.rebuild("rebuild-grp", Some(agg))(e => apply1(xa, e.eventId)) // reset dedupe + replay the log
        c3  <- count(xa)
      } yield expect(c1 == 5L) and          // built
        expect(c2 == 5L) and                // redelivery deduped — no double-apply
        expect(n == 5) and expect(c3 == 5L) // rebuilt identically from the log, same handler
  }

  test("DLQ: a poison message parks (not lost); fix-then-replay drains it with no double-effect; control flips") { xa =>
    val ic     = new IdempotentConsumer[IO](xa, "dlq-grp")
    val rep    = new ReplayService[IO](xa)
    val runner = new ControlRunner[IO](xa)
    val agg    = "demo-dlq-" + UUID.randomUUID()
    for {
      _   <- ddl(xa)
      ids <- appendEvents(xa, agg, 5)
      poison = ids(2)
      // live consumption: the poison event's handler throws → parked on the DLQ, claim released
      _ <- ids.traverse_(id =>
        ic.processOrDlq(id)(if (id == poison) IO.raiseError(new RuntimeException("boom")) else apply1(xa, id))
      )
      applied1 <- count(xa)
      depth1   <- DlqStore.depth.transact(xa)
      ctrlBad  <- runner.run("CTRL-DLQ-EMPTY", None)
      // fix deployed (handler now succeeds) → scoped DLQ replay drains it
      drained  <- rep.replayDlq("dlq-grp")(e => apply1(xa, e.eventId))
      applied2 <- count(xa)
      depth2   <- DlqStore.depth.transact(xa)
      ctrlOk   <- runner.run("CTRL-DLQ-EMPTY", None)
    } yield expect(applied1 == 4L) and expect(depth1 == 1L) and // 4 applied, 1 parked
      expect(ctrlBad.toOption.exists(c => c.result == "fail" && c.violations >= 1)) and
      expect(drained == 1) and expect(applied2 == 5L) and expect(depth2 == 0L) and // drained, now 5, no dupes
      expect(ctrlOk.toOption.exists(c => c.result == "pass" && c.violations == 0))
  }

  test("completeness: an outbox event stuck unpublished past the SLO is detected, and clears once published") { xa =>
    val agg = "demo-stuck-" + UUID.randomUUID()
    val id  = UUID.randomUUID()
    for {
      _     <- sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id,
                  partition_key, payload, occurred_at, status, created_at)
                VALUES ($id, 'demo.created', 1, $agg, ${UUID.randomUUID()}, ${id.toString}, '{}'::jsonb, now(),
                  'pending', now() - interval '10 minutes')""".update.run.transact(xa)
      stuck <- CompletenessRepo.unpublishedOlderThan(5).transact(xa)
      _ <-
        sql"UPDATE outbox_event SET status='published', published_at=now() WHERE event_id=$id".update.run.transact(xa)
      clear <- CompletenessRepo.unpublishedOlderThan(5).transact(xa)
    } yield expect(stuck >= 1L) and expect(clear == 0L)
  }
}
