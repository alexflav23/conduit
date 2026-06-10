package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.consumer.AthenaPlacementVersionedRecord
import com.hypervolt.conduit.consumer.PlacementConsumer
import com.hypervolt.conduit.forecast.BacktestEngine
import com.hypervolt.conduit.forecast.LiveForecastService
import com.hypervolt.conduit.forecast.RevenueProjectionService
import com.hypervolt.conduit.forecast.RunwayService
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import com.hypervolt.conduit.warranty.ActivationService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-Forecast slice 4 (doc 26 §6) — the LIVE engine: the backtest champion publishes into the H6Q spine
// (forecast_entry source='model', append-only supersession); the activation stream maintains the runway projection
// and fires forecast.account.runway at the reorder point (via the real PlacementConsumer handler — M8's wire);
// and revenue is a contract-aware projection (units × the customer's tier, net of the expected retrospective
// rebate per unit) — never a stored number.
object LiveForecastSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private def seedVariant(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation, product_class) VALUES ($fam,$sku,'g3','charger') RETURNING id"
          .query[UUID]
          .unique
    } yield (vid, sku)).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($n,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def orderAt(xa: HikariTransactor[IO], buyer: UUID, vid: UUID, month: LocalDate, qty: Int): IO[UUID] =
    (for {
      oid <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
              VALUES (${"O-" + UUID
          .randomUUID()}, 'trade', $buyer, $buyer, 'placed', 'GBP', 'invoice', 0, 0, 0) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, line_total_inc_vat)
                 VALUES ($oid, $vid, $qty, 600.00, 0, 0)""".update.run
      _ <- sql"""UPDATE "order" SET created_at = ${month.plusDays(14).atStartOfDay()} WHERE id = $oid""".update.run
    } yield oid).transact(xa)

  private def seedSteady(xa: HikariTransactor[IO], buyer: UUID, vid: UUID): IO[Unit] =
    Iterator
      .iterate(LocalDate.of(2024, 1, 1))(_.plusMonths(1))
      .takeWhile(!_.isAfter(LocalDate.of(2025, 6, 1)))
      .toList
      .traverse_(m => orderAt(xa, buyer, vid, m, 100))

  // shipped serials on a dispatch, some already activated — the runway substrate
  private def seedSerials(
      xa: HikariTransactor[IO],
      buyer: UUID,
      vid: UUID,
      shipped: Int,
      activated: Int
  ): IO[List[String]] =
    for {
      oid <- orderAt(xa, buyer, vid, LocalDate.of(2025, 5, 1), shipped)
      did <- sql"""INSERT INTO dispatch (dispatch_no, order_id, date, delivered_at)
              VALUES (${"D-" + UUID.randomUUID()}, $oid, now() - interval '60 days', now() - interval '55 days')
              RETURNING id""".query[UUID].unique.transact(xa)
      serials = List.tabulate(shipped)(i => s"0301${UUID.randomUUID().toString.replace("-", "").take(12)}$i")
      _ <- serials.zipWithIndex.traverse_ {
        case (s, i) =>
          val activatedAt =
            if (i < activated) Some(Instant.now().minusSeconds(86400L * (i.toLong % 90 + 1))) else None
          sql"""INSERT INTO serial_unit (serial_no, generation, product_variant_id, dispatch_id, company_id, activated_at, status)
                VALUES ($s, 'v3', $vid, $did, $buyer, $activatedAt, 'dispatched')""".update.run.void.transact(xa)
      }
    } yield serials.drop(activated) // the not-yet-activated ones

  test("the champion publishes into the H6Q spine; a re-publish supersedes, never deletes") { xa =>
    val engine = new BacktestEngine[IO](xa)
    val live   = new LiveForecastService[IO](xa)
    val origin = LocalDate.of(2025, 7, 1)
    for {
      (vid, _) <- seedVariant(xa)
      buyer    <- party(xa, "Steady Co")
      _        <- seedSteady(xa, buyer, vid)
      _        <- engine.runOrigin(LocalDate.of(2025, 3, 1), horizonMonths = 3)
      _        <- engine.scoreOrigin(LocalDate.of(2025, 3, 1), asOf = origin)
      n1       <- live.publish(origin, horizonMonths = 3)
      n2       <- live.publish(origin, horizonMonths = 3) // re-publish: new current rows, old superseded
      current <-
        sql"""SELECT count(*) FROM forecast_entry
              WHERE company_id = $buyer AND source = 'model' AND superseded_by IS NULL"""
          .query[Long]
          .unique
          .transact(xa)
      superseded <-
        sql"""SELECT count(*) FROM forecast_entry
              WHERE company_id = $buyer AND source = 'model' AND superseded_by IS NOT NULL"""
          .query[Long]
          .unique
          .transact(xa)
      qty <- sql"""SELECT qty FROM forecast_entry
              WHERE company_id = $buyer AND source = 'model' AND superseded_by IS NULL
              ORDER BY period_month LIMIT 1""".query[Int].unique.transact(xa)
    } yield expect(n1 > 0) and expect(n2 > 0) and
      expect(current == 3L) and expect(superseded == 3L) and // append-only chain
      expect(qty == 100)                                     // a steady 100/month account forecasts 100
  }

  test("the activation stream maintains the runway and fires forecast.account.runway at the reorder point") { xa =>
    val runway      = new RunwayService[IO](xa)
    val activations = new ActivationService[IO](xa)
    val consumer    = new PlacementConsumer[IO](null, xa, activations, runway) // null PulsarClient: handle() only
    for {
      (vid, _) <- seedVariant(xa)
      buyer    <- party(xa, "Runway Installer Ltd")
      // 100 shipped, 80 already activated over ~3 months → shelf 20, velocity ≈ 13.3/mo → runway ≈ 45d (no event)
      remaining <- seedSerials(xa, buyer, vid, shipped = 100, activated = 80)
      r1        <- runway.refresh(buyer, vid, Instant.now())
      eventsAt45 <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='forecast.account.runway' AND aggregate_id=$buyer"
          .query[Long]
          .unique
          .transact(xa)
      // 10 more activations arrive through the REAL consumer handler (M8's wire): shelf 20→10, runway halves
      _ <- remaining.take(10).traverse_ { s =>
        consumer.handle(AthenaPlacementVersionedRecord(Some(s), UUID.randomUUID().toString, 1))
      }
      state <-
        sql"""SELECT shelf_stock::int, runway_days FROM account_forecast_state
              WHERE company_id = $buyer AND product_variant_id = $vid"""
          .query[(Int, Option[BigDecimal])]
          .unique
          .transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='forecast.account.runway' AND aggregate_id=$buyer"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(r1.exists(_ > 30)) and expect(eventsAt45 == 0L) and // healthy runway: no signal
      expect(state._1 == 10) and                                       // shelf depleted through the live wire
      expect(state._2.exists(_ <= 30)) and expect(events >= 1L)        // the reorder signal fired
  }

  test("a measured near-JIT account earns its own reorder point — 25 days of runway is healthy, not a signal") { xa =>
    val runway = new RunwayService[IO](xa)
    for {
      (vid, _) <- seedVariant(xa)
      buyer    <- party(xa, "JIT Installer Ltd")
      // three orders each dispatched two days after creation: the measured median order→dispatch lag is 2d,
      // so the reorder point is max(2 + 7, floor) = 14 — not the global 30
      _ <- List(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)).traverse_ { m =>
        orderAt(xa, buyer, vid, m, 50).flatMap(oid =>
          sql"""INSERT INTO dispatch (dispatch_no, order_id, date)
                VALUES (${"D-" + UUID.randomUUID()}, $oid, ${m.plusDays(16).atStartOfDay()})""".update.run.void
            .transact(xa)
        )
      }
      // shelf 12, velocity ≈ 14.7/mo → runway ≈ 25d: under the old global 30 this fired; for THIS account it's healthy
      _  <- seedSerials(xa, buyer, vid, shipped = 100, activated = 88)
      r1 <- runway.refresh(buyer, vid, Instant.now())
      point <-
        sql"""SELECT reorder_point_days FROM account_forecast_state
              WHERE company_id = $buyer AND product_variant_id = $vid"""
          .query[BigDecimal]
          .unique
          .transact(xa)
      events <-
        sql"SELECT count(*) FROM outbox_event WHERE event_type='forecast.account.runway' AND aggregate_id=$buyer"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(r1.exists(d => d > 14 && d <= 30)) and // the zone where global-vs-measured behaviour differs
      expect(point.toInt == 14) and                       // the measured, clamped per-account point
      expect(events == 0L)                                // near-JIT: no false alarm
  }

  test("revenue is a contract-aware projection: units × the customer's tier, net of the expected rebate") { xa =>
    val live    = new LiveForecastService[IO](xa)
    val revenue = new RevenueProjectionService[IO](xa)
    val agr     = new AgreementService[IO](xa)
    for {
      (vid, _) <- seedVariant(xa)
      buyer    <- party(xa, "Octopus Forecast")
      _        <- seedSteady(xa, buyer, vid)
      // a retrospective agreement: entry @600, commitment 500 → tier @520 → expected rebate 80/unit
      agreement <- agr.request(
        TierRequest(
          "Octopus forecast agreement",
          "GBP",
          List(buyer),
          List(
            TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
            TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
            TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
          ),
          Instant.now().minusSeconds(3600),
          None,
          "cumulative_retrospective",
          Json.obj("min_commitment_units" -> Json.fromInt(500)),
          None,
          UUID.randomUUID()
        )
      )
      _    <- agr.activate(agreement, UUID.randomUUID())
      _    <- live.publish(LocalDate.of(2025, 7, 1), horizonMonths = 3)
      json <- revenue.project(buyer, channel, market, "GBP", Instant.now())
      lines = json.hcursor.downField("lines").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      first = lines.headOption.map(_.hcursor)
    } yield expect(lines.size == 3) and
      expect(
        first.flatMap(_.get[String]("unit_price").toOption).exists(BigDecimal(_) == 600)
      ) and // the FIRM entry tier
      expect(first.flatMap(_.get[String]("expected_rebate_pu").toOption).exists(BigDecimal(_) == 80)) and
      // 100 units × (600 − 80) = 52000 — contract-consistent revenue, never list × units
      expect(first.flatMap(_.get[String]("forecast_revenue").toOption).exists(BigDecimal(_) == 52000))
  }
}
