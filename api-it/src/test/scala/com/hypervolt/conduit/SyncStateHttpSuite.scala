package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AuditRoutes
import doobie.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import java.util.UUID
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.headers.Authorization
import weaver.IOSuite

// M-Ingest slice 7 (spec doc 33 §7): the sync-health board endpoint feeding the desk. view:sync_state gated;
// returns per-source cursor + lag + last status for the shadow dual-run owners.
object SyncStateHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def principal(xa: HikariTransactor[IO], grant: Boolean): IO[String] = {
    val kc = s"sync-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Sync"))
      r   <- AdminRepo.createRole(s"syncrole-${UUID.randomUUID()}", Some("sync"))
      _ <-
        if (grant) AdminRepo.addPermission(r, "sync_state", "view", None, List("volume"), Nil, "all").void
        else ().pure[doobie.ConnectionIO]
      _ <- AdminRepo.assign(uid, r, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  private def get(routes: org.http4s.HttpRoutes[IO], kc: String): IO[(Int, Json)] =
    routes.orNotFound
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/finance/sync-state"))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
      )
      .flatMap(r => r.bodyText.compile.string.map(b => (r.status.code, parseJson(b).getOrElse(Json.Null))))

  test("view:sync_state sees the board with per-source cursor + lag; without it, 403") { xa =>
    val routes = new AuditRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
    val src    = s"xero-${UUID.randomUUID().toString.take(6)}"
    for {
      _ <-
        sql"""INSERT INTO sync_state (source, dataset, cursor, last_run_at, last_status, records_seen, records_written)
              VALUES ($src, 'invoices', '2026-06-03T12:30:00Z', now() - interval '90 seconds', 'ok', 42, 40)""".update.run
          .transact(xa)
      yes            <- principal(xa, grant = true)
      no             <- principal(xa, grant = false)
      (okC, body)    <- get(routes, yes)
      (forbidden, _) <- get(routes, no)
    } yield {
      val rows = body.asArray.getOrElse(Vector.empty)
      val mine = rows.find(_.hcursor.get[String]("source").toOption.contains(src))
      val lag  = mine.flatMap(_.hcursor.get[Long]("lag_seconds").toOption).getOrElse(0L)
      expect(okC == 200) and expect(mine.isDefined) and
        expect(mine.flatMap(_.hcursor.get[String]("last_status").toOption).contains("ok")) and
        expect(mine.flatMap(_.hcursor.get[String]("cursor").toOption).contains("2026-06-03T12:30:00Z")) and
        expect(lag >= 60L) and // ~90s stale
        expect(forbidden == 403)
    }
  }
}
