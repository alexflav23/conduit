package com.hypervolt.conduit.scripting

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import com.hypervolt.conduit.db.Transactor
import com.hypervolt.conduit.event.CompletenessRepo
import com.hypervolt.conduit.event.PulsarEventPublisher
import com.hypervolt.conduit.event.ReplayService
import com.hypervolt.conduit.pulsar.PulsarUtils
import doobie.implicits._

// Operator entrypoint for the DLQ-replay / projection-rebuild runbooks (docs/runbooks/{dlq-replay,projection-
// rebuild}.md) — closes the "no admin surface for ReplayService" gap those runbooks flagged. Replay re-emits
// the events to their topics so the live (idempotent on event_id) consumers reprocess them — the same handler,
// no second write path. Reads DB/Pulsar from env (CONDUIT_JDBC_URL/_DB_USER/_DB_PASSWORD, PULSAR_SERVICE_URL).
//
//   sbt "scripting/runMain …ReplayCli dlq-depth"
//   sbt "scripting/runMain …ReplayCli replay-dlq <consumer-group>"
//   sbt "scripting/runMain …ReplayCli rebuild <consumer-group> [aggregate-type]"
object ReplayCli extends IOApp {

  private def env(k: String, default: String) = sys.env.getOrElse(k, default)

  private val resources: Resource[IO, (org.apache.pulsar.client.api.PulsarClient, doobie.hikari.HikariTransactor[IO])] =
    for {
      xa <- Transactor.build[IO](
        env("CONDUIT_JDBC_URL", "jdbc:postgresql://localhost:5432/conduit"),
        env("CONDUIT_DB_USER", "conduit"),
        env("CONDUIT_DB_PASSWORD", "conduit")
      )
      pulsar <- PulsarUtils.makeClient[IO](env("PULSAR_SERVICE_URL", "pulsar://localhost:6650"))
    } yield (pulsar, xa)

  override def run(args: List[String]): IO[ExitCode] =
    args match {
      case "dlq-depth" :: Nil =>
        resources
          .use { case (_, xa) => CompletenessRepo.dlqDepth.transact(xa).flatMap(d => IO.println(s"dlq_depth=$d")) }
          .as(ExitCode.Success)

      case "replay-dlq" :: group :: Nil =>
        resources
          .use {
            case (pulsar, xa) =>
              PulsarEventPublisher.create[IO](pulsar).flatMap { pub =>
                new ReplayService[IO](xa)
                  .replayDlq(group)(pub.publish)
                  .flatMap(n => IO.println(s"replayed $n DLQ event(s) for group $group"))
              }
          }
          .as(ExitCode.Success)

      case "rebuild" :: group :: rest =>
        resources
          .use {
            case (pulsar, xa) =>
              PulsarEventPublisher.create[IO](pulsar).flatMap { pub =>
                new ReplayService[IO](xa)
                  .rebuild(group, rest.headOption)(pub.publish)
                  .flatMap(n => IO.println(s"replayed $n event(s) to rebuild group $group"))
              }
          }
          .as(ExitCode.Success)

      case _ =>
        IO.println(
          "usage: ReplayCli (dlq-depth | replay-dlq <group> | rebuild <group> [aggregate-type])"
        ).as(ExitCode.Error)
    }
}
