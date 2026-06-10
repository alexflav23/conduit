package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.purchasing.CommitmentService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M9c — the rolling commitment ladder: generated from the live forecast, zoned firm/flex/indicative, and
// issued ON SIGNAL — a stable forecast re-issues nothing (the 10-week ceremony dies), a moved forecast
// re-issues immediately with the deviation on the record, and every version is immutable.
object CommitmentSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val asOf = LocalDate.of(2026, 6, 1)

  private def seedForecast(xa: HikariTransactor[IO], variant: UUID, monthly: Int): IO[Unit] =
    (for {
      scenario <- sql"SELECT id FROM forecast_scenario WHERE is_default = true LIMIT 1".query[UUID].unique
      company <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES (${s"CM-${UUID.randomUUID()}"},'wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      _ <- (0 until 9).toList.traverse_ { i =>
        sql"""INSERT INTO forecast_entry (company_id, product_variant_id, period_month, scenario_id, qty, source, model_version)
              VALUES ($company, $variant, ${asOf.plusMonths(
          i.toLong
        )}, $scenario, $monthly, 'model', 'test:v1')""".update.run
      }
    } yield ()).transact(xa)

  test("calendar issue, signal suppression on a stable forecast, deviation re-issue on a moved one") { xa =>
    val svc = new CommitmentService[IO](xa)
    for {
      setup <- (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id"
              .query[UUID]
              .unique
          v <- sql"""INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"CM-${UUID
            .randomUUID()}"}, 'v3') RETURNING id""".query[UUID].unique
          sup <-
            sql"INSERT INTO supplier (name, billing_currency) VALUES ('Luxshare', 'USD') RETURNING id"
              .query[UUID]
              .unique
        } yield (v, sup)).transact(xa)
      (v, sup) = setup
      _         <- seedForecast(xa, v, 1000)
      v1        <- svc.generate(sup, asOf, force = true) // the contractual calendar issue
      zones     <- sql"""SELECT zone, count(*) FROM cm_commitment_line l JOIN cm_commitment c ON c.id = l.commitment_id
            WHERE c.supplier_id = $sup GROUP BY zone""".query[(String, Long)].to[List].map(_.toMap).transact(xa)
      unchanged <- svc.generate(sup, asOf)               // stable forecast: the signal stays quiet — NO version 2
      // the forecast moves +50% on every month — the next nightly check must re-issue
      _  <- sql"UPDATE forecast_entry SET qty = 1500 WHERE product_variant_id = $v".update.run.transact(xa)
      v2 <- svc.generate(sup, asOf)
      versions <-
        sql"SELECT version, reason FROM cm_commitment WHERE supplier_id = $sup ORDER BY version"
          .query[(Int, String)]
          .to[List]
          .transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type = 'purchasing.commitment.issued' AND aggregate_id = $sup"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(v1.isDefined) and
      // 10 firm weeks ≈ 2 month-buckets firm; +10 flex weeks ≈ months 3-4; the rest indicative
      expect(zones.getOrElse("firm", 0L) >= 2L) and expect(zones.getOrElse("indicative", 0L) >= 4L) and
      expect(unchanged.isEmpty) and // the ceremony is dead: stable forecast, no re-issue
      expect(v2.isDefined) and
      expect(versions.map(_._2) == List("calendar", "forecast_deviation")) and
      expect(events == 2L)
  }
}
