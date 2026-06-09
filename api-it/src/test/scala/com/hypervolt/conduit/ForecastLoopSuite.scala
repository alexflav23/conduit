package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.forecast.BacktestEngine
import com.hypervolt.conduit.forecast.DemandSeriesRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-Forecast (doc 26 §5) — THE ACCEPTANCE TEST IS THE USER'S SENTENCE: "use data up to Q2'25 to predict Q3'25,
// compare with the actuals we have for Q3'25, and use that as a training loop". Two accounts with opposite demand
// shapes prove the learning is mechanical and personalised: the loop ranks every registry model by measured error
// and selects a DIFFERENT champion per account — exactly-seasonal demand selects seasonal_naive (zero error);
// cycle-shifted lumpy demand rejects it. Censoring, immutability and idempotency are asserted directly.
object ForecastLoopSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def seedVariant(xa: HikariTransactor[IO]): IO[UUID] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation, product_class) VALUES ($fam,$sku,'g3','charger') RETURNING id"
          .query[UUID]
          .unique
    } yield vid).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($n,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  // an order with a line, backdated to `month` — the demand history the loop fits on
  private def orderAt(xa: HikariTransactor[IO], buyer: UUID, vid: UUID, month: LocalDate, qty: Int): IO[Unit] =
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
    } yield ()).transact(xa)

  // SEASONAL account: an exactly-repeating yearly pattern, Jan'23 … Sep'25 (incl. the Q3'25 actuals)
  private def seedSeasonal(xa: HikariTransactor[IO], buyer: UUID, vid: UUID): IO[Unit] = {
    val months = Iterator
      .iterate(LocalDate.of(2023, 1, 1))(_.plusMonths(1))
      .takeWhile(!_.isAfter(LocalDate.of(2025, 9, 1)))
      .toList
    months.traverse_(m => orderAt(xa, buyer, vid, m, if (Set(6, 7, 8).contains(m.getMonthValue)) 250 else 100))
  }

  // LUMPY account: 300-unit spikes whose 3-month cycle SHIFTS each year — same-month-last-year is always wrong
  private def seedLumpy(xa: HikariTransactor[IO], buyer: UUID, vid: UUID): IO[Unit] = {
    val spikes =
      List(1, 4, 7, 10).map(LocalDate.of(2023, _, 1)) ++
        List(2, 5, 8, 11).map(LocalDate.of(2024, _, 1)) ++
        List(3, 6, 9).map(LocalDate.of(2025, _, 1))
    spikes.traverse_(m => orderAt(xa, buyer, vid, m, 300))
  }

  test(
    "train on ≤ Q2'25, predict Q3'25, score against Q3'25 actuals — the loop selects a different champion per shape"
  ) { xa =>
    val engine = new BacktestEngine[IO](xa)
    val origin = LocalDate.of(2025, 7, 1) // censoring cut: data strictly before July = "up to Q2'25"
    for {
      vid      <- seedVariant(xa)
      seasonal <- party(xa, "Seasonal Wholesale Co")
      lumpy    <- party(xa, "Lumpy Projects Ltd")
      _        <- seedSeasonal(xa, seasonal, vid)
      _        <- seedLumpy(xa, lumpy, vid)
      // censoring: the as-of history ends at June'25, regardless of the Q3 rows in the table
      hist <- DemandSeriesRepo.history(seasonal, vid, origin).transact(xa)
      // the loop: every registry model fits the censored world and predicts Q3'25 — immutable runs
      fitted <- engine.runOrigin(origin, horizonMonths = 3)
      rerun  <- engine.runOrigin(origin, horizonMonths = 3) // idempotent: the same (origin, model) is a no-op
      // Q3'25 closes: score the predictions against the actuals
      scored <- engine.scoreOrigin(origin, asOf = LocalDate.of(2025, 10, 1))
      sChamp <- engine.champion(seasonal)
      lChamp <- engine.champion(lumpy)
      runs   <- sql"SELECT count(*) FROM forecast_run WHERE origin_month = $origin".query[Long].unique.transact(xa)
      preds  <- sql"""SELECT count(*) FROM forecast_run_prediction p JOIN forecast_run r ON r.id = p.run_id
                WHERE r.origin_month = $origin""".query[Long].unique.transact(xa)
    } yield expect(hist.months.lastOption.contains(LocalDate.of(2025, 6, 1))) and // censored at the origin
      expect(hist.qty.takeRight(2).map(_.toInt) == Vector(100, 250)) and          // May'25=100, Jun'25=250 — Q3 rows invisible
      expect(fitted > 0) and expect(rerun == 0) and                               // immutable: re-run adds nothing
      expect(runs == 5L) and expect(preds > 0) and expect(scored > 0) and
      expect(sChamp.exists(c => Set("seasonal_naive", "seasonal_ets").contains(c._1))) and // a SEASONAL family wins
      expect(sChamp.exists(_._2 == BigDecimal(0))) and                                     // …with ZERO error on exact yearly repetition
      expect(
        lChamp.exists(c => !Set("seasonal_naive", "seasonal_ets").contains(c._1))
      ) and // the shifted cycle rejects seasonal
      expect(lChamp.exists(_._2 > 0)) and
      expect(sChamp.map(_._1) != lChamp.map(_._1)) // personalised, mechanical selection
  }

  test("a new origin extends the learning: more scored predictions, the champion stays data-driven") { xa =>
    val engine = new BacktestEngine[IO](xa)
    for {
      vid      <- seedVariant(xa)
      seasonal <- party(xa, "Seasonal Two Co")
      _        <- seedSeasonal(xa, seasonal, vid)
      _        <- engine.runOrigin(LocalDate.of(2025, 1, 1), horizonMonths = 3) // predict Q1'25 from ≤Q4'24
      _        <- engine.runOrigin(LocalDate.of(2025, 4, 1), horizonMonths = 3) // predict Q2'25 from ≤Q1'25
      _        <- engine.scoreOrigin(LocalDate.of(2025, 1, 1), asOf = LocalDate.of(2025, 10, 1))
      _        <- engine.scoreOrigin(LocalDate.of(2025, 4, 1), asOf = LocalDate.of(2025, 10, 1))
      origins <-
        sql"SELECT count(DISTINCT origin_month) FROM model_accuracy WHERE company_id = $seasonal"
          .query[Long]
          .unique
          .transact(xa)
      champ <- engine.champion(seasonal)
    } yield expect(origins == 2L) and
      expect(champ.exists(c => Set("seasonal_naive", "seasonal_ets").contains(c._1)))
  }
}
