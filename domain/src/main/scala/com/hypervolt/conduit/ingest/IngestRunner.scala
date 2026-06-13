package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._

// Drives one (source, dataset) sync cycle (spec doc 33 §3): load the cursor, pull the delta, run each record
// through the shared write handler, and advance the cursor ONLY if every write committed — a single record-level
// failure leaves the cursor where it was so the next run re-pulls (at-least-once; handlers dedupe on sourceId).
// Pure of any specific source — the connector supplies the records, the handler supplies the landing.
final case class RunResult(
    source: String,
    dataset: String,
    seen: Int,
    written: Int,
    advanced: Boolean,
    error: Option[String]
)

final class IngestRunner[F[_]: Async](state: SyncStateRepo[F]) {

  def runDataset(connector: IngestConnector[F], dataset: String)(
      write: IngestRecord => F[Either[String, Unit]]
  ): F[RunResult] =
    state
      .cursor(connector.source, dataset)
      .flatMap(cur => connector.pullSince(dataset, cur).attempt)
      .flatMap {
        case Left(t) =>
          state
            .recordFailure(connector.source, dataset, s"pull failed: ${t.getMessage}")
            .as(RunResult(connector.source, dataset, 0, 0, advanced = false, Some(t.getMessage)))
        case Right(batch) =>
          batch.records
            .traverse(r => write(r).map((r, _)))
            .flatMap { written =>
              val firstError = written.collectFirst { case (r, Left(e)) => s"${r.sourceId}: $e" }
              val okCount    = written.count(_._2.isRight)
              firstError match {
                case Some(e) =>
                  state
                    .recordFailure(connector.source, dataset, s"write failed: $e")
                    .as(RunResult(connector.source, dataset, batch.records.size, okCount, advanced = false, Some(e)))
                case None =>
                  state
                    .recordSuccess(
                      connector.source,
                      dataset,
                      batch.nextCursor,
                      batch.records.size.toLong,
                      okCount.toLong
                    )
                    .as(
                      RunResult(
                        connector.source,
                        dataset,
                        batch.records.size,
                        okCount,
                        advanced = batch.nextCursor.isDefined,
                        None
                      )
                    )
              }
            }
      }

  // Drain a paginated source: keep pulling while the connector reports more pages and progress is being made.
  def drain(connector: IngestConnector[F], dataset: String, maxPages: Int = 50)(
      write: IngestRecord => F[Either[String, Unit]]
  ): F[List[RunResult]] = {
    def loop(page: Int, acc: List[RunResult]): F[List[RunResult]] =
      if (page >= maxPages) (acc.reverse).pure[F]
      else
        runDataset(connector, dataset)(write).flatMap { r =>
          // stop when a run errored, advanced nothing, or saw nothing — i.e. no further progress to make.
          if (r.error.isDefined || !r.advanced || r.seen == 0) (r :: acc).reverse.pure[F]
          else loop(page + 1, r :: acc)
        }
    loop(0, Nil)
  }
}
