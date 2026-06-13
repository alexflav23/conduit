package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.intercompany.HedgeValuationService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M-IC-FX slice 4a (spec doc 28 §5.5): hedge performance + ASC 815-50 / Item 305 disclosure. Each period the
// hedge is valued at spot — its fair value = (contracted − spot) × open notional — and recorded, so treasury
// sees how each individual hedge performs over its life, per market. Designation enforces the ASC 815
// inception-documentation rule (cash_flow / net_investment need a doc_ref, fail-closed; economic is default).
// CTRL-HEDGE-PERF re-derives the figure. Measurement only — no ledger posting in this slice.
object HedgePerfSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def entity(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES (${s"SG-${UUID.randomUUID().toString.take(6)}"},'SG','USD','procurement') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def hedge(xa: HikariTransactor[IO], e: UUID, contracted: String, notional: String): IO[UUID] =
    sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
          VALUES ($e, 'GBP', 'USD', ${BigDecimal(contracted)}, ${BigDecimal(notional)},
                  ${LocalDate.now().minusMonths(2)}, ${LocalDate.now().plusMonths(10)}, 'active') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def rate(xa: HikariTransactor[IO], asOf: LocalDate, r: String): IO[Unit] =
    sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
          VALUES ('GBP','USD', ${BigDecimal(r)}, 'spot', $asOf, 'test') ON CONFLICT DO NOTHING""".update.run
      .transact(xa)
      .void

  private def control(xa: HikariTransactor[IO]): IO[Long] =
    new ControlRunner[IO](xa).run("CTRL-HEDGE-PERF", None).map(_.toOption.get.violations)

  test("a hedge is valued each period: fair value = (contracted − spot) × notional, and the deltas chain") {
    case xa =>
      val svc = new HedgeValuationService[IO](xa)
      for {
        e   <- entity(xa)
        hid <- hedge(xa, e, "1.28000000", "1000.00") // locked at 1.28 on 1000 GBP
        // period 1: GBP weakens to 1.25 → hedge in-the-money (1.28 − 1.25) × 1000 = +30.00
        _  <- rate(xa, LocalDate.now().minusDays(2), "1.25")
        v1 <- svc.revalue(LocalDate.now().minusDays(2))
        // period 2: GBP recovers to 1.27 → cumulative (1.28 − 1.27) × 1000 = +10.00; period delta = −20.00
        _  <- rate(xa, LocalDate.now().minusDays(1), "1.27")
        v2 <- svc.revalue(LocalDate.now().minusDays(1))
        disc <-
          sql"SELECT fair_value_gain_loss, latest_spot FROM hedge_disclosure WHERE id = $hid"
            .query[(BigDecimal, BigDecimal)]
            .unique
            .transact(xa)
        chained <-
          sql"SELECT COALESCE(SUM(period_mtm),0) FROM hedge_valuation WHERE fx_hedge_id = $hid"
            .query[BigDecimal]
            .unique
            .transact(xa)
        ctrl <- control(xa)
      } yield expect(v1.exists(_.cumulativeMtm == BigDecimal("30.0000"))) and
        expect(v2.exists(v => v.cumulativeMtm == BigDecimal("10.0000") && v.periodMtm == BigDecimal("-20.0000"))) and
        expect.same(disc._1, BigDecimal("10.0000")) and // the disclosure surface shows the live fair value
        expect.same(disc._2, BigDecimal("1.27000000")) and
        expect.same(chained, BigDecimal("10.0000")) and // Σ period deltas == latest cumulative
        expect.same(ctrl, 0L)
  }

  test("designation enforces ASC 815-20-25: cash_flow/net_investment need documentation, economic does not") {
    case xa =>
      val svc = new HedgeValuationService[IO](xa)
      for {
        e     <- entity(xa)
        hid   <- hedge(xa, e, "1.28", "500.00")
        blind <- svc.designate(hid, "cash_flow", None)        // no doc → fail closed
        blank <- svc.designate(hid, "cash_flow", Some("  "))  // blank doc → fail closed
        bad   <- svc.designate(hid, "speculative", Some("x")) // unknown designation
        ok    <- svc.designate(hid, "cash_flow", Some("HEDGE-DOC-2026-014"))
        eco   <- svc.designate(hid, "economic", None)         // economic needs none
        stored <-
          sql"SELECT designation, doc_ref FROM fx_hedge WHERE id = $hid"
            .query[(String, Option[String])]
            .unique
            .transact(xa)
      } yield expect(blind.isLeft) and expect(blank.isLeft) and expect(bad.isLeft) and
        expect(ok.isRight) and expect(eco.isRight) and
        expect.same(stored, ("economic", None)) // last write wins; economic clears the doc
  }

  test("DETECTION: a corrupted fair value fails CTRL-HEDGE-PERF, restored on fix") {
    case xa =>
      val svc = new HedgeValuationService[IO](xa)
      for {
        e     <- entity(xa)
        hid   <- hedge(xa, e, "1.30", "200.00")
        _     <- rate(xa, LocalDate.now(), "1.20")
        _     <- svc.revalue(LocalDate.now())
        clean <- control(xa)
        saved <-
          sql"SELECT cumulative_mtm FROM hedge_valuation WHERE fx_hedge_id = $hid".query[BigDecimal].unique.transact(xa)
        _ <-
          sql"UPDATE hedge_valuation SET cumulative_mtm = cumulative_mtm + 1 WHERE fx_hedge_id = $hid".update.run
            .transact(xa)
        broken <- control(xa)
        _      <- sql"UPDATE hedge_valuation SET cumulative_mtm = $saved WHERE fx_hedge_id = $hid".update.run.transact(xa)
        fixed  <- control(xa)
      } yield expect.same(clean, 0L) and expect(broken > 0L) and expect.same(fixed, 0L)
  }
}
