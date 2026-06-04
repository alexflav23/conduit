package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.commission._
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import com.tigerbeetle.Client
import doobie.hikari.HikariTransactor
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// Commission two-phase lifecycle against a real TigerBeetle cluster + Postgres for the entries.
object CommissionLedgerSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] =
    (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val scheme =
    CommissionScheme(UUID.randomUUID(), "gross_margin", BigDecimal("10"), "zero", Instant.parse("2026-01-01T00:00:00Z"), None)
  private val line = CommissionLineInput(BigDecimal("587.50"), BigDecimal("400.00"), 2, "standard", exceptionApproved = false)
  // gross margin = 375.00 ; 10% = 37.50 -> 3750 minor units

  private def seedScheme(xa: HikariTransactor[IO]): IO[(UUID, UUID)] = {
    import doobie.implicits._
    import doobie.postgres.implicits._
    (for {
      sid <- sql"""INSERT INTO commission_scheme (name, basis, rate_pct, exception_treatment, valid_from)
                   VALUES ('WS 10%', 'gross_margin', 10, 'zero', '2026-01-01T00:00:00Z') RETURNING id""".query[UUID].unique
      _   <- sql"INSERT INTO commission_scheme_assignment (scheme_id) VALUES ($sid)".update.run
      aid <- sql"INSERT INTO sales_agent (name) VALUES ('Agent W') RETURNING id".query[UUID].unique
    } yield (sid, aid)).transact(xa)
  }

  private def service(xa: HikariTransactor[IO], client: Client): (CommissionService[IO], TigerBeetleLedger[IO]) = {
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    (new CommissionService[IO](xa, ledger, expenseEntity = "uk"), ledger)
  }

  test("accrue posts a PENDING transfer; post earns it (posted credits on COMM_PAYABLE)") { case (xa, client) =>
    val (svc, ledger) = service(xa, client)
    val gbp = Ledgers.forCurrency(Currency.GBP)
    for {
      ids <- seedScheme(xa)
      (schemeId, agentId) = ids
      payable = svc.payableAccount(agentId, "GBP")
      _ <- ledger.createAccounts(List(LedgerAccount(svc.expenseAccount("GBP"), gbp, LedgerAccountCode.CommPayable), LedgerAccount(payable, gbp, LedgerAccountCode.CommPayable)))
      entryId <- svc.accrue(agentId, schemeId, None, "GBP", scheme, line)
      afterAccrue <- ledger.balance(payable)
      _ <- svc.post(entryId, BigDecimal("37.50"))
      afterPost <- ledger.balance(payable)
      stmt <- svc.statementTotal(agentId)
    } yield expect(afterAccrue.creditsPending == BigInt(3750)) and
      expect(afterAccrue.creditsPosted == BigInt(0)) and
      expect(afterPost.creditsPosted == BigInt(3750)) and
      expect(afterPost.creditsPending == BigInt(0)) and
      expect(stmt == BigDecimal("37.50")) // statement reconciles to ledger (3750 minor == £37.50)
  }

  test("commission trues up to the actual batch margin (delta booked as a current-period adjustment)") { case (xa, client) =>
    val (svc, ledger) = service(xa, client)
    val gbp = Ledgers.forCurrency(Currency.GBP)
    for {
      ids <- seedScheme(xa)
      (schemeId, agentId) = ids
      payable = svc.payableAccount(agentId, "GBP")
      _ <- ledger.createAccounts(List(LedgerAccount(svc.expenseAccount("GBP"), gbp, LedgerAccountCode.CommPayable), LedgerAccount(payable, gbp, LedgerAccountCode.CommPayable)))
      entryId <- svc.accrue(agentId, schemeId, None, "GBP", scheme, line) // std_cost 400 -> margin 375 -> 37.50
      _       <- svc.post(entryId, BigDecimal("37.50"))
      // actual batch landed cost 350 -> (587.50-350)*2*10% = 47.50 ; delta +10.00
      tu      <- svc.trueUp(agentId, schemeId, None, "GBP", BigDecimal("10"), BigDecimal("587.50"), 2, BigDecimal("37.50"), BigDecimal("350.00"))
      (_, delta) = tu
      bal  <- ledger.balance(payable)
      stmt <- svc.statementTotal(agentId)
    } yield expect(delta == BigDecimal("10.00")) and
      expect(bal.creditsPosted == BigInt(4750)) and
      expect(stmt == BigDecimal("47.50"))
  }

  test("claw voids the pending accrual (no posted credit)") { case (xa, client) =>
    val (svc, ledger) = service(xa, client)
    val gbp = Ledgers.forCurrency(Currency.GBP)
    for {
      ids <- seedScheme(xa)
      (schemeId, agentId) = ids
      payable = svc.payableAccount(agentId, "GBP")
      _ <- ledger.createAccounts(List(LedgerAccount(svc.expenseAccount("GBP"), gbp, LedgerAccountCode.CommPayable), LedgerAccount(payable, gbp, LedgerAccountCode.CommPayable)))
      entryId <- svc.accrue(agentId, schemeId, None, "GBP", scheme, line)
      _ <- svc.claw(entryId)
      bal <- ledger.balance(payable)
    } yield expect(bal.creditsPending == BigInt(0)) and expect(bal.creditsPosted == BigInt(0))
  }
}
