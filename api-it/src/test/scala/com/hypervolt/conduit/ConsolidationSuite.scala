package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.gl.ConsolidationService
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.migration.MigrationService
import com.hypervolt.conduit.migration.OpeningLine
import com.hypervolt.conduit.money.Currency
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13b-GL (Option B, stage 3) — hedge-aware, as-of consolidation over the gl_entry mirror (ASC 830, doc 14 §2.4 /
// doc 13 §7.2). Two entities in different functional currencies are translated to USD: the GBP entity at the
// provenanced closing rate, the EUR entity at its DESIGNATED HEDGE rate (the locked rate, not spot). The run is an
// immutable, re-derivable snapshot recording which rate/hedge each line used; the CTA + FX-clearing controls pass.
object ConsolidationSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  // The opening balances post at the wall-clock instant; the as-of cutoff must sit at/after that, so we roll up
  // at year-end (also within the hedge's validity window).
  private val asOf = LocalDate.of(2026, 12, 31)

  private def entity(xa: HikariTransactor[IO], ccy: String): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES (${"E-" + ccy}, 'GB', $ccy, 'operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  // Seed a balanced opening balance (INV asset vs OPENING_BALANCE_EQUITY) into the gl_entry mirror for one entity.
  private def opening(svc: MigrationService[IO], e: UUID, ccy: Currency, minor: BigInt): IO[Unit] =
    svc.ensureAccounts(e, ccy, List((svc.invAccount(e), LedgerAccountCode.Inv))) *>
      svc
        .postOpeningBalances(
          "sim",
          "lot_batch",
          e,
          ccy,
          List(OpeningLine("L-" + UUID.randomUUID(), "INV:" + e, LedgerAccountCode.Inv, debitNormal = true, minor))
        )

  private def latestControl(xa: HikariTransactor[IO], code: String): IO[Option[String]] =
    sql"""SELECT cr.result FROM control_run cr JOIN control c ON c.id = cr.control_id
          WHERE c.code = $code ORDER BY cr.run_at DESC LIMIT 1""".query[String].option.transact(xa)

  test("two-currency consolidation: GBP at the closing rate, EUR at its locked hedge rate; CTA + FX controls pass") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new MigrationService[IO](xa, ledger)
      val consol = new ConsolidationService[IO](xa)
      for {
        gbpE <- entity(xa, "GBP")
        eurE <- entity(xa, "EUR")
        // GBP→USD has only a closing rate; EUR→USD has a designated hedge (the locked rate the run must use).
        _ <-
          sql"INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source) VALUES ('GBP','USD',1.25000000,'closing',$asOf,'ecb')".update.run
            .transact(xa)
        _ <-
          sql"INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source) VALUES ('EUR','USD',1.30000000,'spot',$asOf,'ecb')".update.run
            .transact(xa)
        hedgeId <-
          sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
                VALUES ($eurE,'EUR','USD',1.10000000,1000000,'2026-01-01','2026-12-31','active') RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        _   <- opening(svc, gbpE, Currency.GBP, BigInt(10000)) // £100.00 INV
        _   <- opening(svc, eurE, Currency.EUR, BigInt(20000)) // €200.00 INV
        run <- consol.run(asOf, "USD", None)
        runId  = UUID.fromString(run.hcursor.get[String]("run_id").toOption.get)
        lines  = run.hcursor.downField("lines").as[List[Json]].getOrElse(Nil)
        gbpInv = lines.find(_.hcursor.get[String]("account").toOption.contains("INV:" + gbpE))
        eurInv = lines.find(_.hcursor.get[String]("account").toOption.contains("INV:" + eurE))
        ctaCtrl <- latestControl(xa, "CTRL-CTA-BALANCE")
        fxCtrl  <- latestControl(xa, "CTRL-FXCLEARING-ZERO")
        lin     <- consol.lineage(runId)
      } yield {
        def rate(l: Option[Json])   = l.flatMap(_.hcursor.get[BigDecimal]("rate").toOption)
        def source(l: Option[Json]) = l.flatMap(_.hcursor.get[String]("rate_source").toOption)
        def pres(l: Option[Json])   = l.flatMap(_.hcursor.get[BigDecimal]("balance_presentation").toOption)
        expect(run.hcursor.get[Boolean]("balanced").toOption.contains(true)) and // native books sound
          expect(rate(gbpInv).contains(BigDecimal("1.25000000"))) and            // GBP at the closing rate
          expect(source(gbpInv).contains("closing")) and
          expect(pres(gbpInv).contains(BigDecimal("125.00"))) and // £100 × 1.25
          expect(
            rate(eurInv).contains(BigDecimal("1.10000000"))
          ) and // EUR at the LOCKED hedge rate (not the 1.30 spot)
          expect(source(eurInv).exists(_ == "hedge:" + hedgeId)) and
          expect(pres(eurInv).contains(BigDecimal("220.00"))) and                  // €200 × 1.10
          expect(ctaCtrl.contains("pass")) and expect(fxCtrl.contains("pass")) and // translation-integrity controls
          expect(
            lin.flatMap(_.hcursor.downField("lines").as[List[Json]].toOption).exists(_.size == 4)
          ) // run re-derivable: 4 lines (2 INV + 2 equity)
      }
  }
}
