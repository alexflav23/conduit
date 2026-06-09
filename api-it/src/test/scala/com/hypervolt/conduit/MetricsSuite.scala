package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Dispatcher
import com.hypervolt.conduit.metrics.ConduitMetrics
import com.hypervolt.conduit.metrics.GlobalMetrics
import com.hypervolt.conduit.metrics.MetricsBuilder
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import weaver.IOSuite

// M-NFR.3 (doc 19 §C.1) — Conduit is a metrics citizen of the Hypervolt estate: the OpenTelemetry SDK + Prometheus
// exporter (Athena's GlobalMetrics pattern) serves the operational gauges the alarms defend (§C.3) on :PORT/metrics,
// reading live DB values. Proves the whole vertical end-to-end (exporter + observable gauges + dispatcher + repos).
object MetricsSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val port = 19464

  private def scrape: IO[String] =
    IO.blocking {
      val client = HttpClient.newHttpClient()
      val req    = HttpRequest.newBuilder(URI.create(s"http://localhost:$port/metrics")).GET().build()
      client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

  private def gaugeValue(body: String, metric: String): Double =
    body.linesIterator
      .filter(l => l.startsWith(metric) && !l.startsWith("#"))
      .flatMap(_.trim.split("\\s+").lastOption)
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
      .maxOption
      .getOrElse(-1.0)

  test("the Prometheus exporter serves the Conduit operational gauges with live DB values") { xa =>
    for {
      // seed one of each watched condition: an open reconciliation exception, a stuck-unpublished event, a DLQ entry
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('Mx','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      pid <-
        sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
              VALUES ($e, 'month', ${"mx-" + UUID
          .randomUUID()
          .toString
          .take(4)}, 'Europe/London', 'open') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"INSERT INTO reconciliation (type, period_id, expected, actual, currency, variance, status) VALUES ('gl_vs_tb', $pid, 1, 2, 'GBP', 1, 'exception')".update.run
          .transact(xa)
      stuck = UUID.randomUUID()
      _ <- sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id,
                partition_key, payload, occurred_at, status, created_at)
              VALUES ($stuck, 'm.created', 1, 'metrics', ${UUID.randomUUID()}, ${stuck.toString}, '{}'::jsonb, now(),
                'pending', now() - interval '10 minutes')""".update.run.transact(xa)
      dlqEvent = UUID.randomUUID()
      _ <-
        sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id,
                partition_key, payload, occurred_at, status)
              VALUES ($dlqEvent, 'm.created', 1, 'metrics', ${UUID
          .randomUUID()}, ${dlqEvent.toString}, '{}'::jsonb, now(), 'published')""".update.run
          .transact(xa)
      _ <-
        sql"INSERT INTO outbox_dlq (consumer_group, event_id, reason) VALUES ('metrics-grp', $dlqEvent, 'boom')".update.run
          .transact(xa)
      body <- Dispatcher.parallel[IO].use { d =>
        GlobalMetrics.buildResource[IO]("conduit", port).use { otel =>
          new ConduitMetrics[IO](xa, new MetricsBuilder(otel.getMeter("conduit"), "conduit"), d).register *> scrape
        }
      }
    } yield expect(body.contains("conduit_dlq_depth")) and
      expect(body.contains("conduit_outbox_unpublished_count")) and
      expect(body.contains("conduit_reconciliation_exception_count")) and
      expect(gaugeValue(body, "conduit_dlq_depth") >= 1.0) and
      expect(gaugeValue(body, "conduit_outbox_unpublished_count") >= 1.0) and
      expect(gaugeValue(body, "conduit_reconciliation_exception_count") >= 1.0)
  }
}
