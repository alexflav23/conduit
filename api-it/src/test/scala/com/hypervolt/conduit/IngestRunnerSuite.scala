package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import com.hypervolt.conduit.ingest._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import weaver.IOSuite

// M-Ingest slice 1 (spec doc 33 §3): the IngestRunner cursor mechanics — advance only on full success, re-pull
// (not skip) on failure, idempotent steady state, and paginated drain. A StaticConnector test double supplies
// cursor→batch pages; the write handler is a Ref counter (handler-level dedupe is per-connector, later slices).
object IngestRunnerSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def rec(id: String): IngestRecord = IngestRecord("ds", id, Json.obj())

  // pages: cursor-value (None = cold) → the batch returned for that cursor.
  private final class StaticConnector(src: String, pages: Map[Option[String], IngestBatch])
      extends IngestConnector[IO] {
    def source: String         = src
    def datasets: List[String] = List("ds")
    def pullSince(dataset: String, cursor: Option[SyncCursor]): IO[IngestBatch] =
      IO.pure(pages.getOrElse(cursor.map(_.value), IngestBatch.empty))
  }

  private def counter: IO[(Ref[IO, Int], IngestRecord => IO[Either[String, Unit]])] =
    Ref.of[IO, Int](0).map(r => (r, (_: IngestRecord) => r.update(_ + 1).as(Right(()))))

  test("cursor advances on success, re-pull from the advanced cursor is an idempotent no-op") { xa =>
    val src    = s"test-${java.util.UUID.randomUUID()}"
    val runner = new IngestRunner[IO](new SyncStateRepo[IO](xa))
    val conn = new StaticConnector(
      src,
      Map(
        None       -> IngestBatch(List(rec("a"), rec("b"), rec("c")), Some(SyncCursor("c1")), complete = true),
        Some("c1") -> IngestBatch.empty
      )
    )
    for {
      cw <- counter
      (count, write) = cw
      r1 <- runner.runDataset(conn, "ds")(write)
      cur1 <-
        sql"SELECT cursor FROM sync_state WHERE source=$src AND dataset='ds'".query[Option[String]].unique.transact(xa)
      r2 <- runner.runDataset(conn, "ds")(write) // cursor now c1 → empty batch
      cur2 <-
        sql"SELECT cursor FROM sync_state WHERE source=$src AND dataset='ds'".query[Option[String]].unique.transact(xa)
      writ <-
        sql"SELECT records_written FROM sync_state WHERE source=$src AND dataset='ds'".query[Long].unique.transact(xa)
      n <- count.get
    } yield expect(r1.written == 3 && r1.advanced) and expect(cur1.contains("c1")) and
      expect(r2.seen == 0 && !r2.advanced) and expect(cur2.contains("c1")) and // cursor held, not reset
      expect(writ == 3L) and expect(n == 3)                                    // no double-write on re-pull
  }

  test("a record-level write failure does NOT advance the cursor (the next run re-pulls)") { xa =>
    val src    = s"test-${java.util.UUID.randomUUID()}"
    val runner = new IngestRunner[IO](new SyncStateRepo[IO](xa))
    val conn = new StaticConnector(
      src,
      Map(None -> IngestBatch(List(rec("a"), rec("b")), Some(SyncCursor("c9")), complete = true))
    )
    val write: IngestRecord => IO[Either[String, Unit]] =
      r => IO.pure(if (r.sourceId == "b") Left("boom") else Right(()))
    for {
      r <- runner.runDataset(conn, "ds")(write)
      row <-
        sql"SELECT cursor, last_status, consecutive_failures FROM sync_state WHERE source=$src AND dataset='ds'"
          .query[(Option[String], String, Int)]
          .unique
          .transact(xa)
    } yield expect(!r.advanced) and expect(r.error.exists(_.contains("boom"))) and
      expect(row._1.isEmpty) and expect(row._2 == "error") and expect(row._3 == 1)
  }

  test("drain follows pagination until the source is empty") { xa =>
    val src    = s"test-${java.util.UUID.randomUUID()}"
    val runner = new IngestRunner[IO](new SyncStateRepo[IO](xa))
    val conn = new StaticConnector(
      src,
      Map(
        None       -> IngestBatch(List(rec("p1")), Some(SyncCursor("c1")), complete = false),
        Some("c1") -> IngestBatch(List(rec("p2")), Some(SyncCursor("c2")), complete = false),
        Some("c2") -> IngestBatch.empty
      )
    )
    for {
      cw <- counter
      (count, write) = cw
      results <- runner.drain(conn, "ds")(write)
      cur <-
        sql"SELECT cursor FROM sync_state WHERE source=$src AND dataset='ds'".query[Option[String]].unique.transact(xa)
      n <- count.get
    } yield expect(results.count(_.advanced) == 2) and expect(cur.contains("c2")) and expect(n == 2)
  }
}
