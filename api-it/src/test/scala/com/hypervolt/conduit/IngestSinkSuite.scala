package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.ingest._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.util.UUID
import weaver.IOSuite

// M-Ingest (spec doc 33 §2): the IngestSink lands each pulled record in ingest_record, idempotent on
// (source, dataset, source_id). A re-pull of the same payload is a no-op; a re-pull whose payload changed
// flips `drifted` (the spec/18 §4.3 post-ingest edit). This is the runner's write handler + the reconciler's
// source-of-truth side.
object IngestSinkSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def row(xa: HikariTransactor[IO], source: String, id: String) =
    sql"SELECT payload->>'v', source_hash, drifted FROM ingest_record WHERE source=$source AND dataset='ds' AND source_id=$id"
      .query[(Option[String], String, Boolean)]
      .unique
      .transact(xa)

  private def rec(id: String, v: String) = IngestRecord("ds", id, Json.obj("v" -> Json.fromString(v)))

  test("landing a record is idempotent; an unchanged re-pull does not drift, a changed payload does") { xa =>
    val sink = new IngestSink[IO](xa)
    val src  = s"src-${UUID.randomUUID()}"
    val id   = "rec-1"
    for {
      a      <- sink.write(src)(rec(id, "one"))
      afterA <- row(xa, src, id)
      b      <- sink.write(src)(rec(id, "one")) // identical re-pull → no-op, no drift
      afterB <- row(xa, src, id)
      count1 <- sql"SELECT count(*) FROM ingest_record WHERE source=$src".query[Long].unique.transact(xa)
      c      <- sink.write(src)(rec(id, "two")) // payload changed at source → drift
      afterC <- row(xa, src, id)
      count2 <- sql"SELECT count(*) FROM ingest_record WHERE source=$src".query[Long].unique.transact(xa)
    } yield expect(a.isRight && b.isRight && c.isRight) and
      expect(afterA._1.contains("one") && !afterA._3) and // landed, not drifted
      expect(!afterB._3 && afterB._2 == afterA._2) and    // identical re-pull: still not drifted, same hash
      expect(count1 == 1L) and                            // exactly one row (idempotent)
      expect(afterC._1.contains("two") && afterC._3) and  // changed payload: refreshed + drifted
      expect(count2 == 1L)                                // still one row
  }
}
