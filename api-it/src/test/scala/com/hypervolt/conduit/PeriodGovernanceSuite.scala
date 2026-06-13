package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.close.PeriodCloseService
import com.hypervolt.conduit.close.PeriodInvestigationService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M-Period (spec doc 32): the group roll-up gate forces a common group close (ASC 810 coterminous) — the group
// period for a key cannot lock until EVERY operating entity's period for that key is locked — and the
// investigation view re-projects everything that happened in a period onto its window.
object PeriodGovernanceSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def operatingEntity(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ($name,'GB','GBP','operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  test("the group period will not lock while an operating entity is still open, and locks once all are locked") { xa =>
    val close = new PeriodCloseService[IO](xa)
    val key   = s"GRP-${UUID.randomUUID().toString.take(8)}"
    val actor = UUID.randomUUID()
    for {
      _ <-
        sql"INSERT INTO reporting_calendar (period_key, period_from, period_to) VALUES ($key, '2026-04-01', '2026-06-30')".update.run
          .transact(xa)
      e1 <- operatingEntity(xa, s"HV UK $key")
      e2 <- operatingEntity(xa, s"HV SG $key")
      p1 <- close.ensurePeriod(e1, "month", key, "Europe/London")
      p2 <- close.ensurePeriod(e2, "month", key, "Europe/London")
      // both open → group lock refused, naming both laggards
      blocked0 <- close.closeGroup(key, actor)
      _        <- close.close(p1, UUID.randomUUID())
      _        <- close.lock(p1, UUID.randomUUID())
      // one locked, one still open → still refused, naming the remaining laggard
      blocked1 <- close.closeGroup(key, actor)
      _        <- close.close(p2, UUID.randomUUID())
      _        <- close.lock(p2, UUID.randomUUID())
      // all locked → group locks
      ok     <- close.closeGroup(key, actor)
      status <- sql"SELECT status FROM reporting_calendar WHERE period_key=$key".query[String].unique.transact(xa)
    } yield expect(blocked0.isLeft) and
      expect(blocked0.left.toOption.exists(m => m.contains("HV UK") && m.contains("HV SG"))) and
      expect(blocked1.isLeft) and expect(
      blocked1.left.toOption.exists(m => m.contains("HV SG") && !m.contains("HV UK"))
    ) and
      expect(ok.isRight) and expect(status == "locked")
  }

  test("a second group lock is a no-op once the period is already locked") { xa =>
    val close = new PeriodCloseService[IO](xa)
    val key   = s"GRP2-${UUID.randomUUID().toString.take(8)}"
    for {
      _ <-
        sql"INSERT INTO reporting_calendar (period_key, period_from, period_to) VALUES ($key, '2026-01-01', '2026-03-31')".update.run
          .transact(xa)
      // backdated to before the period end so it genuinely participates in this past group period
      e1 <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, created_at) VALUES (${s"Solo $key"},'GB','GBP','operating','2026-01-01') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      p1     <- close.ensurePeriod(e1, "month", key, "Europe/London")
      _      <- close.close(p1, UUID.randomUUID())
      _      <- close.lock(p1, UUID.randomUUID())
      first  <- close.closeGroup(key, UUID.randomUUID())
      second <- close.closeGroup(key, UUID.randomUUID())
    } yield expect(first.isRight) and expect(second.isLeft) and
      expect(second.left.toOption.exists(_.contains("no open group period")))
  }

  test("the investigation view assembles the period window, status and every section") { xa =>
    val close    = new PeriodCloseService[IO](xa)
    val investig = new PeriodInvestigationService[IO](xa)
    val key      = s"INV-${UUID.randomUUID().toString.take(8)}"
    for {
      _ <-
        sql"INSERT INTO reporting_calendar (period_key, period_from, period_to) VALUES ($key, '2026-07-01', '2026-09-30')".update.run
          .transact(xa)
      e1 <- operatingEntity(xa, s"HV INV $key")
      _  <- close.ensurePeriod(e1, "month", key, "Europe/London")
      // a balanced posting and a business event inside the window (sharing one event_id)
      ev = UUID.randomUUID()
      _ <-
        sql"""INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, entity_id, currency, amount_minor, posted, transfer_code, event_id, occurred_at)
                   VALUES (${UUID
          .randomUUID()
          .getMostSignificantBits
          .abs}, 'debit', ${s"AR:$key"}, 1, $e1, 'GBP', 120000, true, 1, $ev, '2026-08-15T10:00:00Z'),
                          (${UUID
          .randomUUID()
          .getMostSignificantBits
          .abs}, 'credit', ${s"REVENUE:$key"}, 4, $e1, 'GBP', 120000, true, 1, $ev, '2026-08-15T10:00:00Z')""".update.run
          .transact(xa)
      _ <-
        sql"""INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, payload, occurred_at)
                   VALUES ($ev, 'OrderPlaced', 1, 'order', ${UUID
          .randomUUID()}, 'k', '{}'::jsonb, '2026-08-15T10:00:00Z')""".update.run.transact(xa)
      found   <- investig.investigate(key, None)
      unknown <- investig.investigate(s"missing-${UUID.randomUUID()}", None)
    } yield {
      val c        = found.map(_.hcursor)
      val from     = c.flatMap(_.downField("from").as[String].toOption)
      val legCount = c.flatMap(_.downField("journals").downField("leg_count").as[Int].toOption).getOrElse(0)
      val events   = c.flatMap(_.downField("events").as[List[io.circe.Json]].toOption).getOrElse(Nil)
      val periods  = c.flatMap(_.downField("entity_periods").as[List[io.circe.Json]].toOption).getOrElse(Nil)
      expect(found.isDefined) and expect(from.contains("2026-07-01")) and
        expect(legCount == 2) and expect(events.nonEmpty) and expect(periods.size == 1) and
        expect(unknown.isEmpty)
    }
  }
}
