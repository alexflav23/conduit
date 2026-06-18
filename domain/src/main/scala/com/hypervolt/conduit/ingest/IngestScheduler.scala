package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import scala.concurrent.duration.FiniteDuration

// The live ingest driver (S2.0, spec 36): turns the snapshot-era connectors into a continuous sync. For each
// configured (connector, dataset) it drains the source via the existing IngestRunner — which lands every record
// in the durable inbox (IngestSink → ingest_record) and advances the sync cursor ONLY after a batch commits
// (at-least-once; the S1 relay + mapping consumer take it from there). One run errors isolate per dataset; the
// loop survives. Source-agnostic — a connector + its dataset list + a cadence is all it needs.
final class IngestScheduler[F[_]: Async](runner: IngestRunner[F], sink: IngestSink[F])(implicit log: Logger[F]) {

  def runOnce(connector: IngestConnector[F], datasets: List[String]): F[Unit] =
    datasets.traverse_(ds =>
      runner
        .drain(connector, ds)(sink.write(connector.source))
        .flatMap { runs =>
          val landed = runs.map(_.written).sum
          val err    = runs.flatMap(_.error).headOption
          err.fold(log.info(s"ingest ${connector.source}/$ds: $landed landed across ${runs.size} page(s)"))(e =>
            log.warn(s"ingest ${connector.source}/$ds: stopped after $landed landed — $e")
          )
        }
        .handleErrorWith(e => log.error(e)(s"ingest ${connector.source}/$ds crashed: ${e.getMessage}"))
    )

  // The supervised loop: drain the datasets, then wait. A cold cursor backfills; a warm cursor pulls the delta.
  def loop(connector: IngestConnector[F], datasets: List[String], every: FiniteDuration): F[Unit] =
    (runOnce(connector, datasets) *> Async[F].sleep(every)).foreverM
}
