package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TigerBeetleClient
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// A3 ignition: recognise revenue + COGS for the historical delivered book through the PRODUCTION recognition path
// (RevenueRecognitionService — ASC-606, posts AR/Revenue/VAT/COGS to TigerBeetle, idempotent on dispatch_id).
//   sbt "scripting/runMain com.hypervolt.conduit.scripting.RecognizeBackfill 5"     # sample
//   sbt "scripting/runMain com.hypervolt.conduit.scripting.RecognizeBackfill all"   # full book
// Sample-first: run a few, check Σdr==Σcr in gl_entry, then scale. Re-running is a no-op (idempotency guard).
//
// NOTE: this posts to TigerBeetle directly, so it must run where TB is reachable (DEMO_TB_ADDRESSES = the
// in-network address, e.g. inside the compose network) — TB rejects host port-forwarded connections. The
// event-driven alternative (used to ignite the local book) needs no host→TB: emit `dispatch.created` outbox
// events for the imported dispatches and the running consumer recognises them through the production pipeline
// (relay → conduit.orders → RevenueRecognitionConsumer → TB). The MRP ingest should emit that event per dispatch
// so a fresh boot self-ignites; until then this backfill / the outbox emission closes the gap.
object RecognizeBackfill extends IOApp {

  private def env(key: String, default: String) = sys.env.getOrElse(key, default)

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    env("DEMO_PG_URL", "jdbc:postgresql://localhost:5532/conduit"),
    env("DEMO_PG_USER", "conduit"),
    env("DEMO_PG_PASSWORD", "conduit"),
    None
  )

  // Eligible: dispatches with at least one dispatch_line (so revenue/qty resolve), not yet recognised, oldest first.
  private def eligible(limit: Option[Int]): IO[List[UUID]] = {
    val lim = limit.fold("")(n => s"LIMIT $n")
    (fr"""SELECT DISTINCT dl.dispatch_id
          FROM dispatch_line dl JOIN dispatch d ON d.id = dl.dispatch_id
          WHERE NOT EXISTS (SELECT 1 FROM revenue_recognition r WHERE r.dispatch_id = dl.dispatch_id)
          ORDER BY dl.dispatch_id """ ++ doobie.Fragment.const(lim))
      .query[UUID]
      .to[List]
      .transact(xa)
  }

  def run(args: List[String]): IO[cats.effect.ExitCode] = {
    val limit = args.headOption match {
      case Some("all") => None
      case Some(n)     => n.toIntOption.orElse(Some(5))
      case None        => Some(5)
    }
    TigerBeetleClient.make[IO](0, env("DEMO_TB_ADDRESSES", "localhost:3033")).use { client =>
      val service = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      eligible(limit).flatMap { ids =>
        IO.println(s"recognising ${ids.size} dispatch(es)...") *>
          ids
            .traverse(id =>
              service.recognize(id).attempt.map {
                case Right(Right(())) => ("ok", "")
                case Right(Left(msg)) => ("skip", msg)
                case Left(e)          => ("error", Option(e.getMessage).getOrElse(e.toString))
              }
            )
            .flatMap { results =>
              val ok    = results.count(_._1 == "ok")
              val skip  = results.count(_._1 == "skip")
              val err   = results.count(_._1 == "error")
              val notes = results.filter(_._1 != "ok").map(_._2).filter(_.nonEmpty).distinct.take(8)
              IO.println(s"recognised=$ok skipped=$skip errored=$err") *>
                notes.traverse_(n => IO.println(s"  · $n"))
            }
      }
    }.as(cats.effect.ExitCode.Success)
  }
}
