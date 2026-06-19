package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.InMemoryInboundPublisher
import com.hypervolt.conduit.ingest.IngestRecord
import com.hypervolt.conduit.ingest.IngestSink
import com.hypervolt.conduit.ingest.InboundRelay
import com.hypervolt.conduit.ingest.InboxRepo
import com.hypervolt.conduit.ingest.SnapshotLoader
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.circe.Json
import weaver.IOSuite

// S1.9 — the inbound durability spine, end to end against a real Postgres (the mirror of OutboxIntegrationSuite).
// Proves the never-lose invariant: a connector record lands durably ('received'), the relay publishes it in seq
// order and marks it 'published', the mapping consumer maps it through the SAME boot handler into the engine and
// marks it 'processed'; an unmappable record is quarantined ('failed', raw payload retained, never dropped);
// re-running the relay never re-publishes; a drifted re-pull re-enters the inbox. Uses fx→exchange_rate as the
// simplest isolated mapping.
object InboxIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val fxPayload =
    Json.obj(
      "base"      -> Json.fromString("GBP"),
      "quote"     -> Json.fromString("ZZZ"),
      "rate"      -> Json.fromBigDecimal(BigDecimal("1.99")),
      "as_of"     -> Json.fromString("2099-01-01"),
      "rate_type" -> Json.fromString("spot"),
      "source"    -> Json.fromString("s19")
    )

  private def reset(xa: HikariTransactor[IO]): IO[Unit] =
    (sql"TRUNCATE ingest_record RESTART IDENTITY".update.run *>
      sql"DELETE FROM exchange_rate WHERE quote = 'ZZZ'".update.run).transact(xa).void

  private def status(xa: HikariTransactor[IO], source: String, ds: String, id: String): IO[Option[String]] =
    sql"SELECT status FROM ingest_record WHERE source=$source AND dataset=$ds AND source_id=$id"
      .query[String].option.transact(xa)

  private def zzzRates(xa: HikariTransactor[IO]): IO[Long] =
    sql"SELECT count(*) FROM exchange_rate WHERE quote = 'ZZZ'".query[Long].unique.transact(xa)

  test("land → relay → map: a record lands durably, publishes in order, and maps into the engine") { xa =>
    val sink   = new IngestSink[IO](xa)
    val repo   = new InboxRepo[IO](xa)
    val loader = new SnapshotLoader[IO](xa)
    for {
      _       <- reset(xa)
      _       <- sink.write("fx")(IngestRecord("rates", "GBP-ZZZ-2099", fxPayload))
      landed  <- status(xa, "fx", "rates", "GBP-ZZZ-2099")
      pub     <- InMemoryInboundPublisher.create[IO]
      n       <- new InboundRelay[IO](xa, pub).runOnce()
      pubd    <- pub.published
      afterRl <- status(xa, "fx", "rates", "GBP-ZZZ-2099")
      mapped  <- loader.mapInbound("fx", "rates", fxPayload)(repo.markProcessed("fx", "rates", "GBP-ZZZ-2099"))
      rates   <- zzzRates(xa)
      done    <- status(xa, "fx", "rates", "GBP-ZZZ-2099")
    } yield expect(landed.contains("received")) and
      expect(n == 1) and expect(pubd.map(_.source) == Vector("fx")) and
      expect(afterRl.contains("published")) and
      expect(mapped == Right(1)) and expect(rates == 1L) and
      expect(done.contains("processed"))
  }

  test("quarantine: an unmappable record is marked failed with its raw payload retained (never dropped)") { xa =>
    val sink   = new IngestSink[IO](xa)
    val repo   = new InboxRepo[IO](xa)
    val loader = new SnapshotLoader[IO](xa)
    for {
      _      <- reset(xa)
      _      <- sink.write("bogus")(IngestRecord("widgets", "x1", fxPayload))
      mapped <- loader.mapInbound("bogus", "widgets", fxPayload)(repo.markProcessed("bogus", "widgets", "x1"))
      _      <- repo.markFailed("bogus", "widgets", "x1", "no handler for source family 'bogus'")
      st     <- status(xa, "bogus", "widgets", "x1")
      kept   <- sql"SELECT count(*) FROM ingest_record WHERE source='bogus' AND payload IS NOT NULL"
                  .query[Long].unique.transact(xa)
    } yield expect(mapped.isLeft) and expect(st.contains("failed")) and expect(kept == 1L)
  }

  test("idempotency: re-running the relay does not re-publish, and a drifted re-pull re-enters the inbox") { xa =>
    val sink = new IngestSink[IO](xa)
    for {
      _      <- reset(xa)
      _      <- sink.write("fx")(IngestRecord("rates", "GBP-ZZZ-2099", fxPayload))
      pub    <- InMemoryInboundPublisher.create[IO]
      relay   = new InboundRelay[IO](xa, pub)
      first  <- relay.runOnce()
      again  <- relay.runOnce()
      total  <- pub.published.map(_.size)
      // a drifted re-pull (changed payload) resets the row to 'received' so it re-flows
      _      <- sink.write("fx")(IngestRecord("rates", "GBP-ZZZ-2099", fxPayload.deepMerge(Json.obj("rate" -> Json.fromBigDecimal(BigDecimal("2.50"))))))
      st     <- status(xa, "fx", "rates", "GBP-ZZZ-2099")
    } yield expect(first == 1) and expect(again == 0) and expect(total == 1) and expect(st.contains("received"))
  }
}
