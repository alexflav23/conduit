package com.hypervolt.conduit.metrics

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.hypervolt.conduit.event.CompletenessRepo
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.opentelemetry.api.metrics.ObservableLongGauge

// The Conduit-specific operational gauges the alarms defend (doc 19 §C.1/§C.3): DLQ depth, stuck-unpublished outbox
// events, and open (unsigned) reconciliation exceptions. Registered as OpenTelemetry observable gauges — the
// callback runs on each Prometheus scrape and reads the LIVE value from Postgres via the dispatcher (the SLOs in
// §C.2 are these series). Keep the returned handles referenced for the app lifetime.
final class ConduitMetrics[F[_]: Async](xa: Transactor[F], mb: MetricsBuilder, dispatcher: Dispatcher[F]) {

  def register: F[List[ObservableLongGauge]] =
    Async[F].delay(
      List(
        mb.gauge("dlq_depth")(m => m.record(run(CompletenessRepo.dlqDepth))),
        mb.gauge("outbox_unpublished_count")(m => m.record(run(CompletenessRepo.unpublishedOlderThan(5)))),
        mb.gauge("reconciliation_exception_count")(m => m.record(run(openReconExceptions)))
      )
    )

  private def run(q: ConnectionIO[Long]): Long = dispatcher.unsafeRunSync(q.transact(xa))

  private val openReconExceptions: ConnectionIO[Long] =
    sql"SELECT count(*) FROM reconciliation WHERE status = 'exception' AND signed_off_by IS NULL".query[Long].unique
}
